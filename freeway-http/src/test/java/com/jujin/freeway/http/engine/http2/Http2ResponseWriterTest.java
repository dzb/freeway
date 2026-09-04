package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.commons.coercion.CoercerImpl;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.engine.HttpContextImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Http2ResponseWriterTest {

    @Test
    void writeHeadFiltersConnectionSpecificHeaders() throws Exception {
        try (SocketPair pair = SocketPair.open();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Http2Connection connection = connection(pair, executor);
            Http2Stream stream = new Http2Stream(1, connection, Map.of(),
                (s, in, out, h) -> {});
            // Real requests are already half-closed when the handler runs
            // (END_STREAM on the request HEADERS); closing the output stream
            // then drains an empty request body instead of blocking forever.
            stream.markHalfClosed();
            HttpContextImpl ctx = new HttpContextImpl(
                new JsonCodecDefault(), new CoercerImpl());
            ctx.setHeader("Content-Type", "text/plain");
            ctx.setHeader("Connection", "close");
            ctx.setHeader("Keep-Alive", "timeout=5");
            ctx.setHeader("Transfer-Encoding", "chunked");
            ctx.setHeader("X-Custom", "ok");

            new Http2ResponseWriter(stream).writeHead(ctx);

            assertFalse(stream.responseHeaders.containsKey("connection"));
            assertFalse(stream.responseHeaders.containsKey("keep-alive"));
            assertFalse(stream.responseHeaders.containsKey("transfer-encoding"));
            assertTrue(stream.responseHeaders.containsKey("content-type"));
            assertTrue(stream.responseHeaders.containsKey("x-custom"));
        }
    }

    @Test
    void openSseWritesStandardHeadersAndDropsConnection() throws Exception {
        try (SocketPair pair = SocketPair.open();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Http2Connection connection = connection(pair, executor);
            Http2Stream stream = new Http2Stream(1, connection, Map.of(),
                (s, in, out, h) -> {});
            stream.markHalfClosed();
            HttpContextImpl ctx = new HttpContextImpl(
                new JsonCodecDefault(), new CoercerImpl());
            ctx.setHeader("Content-Type", "text/event-stream; charset=utf-8");
            ctx.setHeader("Cache-Control", "no-cache");
            ctx.setHeader("Connection", "keep-alive");

            var emitter = new Http2ResponseWriter(stream).openSse(ctx);
            emitter.close();

            assertTrue(stream.responseHeaders.containsKey("content-type"));
            assertTrue(stream.responseHeaders.containsKey("cache-control"));
            assertFalse(stream.responseHeaders.containsKey("connection"));
        }
    }

    @Test
    void writeHeadPreservesMultipleSetCookieValues() throws Exception {
        try (SocketPair pair = SocketPair.open();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Http2Connection connection = connection(pair, executor);
            Http2Stream stream = new Http2Stream(1, connection, Map.of(),
                (s, in, out, h) -> {});
            stream.markHalfClosed();
            HttpContextImpl ctx = new HttpContextImpl(
                new JsonCodecDefault(), new CoercerImpl());
            ctx.addHeader("Set-Cookie", "a=1");
            ctx.addHeader("Set-Cookie", "b=2");

            new Http2ResponseWriter(stream).writeHead(ctx);

            assertEquals(List.of("a=1", "b=2"),
                stream.responseHeaders.get("set-cookie"));
        }
    }

    private static Http2Connection connection(SocketPair pair, ExecutorService executor)
        throws IOException {
        return new Http2Connection(pair.client, pair.client.getInputStream(),
            pair.client.getOutputStream(), executor, (s, i, o, h) -> {}, 0);
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
