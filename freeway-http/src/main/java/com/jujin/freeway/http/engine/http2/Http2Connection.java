package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jujin.freeway.http.engine.http2.hpack.HPackContext;
import com.jujin.freeway.http.engine.http2.hpack.HeaderFields;

/**
 * HTTP/2 connection controller. Manages the full connection lifecycle:
 * frame processing (SETTINGS, PING, GOAWAY), stream-map management,
 * connection-level flow control, and the HPACK codec instance.
 */
public final class Http2Connection {

    private static final Logger LOG = LoggerFactory.getLogger(Http2Connection.class);

    public static final String PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
    public static final String PARTIAL_PREFACE = "\r\nSM\r\n\r\n";

    private static final int DEFAULT_WINDOW_SIZE = 65535;
    private static final int DEFAULT_MAX_FRAME_SIZE = 16384;
    /**
     * Server-side cap on open streams (RFC 7540 §5.1.2). Excess streams are
     * rejected with RST_STREAM(REFUSED_STREAM) instead of opening unbounded
     * per-stream state. */
    private static final int MAX_CONCURRENT_STREAMS = 100;
    /**
     * Cap on a single inbound header block (across HEADERS + CONTINUATION
     * fragments) before HPACK decode — bounds memory under a malicious peer.
     * This is the connection's raw-wire bound; the *decoded* list size is
     * bounded separately by {@link #MAX_HEADER_LIST_SIZE} (enforced inside
     * {@link com.jujin.freeway.http.engine.http2.hpack.HPackContext#decode}),
     * and dynamic-table sizing is owned entirely by HPackContext. */
    private static final int MAX_INBOUND_HEADER_BLOCK = 64 * 1024;
    /**
     * Advertised SETTINGS_MAX_HEADER_LIST_SIZE (RFC 7540 §6.5.2), passed as
     * the decode-time cap so a peer cannot inflate the decoded field list
     * beyond what we advertise. */
    private static final int MAX_HEADER_LIST_SIZE = 64 * 1024;

    final AtomicLong sendWindow = new AtomicLong(DEFAULT_WINDOW_SIZE);
    final AtomicInteger receiveWindow = new AtomicInteger(DEFAULT_WINDOW_SIZE);

    private final InputStream inputStream;
    private final Http2FrameWriter writer;
    private final Socket socket;
    private final ExecutorService executor;
    private final StreamHandler handler;
    /** Socket read idle timeout in millis (0 = disabled). Only applied while
     *  the connection has no open streams — an active stream must never be
     *  killed by connection-level idle timeout (RFC 7540 multiplexing). */
    private final int readTimeoutMillis;
    /** Last SO_TIMEOUT value applied, to avoid a syscall per frame. */
    private int currentSoTimeout = -1;
    private final HPackContext hpack = new HPackContext();
    final ConcurrentHashMap<Integer, Http2Stream> streams = new ConcurrentHashMap<>();
    /** Peer's advertised SETTINGS_MAX_FRAME_SIZE — caps our OUTBOUND DATA chunking. */
    volatile int peerMaxFrameSize = 16384;
    private final Settings remoteSettings = new Settings();
    private final Settings localSettings = new Settings();
    /** Reused by the single reader thread to avoid per-frame header allocation. */
    private final byte[] frameHeaderBuffer = new byte[9];
    /** Set once GOAWAY has been sent or received — no new streams may be
     *  created afterwards (RFC 7540 §6.8). */
    private volatile boolean goawaySentOrReceived;

    /** Threads blocked on connection/stream flow control, unparked on WINDOW_UPDATE. */
    final Set<Thread> windowWaiters = ConcurrentHashMap.newKeySet();

    void unparkWindowWaiters() {
        for (Thread t : windowWaiters) LockSupport.unpark(t);
    }
    private final AtomicBoolean closed = new AtomicBoolean();

    private int lastSeenStreamId;

    public Http2Connection(Socket socket, InputStream inputStream, OutputStream outputStream,
                           ExecutorService executor, StreamHandler handler,
                           int readTimeoutMillis) {
        this.socket = socket;
        this.inputStream = inputStream;
        this.writer = new Http2FrameWriter(outputStream);
        this.executor = executor;
        this.handler = handler;
        this.readTimeoutMillis = readTimeoutMillis;
        localSettings.set(new SettingParameter(SettingIdentifier.SETTINGS_MAX_FRAME_SIZE, DEFAULT_MAX_FRAME_SIZE));
        localSettings.set(new SettingParameter(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, DEFAULT_WINDOW_SIZE));
        localSettings.set(new SettingParameter(
            SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, MAX_CONCURRENT_STREAMS));
        localSettings.set(new SettingParameter(
            SettingIdentifier.SETTINGS_MAX_HEADER_LIST_SIZE, MAX_HEADER_LIST_SIZE));
    }

    void lock() {
        writer.lock();
    }

    void unlock() {
        writer.unlock();
    }

    public boolean isClosed() {
        return closed.get();
    }

    Settings remoteSettings() {
        return remoteSettings;
    }

    Settings localSettings() {
        return localSettings;
    }

    HPackContext hpack() {
        return hpack;
    }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Wake writers blocked on flow control — they re-check closed and exit.
            unparkWindowWaiters();
            for (var stream : streams.values()) stream.close();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public boolean hasProperPreface(boolean ssl) throws IOException {
        String expected = ssl ? PREFACE : PARTIAL_PREFACE;
        byte[] buffer = new byte[expected.length()];
        FrameSerializer.readFully(inputStream, buffer);
        return expected.equals(new String(buffer));
    }

    void writeFrame(byte[]... frames) throws IOException {
        writer.writeFrame(frames);
    }

    void writeDataFrame(byte[] header, byte[] payload, int offset, int length)
            throws IOException {
        writer.writeDataFrame(header, payload, offset, length);
    }

    public void handle() throws IOException {
        try {
            processFrames();
        } catch (Http2Exception e) {
            LOG.debug("H2 error", e);
            sendGoAway(e.errorCode());
            throw e;
        }
    }

    private void processFrames() throws IOException {
        var headerBlock = new HeaderBlockState();

        while (!closed.get()) {
            updateReadTimeout();
            var frame = FrameSerializer.deserialize(
                inputStream, DEFAULT_MAX_FRAME_SIZE, frameHeaderBuffer);
            int streamId = frame.header().streamId();
            Http2FrameValidator.requireClientStreamId(streamId);

            switch (frame.header().type()) {
                case SETTINGS -> {
                    // RFC 7540 §4.3: a header block may be interrupted only
                    // by CONTINUATION; any other frame is a connection error.
                    Http2FrameValidator.requireNotInHeaderBlock(
                        headerBlock.inHeaders);
                    if (frame.header().flags().contains(FrameFlag.ACK)) {
                        continue;
                    }
                    updateRemoteSettings((SettingsFrame) frame);
                    sendSettingsAck();
                    continue;
                }
                case GOAWAY -> {
                    Http2FrameValidator.requireNotInHeaderBlock(
                        headerBlock.inHeaders);
                    var goaway = (GoawayFrame) frame;
                    if (goaway.errorCode != Http2ErrorCode.NO_ERROR)
                        throw new IOException("GOAWAY");
                    // RFC 7540 §6.8: after receiving GOAWAY the endpoint must
                    // not create new streams — new HEADERS are RST'd below.
                    goawaySentOrReceived = true;
                    continue;
                }
                case PING -> {
                    Http2FrameValidator.requireNotInHeaderBlock(
                        headerBlock.inHeaders);
                    if (!frame.header().flags().contains(FrameFlag.ACK))
                        sendPingAck((PingFrame) frame);
                    continue;
                }
                case WINDOW_UPDATE -> {
                    Http2FrameValidator.requireNotInHeaderBlock(
                        headerBlock.inHeaders);
                    if (streamId == 0) {
                        int increment = ((WindowUpdateFrame) frame).increment();
                        Http2FrameValidator.requirePositiveWindowIncrement(
                            increment);
                        if (Http2FrameValidator.sendWindowOverflow(
                                sendWindow.addAndGet(increment))) {
                            throw new Http2Exception(
                                Http2ErrorCode.FLOW_CONTROL_ERROR);
                        }
                        unparkWindowWaiters();
                        continue;
                    }
                    // Stream-level WINDOW_UPDATE dispatches below.
                }
                case NOT_IMPLEMENTED -> {
                    Http2FrameValidator.requireNotInHeaderBlock(
                        headerBlock.inHeaders);
                    // RFC 7540 §5.5: ignore unknown/unimplemented frames.
                    continue;
                }
                case DATA -> {
                    Http2FrameValidator.requireNotInHeaderBlock(
                        headerBlock.inHeaders);
                    if (streamId == 0) {
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    }
                    var dataFrame = (DataFrame) frame;
                    int flowLength = dataFrame.flowLength();
                    if (flowLength > receiveWindow.get()) {
                        throw new Http2Exception(
                            Http2ErrorCode.FLOW_CONTROL_ERROR);
                    }
                    receiveWindow.addAndGet(-flowLength);
                    if (receiveWindow.get() < DEFAULT_WINDOW_SIZE / 10) {
                        sendConnectionWindowUpdate();
                    }
                }
                case HEADERS -> {
                    if (streamId == 0) {
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    }
                    Http2FrameValidator.requireNotInHeaderBlock(
                        headerBlock.inHeaders);
                    if (streamId < lastSeenStreamId) {
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    }
                    headerBlock.add(((HeadersFrame) frame).headerBlock());
                    if (streams.containsKey(streamId)) {
                        // Any HEADERS on an already-created stream is a trailer
                        // block (RFC 7540 §8.1.2.2) — the request pseudo-headers
                        // arrived with the first HEADERS. The stream is still
                        // "open" here because END_STREAM on this very frame has
                        // not been applied yet, so state cannot drive the check.
                        // The shared handling decodes it to keep HPACK state in
                        // sync and discards the fields.
                        headerBlock.endStream = frame.header().flags()
                            .contains(FrameFlag.END_STREAM);
                    } else if (frame.header().flags()
                            .contains(FrameFlag.END_STREAM)) {
                        headerBlock.endStream = true;
                    }
                    if (!frame.header().flags()
                            .contains(FrameFlag.END_HEADERS)) {
                        headerBlock.start(streamId);
                        continue;
                    }
                    // END_HEADERS set — fall through to shared handling below.
                }
                case CONTINUATION -> {
                    if (!headerBlock.inHeaders
                            || streamId != headerBlock.openStreamId) {
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    }
                    headerBlock.add(((ContinuationFrame) frame).headerBlock());
                    if (!frame.header().flags()
                            .contains(FrameFlag.END_HEADERS)) {
                        continue;
                    }
                    // Fall through to shared handling once the block completes.
                }
                case PRIORITY -> {
                    Http2FrameValidator.requireNotInHeaderBlock(
                        headerBlock.inHeaders);
                    if (streamId == 0) {
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    }
                    continue;
                }
                case PUSH_PROMISE -> {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                }
                case RST_STREAM -> {
                    Http2FrameValidator.requireNotInHeaderBlock(
                        headerBlock.inHeaders);
                    // Any RST_STREAM (even error code 0) terminates the target
                    // stream — close it so the handler and stream state are
                    // released instead of leaking.
                    if (streamId == 0) {
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    }
                    var resetTarget = streams.get(streamId);
                    if (resetTarget != null) {
                        resetTarget.close();
                    } else if (streamId > lastSeenStreamId) {
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    }
                    continue;
                }
            }

            var target = streams.get(streamId);
            if (target == null && lastSeenStreamId < streamId) {
                // New stream: reject when GOAWAY was exchanged (RFC 7540
                // §6.8, peer may retry on a fresh connection) or the
                // concurrent-stream cap is reached (RFC 7540 §5.1.2,
                // streams.size() is the open-stream count because close()
                // removes from the map).
                if (goawaySentOrReceived || streams.size() >= MAX_CONCURRENT_STREAMS) {
                    rejectNewStream(
                        Http2ErrorCode.REFUSED_STREAM, streamId, headerBlock);
                    continue;
                }
                boolean requestEndStream = headerBlock.endStream;
                var fields = decodeFields(headerBlock);
                fields.validate();

                Map<String, List<String>> requestHeaders =
                    new LinkedHashMap<>(fields.size() * 2);
                for (var headerField : fields.fields()) {
                    if (headerField.value != null) {
                        requestHeaders.computeIfAbsent(
                            headerField.normalizedName,
                            k -> new ArrayList<>(4)).add(headerField.value);
                    }
                }

                headerBlock.reset();
                target = new Http2Stream(streamId, this, requestHeaders, handler);
                streams.put(streamId, target);
                lastSeenStreamId = streamId;
                if (requestEndStream) {
                    target.markHalfClosed();
                }
                target.startRequest(executor);
                // Header block assembled — this frame consumed, skip dispatch.
                continue;
            } else if (target != null
                    && (frame.header().type() == FrameType.HEADERS
                        || frame.header().type() == FrameType.CONTINUATION)) {
                // Trailer header block (RFC 7540 §8.1.2.2) on a stream that
                // already received its request headers. Decode it so the
                // HPACK dynamic table stays in sync with the peer, then
                // discard — trailers carry no request semantics we expose.
                // add() still rejects pseudo-headers and connection-specific
                // fields inside the block; the REQUIRED pseudo-header check
                // in validate() only applies to request header blocks.
                var fields = decodeFields(headerBlock);
                boolean trailerEndStream = headerBlock.endStream
                    || frame.header().flags().contains(FrameFlag.END_STREAM);
                headerBlock.reset();
                if (trailerEndStream) {
                    // END_STREAM on the trailer ends the request body: wake
                    // the body reader so handler body() returns EOF naturally.
                    // The stream itself stays open until the response side
                    // finishes and closes it (Http2OutputStream.close).
                    target.markHalfClosed();
                    target.wakeupBodyReader();
                }
                continue;
            } else if (target == null) {
                if (streamId <= lastSeenStreamId) {
                    if (frame.header().type() == FrameType.WINDOW_UPDATE) {
                        continue;
                    }
                    // The frame targets a closed stream; this is a stream
                    // error and must not terminate other active streams.
                    sendResetStream(Http2ErrorCode.STREAM_CLOSED, streamId);
                    continue;
                }
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
            }

            dispatchToStream(target, frame, streamId);
        }
    }

    /** Decodes a combined HPACK block into {@link HeaderFields}, applying
     *  per-field validity rules (pseudo-header placement, prohibited
     *  connection-specific fields). */
    private HeaderFields decodeFields(HeaderBlockState headerBlock)
            throws IOException {
        var fields = new HeaderFields();
        for (var field : hpack.decode(headerBlock.combined(), MAX_HEADER_LIST_SIZE)) {
            fields.add(field);
        }
        return fields;
    }

    /** Rejects a new-stream header block with the given error and clears the
     *  in-progress block state. */
    private void rejectNewStream(Http2ErrorCode code, int streamId,
                                 HeaderBlockState headerBlock)
            throws IOException {
        lastSeenStreamId = streamId;
        headerBlock.reset();
        sendResetStream(code, streamId);
    }

    /** Dispatches a frame to a stream, converting stream errors into a reset
     *  without tearing down the multiplexed connection. */
    private void dispatchToStream(Http2Stream target, BaseFrame frame,
                                  int streamId) throws IOException {
        try {
            target.dispatch(frame, executor);
        } catch (Http2Exception e) {
            // A stream error must not tear down the multiplexed connection.
            sendResetStream(e.errorCode(), streamId);
            target.close();
        }
    }

    /** In-progress HEADERS/CONTINUATION header block. Tracks the raw fragment
     *  bytes (bounded by {@link #MAX_INBOUND_HEADER_BLOCK}) and whether the
     *  block carries END_STREAM, so the stream can be marked half-closed once
     *  the block completes. */
    private static final class HeaderBlockState {
        final List<byte[]> fragments = new ArrayList<>();
        int size;
        boolean inHeaders;
        int openStreamId;
        boolean endStream;

        void add(byte[] block) throws Http2Exception {
            if (size + block.length > MAX_INBOUND_HEADER_BLOCK) {
                throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                    "Header block exceeds " + MAX_INBOUND_HEADER_BLOCK
                        + " bytes");
            }
            fragments.add(block);
            size += block.length;
        }

        void start(int streamId) {
            inHeaders = true;
            openStreamId = streamId;
        }

        byte[] combined() {
            return BinUtils.combine(fragments);
        }

        void reset() {
            fragments.clear();
            size = 0;
            inHeaders = false;
            openStreamId = 0;
            endStream = false;
        }
    }

    /**
     * Applies the read idle timeout only when the connection is truly idle
     * (no open streams). While any stream is active the socket read blocks
     * indefinitely — a slow client pausing mid-request must not tear down
     * the whole multiplexed connection. Called at a frame boundary, so the
     * SO_TIMEOUT change never splits a partially-read frame.
     */
    private void updateReadTimeout() throws IOException {
        int timeout = streams.isEmpty() ? readTimeoutMillis : 0;
        if (timeout != currentSoTimeout) {
            socket.setSoTimeout(timeout);
            currentSoTimeout = timeout;
        }
    }

    private void updateRemoteSettings(SettingsFrame settingsFrame) throws IOException {
        for (var parameter : settingsFrame.params) {
            long oldWindow = remoteSettings.getOrDefault(
                    SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE,
                    SettingParameter.DEFAULT_INITIAL_WINDOW_SIZE).value;
            if (parameter.identifier == SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE) {
                if (parameter.value > Integer.MAX_VALUE)
                    throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR);
                for (var stream : streams.values())
                    stream.sendWindow.addAndGet(parameter.value - oldWindow);
                // SETTINGS can unblock writers without a WINDOW_UPDATE.
                unparkWindowWaiters();
            } else if (parameter.identifier == SettingIdentifier.SETTINGS_MAX_FRAME_SIZE) {
                peerMaxFrameSize = (int) Math.min(parameter.value, 16_777_215); // RFC max
            } else if (parameter.identifier == SettingIdentifier.SETTINGS_HEADER_TABLE_SIZE) {
                // RFC 7540 §6.5.2: SETTINGS_HEADER_TABLE_SIZE is a 32-bit
                // unsigned value. The wire parse is unsigned (a wire
                // "negative" is a large positive), so an out-of-range value
                // can only reach this point through a programmatically built
                // SettingsFrame — reject it as a connection error like the
                // ENABLE_PUSH check below instead of letting a negative cap
                // poison the HPACK decoder state.
                if (parameter.value < 0 || parameter.value > 0xFFFFFFFFL)
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                hpack.setMaxDynamicTableSize(parameter.value);
            } else if (parameter.identifier == SettingIdentifier.SETTINGS_ENABLE_PUSH) {
                // RFC 7540 §6.5.2: ENABLE_PUSH is sent by clients (it disables
                // server push); any value other than 0 or 1 is a connection error.
                if (parameter.value != 0 && parameter.value != 1)
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
            }
            remoteSettings.set(parameter);
        }
    }

    /** Applies the client SETTINGS payload carried by an h2c Upgrade header. */
    public void applyUpgradeSettings(SettingsFrame settingsFrame) throws IOException {
        updateRemoteSettings(settingsFrame);
    }

    /**
     * Installs stream 1 for an h2c upgrade (RFC 7540 §3.2). The HTTP/1.1
     * request that negotiated the upgrade becomes stream 1, implicitly
     * half-closed from the client side — the client never sends HEADERS for
     * it. Must be invoked after the server connection preface (SETTINGS) has
     * been written so a fast handler cannot put response frames ahead of our
     * preface.
     */
    public void prepopulateUpgradeStream(Map<String, List<String>> requestHeaders)
            throws IOException {
        if (lastSeenStreamId != 0)
            throw new IllegalStateException("upgrade stream 1 already in use");
        var target = new Http2Stream(1, this, requestHeaders, handler);
        streams.put(1, target);
        lastSeenStreamId = 1;
        target.markHalfClosed();
        target.startRequest(executor);
    }

    public void sendMySettings() throws IOException {
        var sf = new SettingsFrame(new FrameHeader(0, FrameType.SETTINGS, FrameFlag.NONE, 0));
        localSettings.forEach(sf.params::add);
        writeFrame(sf.encode());
    }

    private void sendSettingsAck() throws IOException {
        writeFrame(FrameHeader.encode(0, FrameType.SETTINGS,
            FrameFlag.FlagSet.of(FrameFlag.ACK), 0));
    }

    private void sendConnectionWindowUpdate() throws IOException {
        int current = receiveWindow.get();
        int increment = DEFAULT_WINDOW_SIZE - current;
        receiveWindow.addAndGet(increment);
        writeFrame(new WindowUpdateFrame(0, increment).encode());
    }

    public void sendGoAway(Http2ErrorCode errorCode) throws IOException {
        // Sending GOAWAY has the same effect as receiving one (RFC 7540
        // §6.8): no new streams may be created afterwards.
        goawaySentOrReceived = true;
        writeFrame(new GoawayFrame(errorCode, lastSeenStreamId).encode());
    }

    public void sendResetStream(Http2ErrorCode errorCode, int streamId) throws IOException {
        writeFrame(new ResetStreamFrame(errorCode, streamId).encode());
    }

    private void sendPingAck(PingFrame pingFrame) throws IOException {
        writeFrame(new PingFrame(pingFrame).encode());
    }

    InetSocketAddress remoteAddress() {
        return (InetSocketAddress) socket.getRemoteSocketAddress();
    }

    InetSocketAddress localAddress() {
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }

    @FunctionalInterface
    public interface StreamHandler {
        void handle(Http2Stream stream, InputStream inputStream, OutputStream outputStream,
                    Map<String, List<String>> headers) throws IOException;
    }
}
