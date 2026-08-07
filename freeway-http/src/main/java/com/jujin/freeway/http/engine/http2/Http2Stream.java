package com.jujin.freeway.http.engine.http2;

import com.jujin.freeway.http.engine.Http2ResponseBridge;
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
 */
public final class Http2Stream implements Http2ResponseBridge {
    private static final Logger LOG = LoggerFactory.getLogger(Http2Stream.class);
    private static final FrameFlag.FlagSet END_STREAM = FrameFlag.FlagSet.of(FrameFlag.END_STREAM);

    /** stream-level send window */
    public final AtomicLong sendWindow = new AtomicLong(65535);
    private final int streamId;
    private final int initialWindowSize;
    private final Http2Connection connection;
    private final Map<String, List<String>> requestHeaders;
    private final DataIn dataIn;
    private final OutputStream outputStream;
    private final Http2Connection.StreamHandler handler;
    private final Map<String, List<String>> responseHeaders = new LinkedHashMap<>(16);

    @Override
    public Map<String, List<String>> headers() { return responseHeaders; }
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
                if (dataFrame.body.length > receiveWindow.get())
                    throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR);
                receiveWindow.addAndGet(-dataFrame.body.length);
                dataIn.enqueue(dataFrame.body);
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
        connection.lock();
        try {
            connection.hpack().writeResponseHeaders(responseHeaders, connection.outputStream(), streamId, closeStream);
            if (closeStream) streamOutputClosed = true;
        } finally {
            connection.unlock();
        }
    }

    void startRequest(ExecutorService executor) throws IOException {
        if (!handlingRequest.compareAndSet(false, true))
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        connection.requestsInProgress.incrementAndGet();
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
            writeResponseHeaders(false);
            if (streamOutputClosed) throw new IOException("output closed");

            while (length > 0) {
                int chunkSize = (int) Math.min(Math.min(length, connection.maxFrameSize),
                    Math.min(connection.sendWindow.get(), sendWindow.get()));
                if (chunkSize <= 0) {
                    connection.lock();
                    try { connection.outputStream().flush(); } finally { connection.unlock(); }
                    waitForSendWindow();
                    if (connection.isClosed()) throw new IOException("closed");
                    continue;
                }
                if (connection.sendWindow.addAndGet(-chunkSize) < 0) {
                    connection.sendWindow.addAndGet(chunkSize);
                    continue;
                }
                connection.lock();
                try {
                    FrameHeader.writeTo(connection.outputStream(), chunkSize, FrameType.DATA, FrameFlag.NONE, streamId);
                    connection.outputStream().write(data, offset, chunkSize);
                } finally { connection.unlock(); }
                offset += chunkSize;
                length -= chunkSize;
                sendWindow.addAndGet(-chunkSize);
            }
        }

        /**
         * Blocks until the connection-level send window has capacity (or the
         * connection closes). Event-driven: parks instead of polling, and is
         * unparked by WINDOW_UPDATE frames or by connection close.
         */
        private void waitForSendWindow() {
            while (sendWindow.get() <= 0 && !connection.isClosed()) {
                connection.windowWaiters.add(Thread.currentThread());
                try {
                    if (sendWindow.get() > 0 || connection.isClosed()) return;
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
                connection.lock();
                try {
                    if (!streamOutputClosed)
                        FrameHeader.writeTo(connection.outputStream(), 0, FrameType.DATA, END_STREAM, streamId);
                    if (connection.requestsInProgress.decrementAndGet() == 0)
                        connection.outputStream().flush();
                } finally { connection.unlock(); }
                dataIn.close();
            } finally {
                closed = true;
                Http2Stream.this.close();
            }
        }
    }

    /** InputStream adapter that reads request body from DATA frames using park/unpark for blocking. */
    private class DataIn extends InputStream {
        private final ConcurrentLinkedQueue<byte[]> queue = new ConcurrentLinkedQueue<>();
        private volatile Thread reader;
        private int offset;
        private long readSinceWindowUpdate;

        void enqueue(byte[] data) { queue.add(data); LockSupport.unpark(reader); }
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
                    byte[] data;
                    while ((data = queue.peek()) == null) {
                        if (bytesRead > 0) return bytesRead;
                        if (halfClosed) return -1;
                        LockSupport.park();
                        if (Thread.interrupted()) throw new IOException("interrupted");
                    }
                    int available = data.length - offset;
                    int toRead = Math.min(len, available);
                    System.arraycopy(data, offset, buffer, off, toRead);
                    offset += toRead;
                    off += toRead;
                    len -= toRead;
                    bytesRead += toRead;
                    if (offset == data.length) { queue.poll(); offset = 0; }
                }
                return bytesRead;
            } finally {
                if (bytesRead > 0) {
                    readSinceWindowUpdate += bytesRead;
                    if (readSinceWindowUpdate >= initialWindowSize / 2) {
                        int increment = (int) readSinceWindowUpdate;
                        readSinceWindowUpdate = 0;
                        receiveWindow.addAndGet(increment);
                        connection.lock();
                        try {
                            new WindowUpdateFrame(streamId, increment).writeTo(connection.outputStream());
                        } finally { connection.unlock(); }
                    }
                }
            }
        }
    }
}
