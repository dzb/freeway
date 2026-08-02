package com.jujin.freeway.http.engine.http2;

import com.jujin.freeway.http.engine.http2.frame.DataFrame;
import com.jujin.freeway.http.engine.http2.frame.FrameFlag;
import com.jujin.freeway.http.engine.http2.frame.FrameHeader;
import com.jujin.freeway.http.engine.http2.frame.FrameType;
import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Http2FlowControlTest {

    private static final int INITIAL_WINDOW = 65535;

    @Test
    void streamRejectsDataExceedingInitialWindow() throws Exception {
        try (SocketPair pair = SocketPair.open();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Http2Connection connection = connection(pair, executor);
            Http2Stream stream = new Http2Stream(1, connection, Map.of(), (s, i, o, h) -> {});

            Http2Exception ex = assertThrows(Http2Exception.class,
                () -> stream.dispatch(data(INITIAL_WINDOW + 1), executor));
            assertEquals(Http2ErrorCode.FLOW_CONTROL_ERROR, ex.errorCode());
        }
    }

    @Test
    void streamWindowReplenishesAfterApplicationReads() throws Exception {
        try (SocketPair pair = SocketPair.open();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Http2Connection connection = connection(pair, executor);
            AtomicReference<InputStream> inputRef = new AtomicReference<>();
            Http2Stream stream = new Http2Stream(1, connection, Map.of(),
                (s, in, out, h) -> inputRef.set(in));
            stream.startRequest(executor);

            long deadline = System.currentTimeMillis() + 2_000;
            while (inputRef.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            assertNotNull(inputRef.get(), "Stream handler must capture the body input");
            InputStream in = inputRef.get();

            stream.dispatch(data(40_000), executor);
            assertEquals(40_000, readFully(in, new byte[40_000]));

            // Consuming at least half the window replenishes it.
            stream.dispatch(data(40_000), executor);

            Http2Exception ex = assertThrows(Http2Exception.class,
                () -> stream.dispatch(data(40_000), executor));
            assertEquals(Http2ErrorCode.FLOW_CONTROL_ERROR, ex.errorCode());
        }
    }

    private static Http2Connection connection(SocketPair pair, ExecutorService executor)
        throws IOException {
        return new Http2Connection(pair.client, pair.client.getInputStream(),
            pair.client.getOutputStream(), executor, (s, i, o, h) -> {});
    }

    private static DataFrame data(int length) {
        return new DataFrame(
            new FrameHeader(1, FrameType.DATA, FrameFlag.NONE, 1),
            new byte[length]);
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int off = 0;
        while (off < buffer.length) {
            int n = in.read(buffer, off, buffer.length - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        return off;
    }

    private static final class SocketPair implements AutoCloseable {
        private final ServerSocket server;
        private final Socket client;

        private SocketPair(ServerSocket server, Socket client) {
            this.server = server;
            this.client = client;
        }

        static SocketPair open() throws IOException {
            ServerSocket server = new ServerSocket(0);
            Socket client = new Socket("127.0.0.1", server.getLocalPort());
            return new SocketPair(server, client);
        }

        @Override
        public void close() throws IOException {
            client.close();
            server.close();
        }
    }
}
