package com.jujin.freeway.http.engine.http2;

import com.jujin.freeway.http.engine.http2.frame.ContinuationFrame;
import com.jujin.freeway.http.engine.http2.frame.DataFrame;
import com.jujin.freeway.http.engine.http2.frame.FrameFlag;
import com.jujin.freeway.http.engine.http2.frame.FrameHeader;
import com.jujin.freeway.http.engine.http2.frame.FrameSerializer;
import com.jujin.freeway.http.engine.http2.frame.FrameType;
import com.jujin.freeway.http.engine.http2.frame.GoawayFrame;
import com.jujin.freeway.http.engine.http2.frame.HeadersFrame;
import com.jujin.freeway.http.engine.http2.frame.PingFrame;
import com.jujin.freeway.http.engine.http2.frame.ResetStreamFrame;
import com.jujin.freeway.http.engine.http2.frame.SettingIdentifier;
import com.jujin.freeway.http.engine.http2.frame.SettingParameter;
import com.jujin.freeway.http.engine.http2.frame.SettingsFrame;
import com.jujin.freeway.http.engine.http2.frame.SettingsMap;
import com.jujin.freeway.http.engine.http2.frame.WindowUpdateFrame;
import com.jujin.freeway.http.engine.http2.hpack.HPackContext;
import com.jujin.freeway.http.engine.http2.hpack.HeaderFields;
import com.jujin.freeway.http.engine.http2.util.BinUtils;
import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.ArrayDeque;
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
import java.util.concurrent.locks.ReentrantLock;

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
    /** Cap on a single inbound header block (across HEADERS + CONTINUATION
     *  fragments) before HPACK decode — bounds memory under a malicious peer. */
    private static final int MAX_INBOUND_HEADER_BLOCK = 64 * 1024;
    /** Advertised SETTINGS_MAX_HEADER_LIST_SIZE (RFC 7540 §6.5.2). */
    private static final int MAX_HEADER_LIST_SIZE = 64 * 1024;

    public final AtomicLong sendWindow = new AtomicLong(DEFAULT_WINDOW_SIZE);
    public final AtomicInteger receiveWindow = new AtomicInteger(DEFAULT_WINDOW_SIZE);

    private final InputStream inputStream;
    private final OutputStream outputStream;
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
    /** Our advertised SETTINGS_MAX_FRAME_SIZE — caps INBOUND frame payloads (RFC 7540 §4.2). */
    final int maxFrameSize = 16384;
    /** Peer's advertised SETTINGS_MAX_FRAME_SIZE — caps our OUTBOUND DATA chunking. */
    volatile int peerMaxFrameSize = 16384;
    private final SettingsMap remoteSettings = new SettingsMap();
    private final SettingsMap localSettings = new SettingsMap();
    /** Reused by the single reader thread to avoid per-frame header allocation. */
    private final byte[] frameHeaderBuffer = new byte[9];
    /** Set once GOAWAY has been sent or received — no new streams may be
     *  created afterwards (RFC 7540 §6.8). */
    private volatile boolean goawayReceived;

    private final int connectionWindowSize = DEFAULT_WINDOW_SIZE;
    private final ReentrantLock lock = new ReentrantLock();
    /** Outbound frame queue. Producers append under {@link #lock}; a single
     *  leader drains and flushes so concurrent streams coalesce into fewer
     *  socket writes. */
    private final ArrayDeque<OutboundChunk> outbound = new ArrayDeque<>();
    private boolean writing;
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
        this.outputStream = outputStream;
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
        lock.lock();
    }

    void unlock() {
        lock.unlock();
    }

    public boolean isClosed() {
        return closed.get();
    }

    SettingsMap remoteSettings() {
        return remoteSettings;
    }

    SettingsMap localSettings() {
        return localSettings;
    }

    OutputStream outputStream() {
        return outputStream;
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

    /**
     * Queues one or more whole frame byte-arrays and flushes all pending
     * frames. The first producer to find no active writer drains the queue in
     * a single write+flush; producers that join while a drain is running only
     * append, so concurrent streams share syscalls instead of each flushing
     * its own frame. Frames are drained in FIFO order.
     */
    void writeFrame(byte[]... frames) throws IOException {
        boolean leader;
        lock.lock();
        try {
            for (var frame : frames) {
                if (frame != null && frame.length > 0) {
                    outbound.add(new OutboundChunk(frame, 0, frame.length));
                }
            }
            leader = !writing;
            if (leader) writing = true;
        } finally {
            lock.unlock();
        }
        if (leader) drainOutbound();
    }

    /** Queues a DATA frame without copying the payload: header slice plus the
     *  payload range are appended contiguously under one lock acquisition. */
    void writeDataFrame(byte[] header, byte[] payload, int offset, int length)
            throws IOException {
        boolean leader;
        lock.lock();
        try {
            outbound.add(new OutboundChunk(header, 0, header.length));
            outbound.add(new OutboundChunk(payload, offset, length));
            leader = !writing;
            if (leader) writing = true;
        } finally {
            lock.unlock();
        }
        if (leader) drainOutbound();
    }

    private void drainOutbound() throws IOException {
        try {
            while (true) {
                OutboundChunk next;
                lock.lock();
                try {
                    next = outbound.poll();
                } finally {
                    lock.unlock();
                }
                if (next == null) {
                    lock.lock();
                    try {
                        if (outbound.isEmpty()) {
                            writing = false;
                            outputStream.flush();
                            return;
                        }
                    } finally {
                        lock.unlock();
                    }
                    continue;
                }
                outputStream.write(next.bytes, next.offset, next.length);
            }
        } catch (IOException e) {
            lock.lock();
            try {
                writing = false;
                outbound.clear();
            } finally {
                lock.unlock();
            }
            throw e;
        }
    }

    private record OutboundChunk(byte[] bytes, int offset, int length) {}

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
        boolean inHeaders = false;
        boolean headersEndStream = false;
        int openStreamId = 0;
        var headerBlockFragments = new ArrayList<byte[]>();
        int headerBlockSize = 0;

        while (!closed.get()) {
            updateReadTimeout();
            var frame = FrameSerializer.deserialize(
                inputStream, maxFrameSize, frameHeaderBuffer);
            int streamId = frame.header().streamId();

            if (streamId != 0 && streamId % 2 == 0)
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);

            switch (frame.header().type()) {
                case SETTINGS -> {
                    // RFC 7540 §4.3: a header block may be interrupted only
                    // by CONTINUATION; any other frame is a connection error.
                    if (inHeaders) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    if (frame.header().flags().contains(FrameFlag.ACK)) {
                        continue;
                    }
                    updateRemoteSettings((SettingsFrame) frame);
                    sendSettingsAck();
                    continue;
                }
                case GOAWAY -> {
                    if (inHeaders) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    var goaway = (GoawayFrame) frame;
                    if (goaway.errorCode != Http2ErrorCode.NO_ERROR)
                        throw new IOException("GOAWAY");
                    // RFC 7540 §6.8: after receiving GOAWAY the endpoint must
                    // not create new streams — new HEADERS are RST'd below.
                    goawayReceived = true;
                    continue;
                }
                case PING -> {
                    if (inHeaders) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    if (!frame.header().flags().contains(FrameFlag.ACK))
                        sendPingAck((PingFrame) frame);
                    continue;
                }
                case WINDOW_UPDATE -> {
                    if (inHeaders) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    if (streamId == 0) {
                        int increment = ((WindowUpdateFrame) frame).increment();
                        if (sendWindow.addAndGet(increment) > Integer.MAX_VALUE)
                            throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR);
                        unparkWindowWaiters();
                        continue;
                    }
                }
                case NOT_IMPLEMENTED -> {
                    if (inHeaders) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    continue; // RFC 7540 §5.5: ignore unknown/unimplemented frames
                }
                case DATA -> {
                    if (inHeaders) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    if (streamId == 0) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    var dataFrame = (DataFrame) frame;
                    int flowLength = dataFrame.flowLength();
                    if (flowLength > receiveWindow.get())
                        throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR);
                    receiveWindow.addAndGet(-flowLength);
                    if (receiveWindow.get() < connectionWindowSize / 10)
                        sendConnectionWindowUpdate();
                }
                case HEADERS -> {
                    if (streamId == 0) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    if (inHeaders) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    if (streamId < lastSeenStreamId) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);

                    var existing = streams.get(streamId);
                    if (existing != null) {
                        // Any HEADERS on an already-created stream is a trailer
                        // block (RFC 7540 §8.1.2.2) — the request pseudo-headers
                        // arrived with the first HEADERS. The stream is still
                        // "open" here because END_STREAM on this very frame has
                        // not been applied yet, so state cannot drive the check.
                        // Collect the block; the shared header handling decodes
                        // it to keep HPACK state in sync and discards the fields.
                        // Duplicate pseudo-headers inside are rejected by
                        // HeaderFields.validate().
                        var trailersFrame = (HeadersFrame) frame;
                        if (headerBlockSize + trailersFrame.headerBlock().length > MAX_INBOUND_HEADER_BLOCK)
                            throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                                "Header block exceeds " + MAX_INBOUND_HEADER_BLOCK + " bytes");
                        headerBlockFragments.add(trailersFrame.headerBlock());
                        headerBlockSize += trailersFrame.headerBlock().length;
                        headersEndStream =
                            trailersFrame.header().flags().contains(FrameFlag.END_STREAM);
                        if (!trailersFrame.header().flags().contains(FrameFlag.END_HEADERS)) {
                            inHeaders = true;
                            openStreamId = streamId;
                            continue;
                        }
                        // END_HEADERS set — fall through to shared handling below.
                    } else {

                    var headersFrame = (HeadersFrame) frame;
                    if (headerBlockSize + headersFrame.headerBlock().length > MAX_INBOUND_HEADER_BLOCK)
                        throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                            "Header block exceeds " + MAX_INBOUND_HEADER_BLOCK + " bytes");
                    headerBlockFragments.add(headersFrame.headerBlock());
                    headerBlockSize += headersFrame.headerBlock().length;
                    if (headersFrame.header().flags().contains(FrameFlag.END_STREAM)) {
                        headersEndStream = true;
                    }
                    if (!headersFrame.header().flags().contains(FrameFlag.END_HEADERS)) {
                        inHeaders = true;
                        openStreamId = streamId;
                        continue;
                    }
                    }
                }
                case CONTINUATION -> {
                    if (inHeaders && streamId != openStreamId)
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    if (!inHeaders) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    var continuationFrame = (ContinuationFrame) frame;
                    if (headerBlockSize + continuationFrame.headerBlock().length > MAX_INBOUND_HEADER_BLOCK)
                        throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                            "Header block exceeds " + MAX_INBOUND_HEADER_BLOCK + " bytes");
                    headerBlockFragments.add(continuationFrame.headerBlock());
                    headerBlockSize += continuationFrame.headerBlock().length;
                    if (!continuationFrame.header().flags().contains(FrameFlag.END_HEADERS))
                        continue;
                }
                case PRIORITY -> {
                    if (inHeaders) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    if (streamId == 0) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    continue;
                }
                case PUSH_PROMISE -> {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                }
                case RST_STREAM -> {
                    if (inHeaders) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    // Any RST_STREAM (even error code 0) terminates the target
                    // stream — close it so the handler and stream state are
                    // released instead of leaking.
                    if (streamId == 0) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
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
                if (goawayReceived) {
                    // RFC 7540 §6.8: no new streams after GOAWAY. Reject with
                    // REFUSED_STREAM so the peer may retry on a fresh connection.
                    lastSeenStreamId = streamId;
                    headerBlockFragments.clear();
                    headerBlockSize = 0;
                    inHeaders = false;
                    openStreamId = 0;
                    headersEndStream = false;
                    sendResetStream(Http2ErrorCode.REFUSED_STREAM, streamId);
                    continue;
                }
                // Enforce the concurrent-stream cap. Http2Stream.close()
                // removes itself from the map, so streams.size() is the number
                // of currently open streams.
                if (streams.size() >= MAX_CONCURRENT_STREAMS) {
                    lastSeenStreamId = streamId;
                    headerBlockFragments.clear();
                    headerBlockSize = 0;
                    inHeaders = false;
                    openStreamId = 0;
                    headersEndStream = false;
                    sendResetStream(Http2ErrorCode.REFUSED_STREAM, streamId);
                    continue;
                }
                byte[] headerBlock = BinUtils.combine(headerBlockFragments);
                var fields = new HeaderFields();
                for (var field : hpack.decode(headerBlock)) fields.add(field);
                fields.validate();

                Map<String, List<String>> requestHeaders = new LinkedHashMap<>(fields.size() * 2);
                for (var headerField : fields.fields()) {
                    if (headerField.value != null)
                        requestHeaders.computeIfAbsent(headerField.normalizedName, k -> new ArrayList<>(4)).add(headerField.value);
                }

                headerBlockFragments.clear();
                headerBlockSize = 0;
                inHeaders = false;
                target = new Http2Stream(streamId, this, requestHeaders, handler);
                streams.put(streamId, target);
                lastSeenStreamId = streamId;
                if (headersEndStream) {
                    target.markHalfClosed();
                    headersEndStream = false;
                }
                target.startRequest(executor);
                // header block assembled — this frame consumed, skip dispatch
                continue;
            } else if (target != null
                    && (frame.header().type() == FrameType.HEADERS
                        || frame.header().type() == FrameType.CONTINUATION)) {
                // Trailer header block (RFC 7540 §8.1.2.2) on a stream that
                // already received its request headers — open or half-closed,
                // since END_STREAM on the trailer frame applies only after
                // this frame is processed. Decode it so the HPACK dynamic
                // table stays in sync with the peer, then discard — trailers
                // carry no request semantics we expose. add() still rejects
                // pseudo-headers and connection-specific fields inside the
                // block; the REQUIRED pseudo-header check in validate() only
                // applies to request header blocks.
                byte[] headerBlock = BinUtils.combine(headerBlockFragments);
                var fields = new HeaderFields();
                for (var field : hpack.decode(headerBlock)) fields.add(field);
                headerBlockFragments.clear();
                headerBlockSize = 0;
                inHeaders = false;
                boolean trailerEndStream = headersEndStream
                    || frame.header().flags().contains(FrameFlag.END_STREAM);
                headersEndStream = false;
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
                    if (frame.header().type() == FrameType.WINDOW_UPDATE) continue;
                    throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED);
                }
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
            }

            target.dispatch(frame, executor);
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
            } else if (parameter.identifier == SettingIdentifier.SETTINGS_MAX_FRAME_SIZE) {
                peerMaxFrameSize = (int) Math.min(parameter.value, 16_777_215); // RFC max
            } else if (parameter.identifier == SettingIdentifier.SETTINGS_HEADER_TABLE_SIZE) {
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
        int increment = connectionWindowSize - current;
        receiveWindow.addAndGet(increment);
        writeFrame(new WindowUpdateFrame(0, increment).encode());
    }

    public void sendGoAway(Http2ErrorCode errorCode) throws IOException {
        // Sending GOAWAY has the same effect as receiving one (RFC 7540
        // §6.8): no new streams may be created afterwards.
        goawayReceived = true;
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
