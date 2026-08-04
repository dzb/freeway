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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    public final AtomicLong sendWindow = new AtomicLong(DEFAULT_WINDOW_SIZE);
    public final AtomicInteger receiveWindow = new AtomicInteger(DEFAULT_WINDOW_SIZE);
    public final AtomicInteger requestsInProgress = new AtomicInteger();

    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final Socket socket;
    private final ExecutorService executor;
    private final StreamHandler handler;
    private final HPackContext hpack = new HPackContext();
    final ConcurrentHashMap<Integer, Http2Stream> streams = new ConcurrentHashMap<>();
    volatile int maxFrameSize = 16384;
    private final SettingsMap remoteSettings = new SettingsMap();
    private final SettingsMap localSettings = new SettingsMap();

    private final int connectionWindowSize = DEFAULT_WINDOW_SIZE;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();

    private int lastSeenStreamId;

    public Http2Connection(Socket socket, InputStream inputStream, OutputStream outputStream,
                           ExecutorService executor, StreamHandler handler) {
        this.socket = socket;
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.executor = executor;
        this.handler = handler;
        localSettings.set(new SettingParameter(SettingIdentifier.SETTINGS_MAX_FRAME_SIZE, DEFAULT_MAX_FRAME_SIZE));
        localSettings.set(new SettingParameter(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, DEFAULT_WINDOW_SIZE));
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

    void writeFrame(List<byte[]> partials) throws IOException {
        lock.lock();
        try {
            for (var frame : partials) outputStream.write(frame);
            outputStream.flush();
        } finally {
            lock.unlock();
        }
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
        boolean inHeaders = false;
        boolean headersEndStream = false;
        int openStreamId = 0;
        var headerBlockFragments = new ArrayList<byte[]>();

        while (!closed.get()) {
            var frame = FrameSerializer.deserialize(inputStream, maxFrameSize);
            int streamId = frame.header().streamId();

            if (streamId != 0 && streamId % 2 == 0)
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);

            switch (frame.header().type()) {
                case SETTINGS -> {
                    if (frame.header().flags().contains(FrameFlag.ACK)) {
                        continue;
                    }
                    updateRemoteSettings((SettingsFrame) frame);
                    sendSettingsAck();
                    continue;
                }
                case GOAWAY -> {
                    var goaway = (GoawayFrame) frame;
                    if (goaway.errorCode != Http2ErrorCode.NO_ERROR)
                        throw new IOException("GOAWAY");
                    continue;
                }
                case PING -> {
                    if (!frame.header().flags().contains(FrameFlag.ACK))
                        sendPingAck((PingFrame) frame);
                    continue;
                }
                case WINDOW_UPDATE -> {
                    if (streamId == 0) {
                        int increment = ((WindowUpdateFrame) frame).increment();
                        if (sendWindow.addAndGet(increment) > Integer.MAX_VALUE)
                            throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR);
                        continue;
                    }
                }
                case NOT_IMPLEMENTED -> {
                    if (inHeaders) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    continue; // RFC 7540 §5.5: ignore unknown/unimplemented frames
                }
                case DATA -> {
                    if (streamId == 0) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    var dataFrame = (DataFrame) frame;
                    if (dataFrame.body.length > receiveWindow.get())
                        throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR);
                    receiveWindow.addAndGet(-dataFrame.body.length);
                    if (receiveWindow.get() < connectionWindowSize / 10)
                        sendConnectionWindowUpdate();
                    if (inHeaders) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                }
                case HEADERS -> {
                    if (streamId == 0) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    if (inHeaders) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    if (streamId < lastSeenStreamId) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);

                    var existing = streams.get(streamId);
                    if (existing != null) {
                        if (!existing.isOpen() || existing.isHalfClosed())
                            throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED);
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    }

                    var headersFrame = (HeadersFrame) frame;
                    headerBlockFragments.add(headersFrame.headerBlock());
                    if (headersFrame.header().flags().contains(FrameFlag.END_STREAM)) {
                        headersEndStream = true;
                    }
                    if (!headersFrame.header().flags().contains(FrameFlag.END_HEADERS)) {
                        inHeaders = true;
                        openStreamId = streamId;
                        continue;
                    }
                }
                case CONTINUATION -> {
                    if (inHeaders && streamId != openStreamId)
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    if (!inHeaders) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    var continuationFrame = (ContinuationFrame) frame;
                    headerBlockFragments.add(continuationFrame.headerBlock());
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
                    var resetFrame = (ResetStreamFrame) frame;
                    if (resetFrame.errorCode == Http2ErrorCode.NO_ERROR) continue;
                    if (streamId == 0) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                    if (streams.get(streamId) == null && streamId > lastSeenStreamId)
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                }
            }

            var target = streams.get(streamId);
            if (target == null && lastSeenStreamId < streamId) {
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
                maxFrameSize = (int) Math.min(parameter.value, 16_777_215); // RFC max
            }
            remoteSettings.set(parameter);
        }
    }

    public void sendMySettings() throws IOException {
        var sf = new SettingsFrame(new FrameHeader(0, FrameType.SETTINGS, FrameFlag.NONE, 0));
        localSettings.forEach(sf.params::add);
        writeFrame(List.of(sf.encode()));
    }

    private void sendSettingsAck() throws IOException {
        writeFrame(List.of(FrameHeader.encode(0, FrameType.SETTINGS,
                FrameFlag.FlagSet.of(FrameFlag.ACK), 0)));
    }

    private void sendConnectionWindowUpdate() throws IOException {
        int current = receiveWindow.get();
        int increment = connectionWindowSize - current;
        receiveWindow.addAndGet(increment);
        writeFrame(List.of(new WindowUpdateFrame(0, increment).encode()));
    }

    public void sendGoAway(Http2ErrorCode errorCode) throws IOException {
        lock.lock();
        try {
            new GoawayFrame(errorCode, lastSeenStreamId).writeTo(outputStream);
            outputStream.flush();
        } finally {
            lock.unlock();
        }
    }

    public void sendResetStream(Http2ErrorCode errorCode, int streamId) throws IOException {
        writeFrame(List.of(new ResetStreamFrame(errorCode, streamId).encode()));
    }

    private void sendPingAck(PingFrame pingFrame) throws IOException {
        writeFrame(List.of(new PingFrame(pingFrame).encode()));
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
