package com.jujin.freeway.http.engine.http2;

import com.jujin.freeway.http.engine.http2.frame.BaseFrame;
import com.jujin.freeway.http.engine.http2.frame.DataFrame;
import com.jujin.freeway.http.engine.http2.frame.FrameFlag;
import com.jujin.freeway.http.engine.http2.frame.FrameHeader;
import com.jujin.freeway.http.engine.http2.frame.FrameType;
import com.jujin.freeway.http.engine.http2.frame.SettingIdentifier;
import com.jujin.freeway.http.engine.http2.frame.WindowUpdateFrame;
import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * HTTP/2 stream processor. Represents a single HTTP/2 request/response stream,
 * managing stream state, flow control, I/O adapters, and async request processing.
 * Response framing is delegated to {@link Http2ResponseWriter}.
 */
public final class Http2Stream {
    private static final Logger LOG = LoggerFactory.getLogger(Http2Stream.class);
    private static final FrameFlag.FlagSet END_STREAM = FrameFlag.FlagSet.of(FrameFlag.END_STREAM);

    /** stream-level send window */
    public final AtomicLong sendWindow = new AtomicLong(65535);
    private final int streamId;
    private final int initialWindowSize;
    private final Http2Connection connection;
    private final Map<String, List<String>> requestHeaders;
    private final DataIn dataIn;
    final OutputStream outputStream;
    private final Http2Connection.StreamHandler handler;
    final Map<String, List<String>> responseHeaders = new LinkedHashMap<>(16);

    private final AtomicLong receiveWindow = new AtomicLong(65535);
    private final AtomicBoolean handlingRequest = new AtomicBoolean();
    private final AtomicBoolean headersSent = new AtomicBoolean();
    private volatile Thread thread;
    private volatile boolean streamOpen = true;
    private volatile boolean halfClosed;
    private volatile boolean streamOutputClosed; // starts false

    Http2Stream(int streamId, Http2Connection connection, Map<String, List<String>> requestHeaders,
                Http2Connection.StreamHandler handler) {
        this.streamId = streamId;
        this.connection = connection;
        this.requestHeaders = requestHeaders;
        this.handler = handler;
        this.dataIn = new DataIn();

        var remoteWindow = connection.remoteSettings().get(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE);
        if (remoteWindow != null) sendWindow.set((int) remoteWindow.value);

        var localWindow = connection.localSettings().get(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE);
        initialWindowSize = localWindow != null ? (int) localWindow.value : 65535;
        if (localWindow != null) receiveWindow.set((int) localWindow.value);

        // maxFrameSize is read dynamically from connection to follow settings changes

        this.outputStream = new Http2OutputStream(streamId);
    }

    public boolean isOpen() { return streamOpen; }
    public boolean isHalfClosed() { return halfClosed; }

    public void sendReset() throws IOException {
        connection.sendResetStream(Http2ErrorCode.INTERNAL_ERROR, streamId);
    }

    void markHalfClosed() {
        halfClosed = true;
    }

    /** Wakes a handler blocked in body() so it observes EOF once the request body ends. */
    void wakeupBodyReader() {
        dataIn.wakeupReader();
    }

    public void close() {
        streamOpen = false;
        connection.streams.remove(streamId);
        try { dataIn.close(); } catch (IOException ignored) {}
        try { outputStream.close(); } catch (IOException ignored) {}
        var t = thread;
        if (t != null) t.interrupt();
    }

    public void dispatch(BaseFrame frame, ExecutorService executor) throws IOException {
        switch (frame.header().type()) {
            case HEADERS, CONTINUATION -> {
                if (halfClosed) throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED);
                // END_STREAM on HEADERS means no body, but only if END_HEADERS
                // is also set (header block complete). Otherwise CONTINUATION follows.
                if (frame.header().flags().contains(FrameFlag.END_STREAM)
                        && frame.header().flags().contains(FrameFlag.END_HEADERS)) {
                    halfClosed = true;
                }
                startRequest(executor);
            }
            case DATA -> {
                var dataFrame = (DataFrame) frame;
                if (halfClosed) throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED);
                if (!streamOpen) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
                // Enforce the stream-level receive window at frame receipt, not
                // only when the application reads, so a peer cannot overflow it.
                int flowLength = dataFrame.flowLength();
                if (flowLength > receiveWindow.get())
                    throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR);
                receiveWindow.addAndGet(-flowLength);
                if (dataFrame.body.length == 0 && flowLength > 0) {
                    // Padding-only DATA has no application read that can
                    // trigger the normal stream WINDOW_UPDATE path.
                    receiveWindow.addAndGet(flowLength);
                    connection.writeFrame(new WindowUpdateFrame(streamId, flowLength).encode());
                } else {
                    dataIn.enqueue(dataFrame.body, flowLength);
                }
                if (dataFrame.header().flags().contains(FrameFlag.END_STREAM)) {
                    halfClosed = true;
                    dataIn.wakeupReader();
                }
            }
            case RST_STREAM -> { halfClosed = true; close(); }
            case WINDOW_UPDATE -> {
                int increment = ((WindowUpdateFrame) frame).increment();
                if (sendWindow.addAndGet(increment) > Integer.MAX_VALUE) {
                    connection.sendResetStream(Http2ErrorCode.FLOW_CONTROL_ERROR, streamId);
                    close();
                }
                // Writers blocked on this stream's window re-check after wake.
                connection.unparkWindowWaiters();
            }
            default -> {}
        }
    }

    public void writeResponseHeaders(boolean closeStream) throws IOException {
        if (!headersSent.compareAndSet(false, true)) return;
        // The connection may have been closed (peer disconnect) while this
        // stream's handler was still running — writing into the dead
        // connection would throw from an already-closed stream. Surface a
        // clean IOException so the handler unwinds normally.
        if (connection.isClosed()) throw new IOException("connection closed");
        connection.lock();
        try {
            byte[] frame = connection.hpack().encodeResponseHeaders(
                responseHeaders, streamId, closeStream);
            connection.writeFrame(frame);
            if (closeStream) streamOutputClosed = true;
        } finally {
            connection.unlock();
        }
    }

    void startRequest(ExecutorService executor) throws IOException {
        if (!handlingRequest.compareAndSet(false, true))
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        InputStream input = halfClosed ? InputStream.nullInputStream() : dataIn;
        executor.execute(() -> {
            thread = Thread.currentThread();
            try {
                handler.handle(this, input, outputStream, requestHeaders);
            } catch (IOException ex) {
                LOG.debug("H2 stream IO error", ex);
                close();
            }
        });
    }

    /** OutputStream adapter that writes HTTP/2 DATA frames with flow control and frame splitting. */
    private class Http2OutputStream extends OutputStream {
        private final int streamId;
        private boolean closed;

        Http2OutputStream(int streamId) { this.streamId = streamId; }

        @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}); }
        @Override public void write(byte[] data) throws IOException { write(data, 0, data.length); }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            waitForSendWindow();
            if (connection.isClosed()) throw new IOException("connection closed");
            writeResponseHeaders(false);
            if (streamOutputClosed) throw new IOException("output closed");

            while (length > 0) {
                int chunkSize = (int) Math.min(Math.min(length, connection.peerMaxFrameSize),
                    Math.min(connection.sendWindow.get(), sendWindow.get()));
                if (chunkSize <= 0) {
                    waitForSendWindow();
                    if (connection.isClosed()) throw new IOException("closed");
                    continue;
                }
                if (connection.sendWindow.addAndGet(-chunkSize) < 0) {
                    connection.sendWindow.addAndGet(chunkSize);
                    continue;
                }
                connection.writeDataFrame(
                    FrameHeader.encode(chunkSize, FrameType.DATA, FrameFlag.NONE, streamId),
                    data, offset, chunkSize);
                offset += chunkSize;
                length -= chunkSize;
                sendWindow.addAndGet(-chunkSize);
            }
        }

        /**
         * Blocks until BOTH the stream and connection send windows have
         * capacity (or the connection closes). Event-driven: parks instead of
         * polling, unparked by WINDOW_UPDATE (connection-level updates wake
         * windowWaiters too) or by connection close. Checking the connection
         * window here prevents a busy spin when other streams have consumed
         * the shared connection window while this stream's own window is open.
         */
        private void waitForSendWindow() {
            while ((sendWindow.get() <= 0 || connection.sendWindow.get() <= 0)
                    && !connection.isClosed()) {
                connection.windowWaiters.add(Thread.currentThread());
                try {
                    if ((sendWindow.get() > 0 && connection.sendWindow.get() > 0)
                            || connection.isClosed()) return;
                    LockSupport.park();
                } finally {
                    connection.windowWaiters.remove(Thread.currentThread());
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            try {
                if (connection.isClosed()) { headersSent.compareAndSet(false, true); return; }
                writeResponseHeaders(false);
                if (!streamOutputClosed) {
                    connection.writeFrame(
                        FrameHeader.encode(0, FrameType.DATA, END_STREAM, streamId));
                }
                dataIn.close();
            } finally {
                closed = true;
                Http2Stream.this.close();
            }
        }
    }

    /** InputStream adapter that reads request body from DATA frames using park/unpark for blocking. */
    private class DataIn extends InputStream {
        private final ConcurrentLinkedQueue<InboundData> queue = new ConcurrentLinkedQueue<>();
        private volatile Thread reader;
        private int offset;
        private long readSinceWindowUpdate;

        void enqueue(byte[] data, int flowLength) {
            queue.add(new InboundData(data, flowLength));
            LockSupport.unpark(reader);
        }
        void wakeupReader() { LockSupport.unpark(reader); }

        @Override
        public void close() throws IOException {
            if (Thread.currentThread() == reader || reader == null) {
                byte[] drainBuffer = new byte[2048];
                while (read(drainBuffer, 0, drainBuffer.length) != -1) {}
            } else {
                LockSupport.unpark(reader);
            }
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) == -1 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int bytesRead = 0;
            try {
                reader = Thread.currentThread();
                while (len > 0) {
                    InboundData inbound;
                    while ((inbound = queue.peek()) == null) {
                        if (bytesRead > 0) return bytesRead;
                        if (halfClosed) return -1;
                        LockSupport.park();
                        if (Thread.interrupted()) throw new IOException("interrupted");
                    }
                    byte[] data = inbound.data();
                    if (data.length == 0) {
                        queue.poll();
                        readSinceWindowUpdate += inbound.flowLength();
                        continue;
                    }
                    int available = data.length - offset;
                    int toRead = Math.min(len, available);
                    System.arraycopy(data, offset, buffer, off, toRead);
                    offset += toRead;
                    off += toRead;
                    len -= toRead;
                    bytesRead += toRead;
                    if (offset == data.length) {
                        queue.poll();
                        offset = 0;
                        // Padding consumes flow-control credit too; body bytes
                        // are accounted for in the finally block below.
                        readSinceWindowUpdate += inbound.flowLength() - data.length;
                    }
                }
                return bytesRead;
            } finally {
                if (bytesRead > 0) {
                    readSinceWindowUpdate += bytesRead;
                    if (readSinceWindowUpdate >= initialWindowSize / 2) {
                        int increment = (int) readSinceWindowUpdate;
                        readSinceWindowUpdate = 0;
                        receiveWindow.addAndGet(increment);
                        connection.writeFrame(
                            new WindowUpdateFrame(streamId, increment).encode());
                    }
                }
            }
        }

        private record InboundData(byte[] data, int flowLength) {}
    }
}
