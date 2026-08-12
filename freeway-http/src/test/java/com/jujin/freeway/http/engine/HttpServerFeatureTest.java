package com.jujin.freeway.http.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.WebServerBuilder;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.http.staticfile.StaticResourceMount;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpServerFeatureTest {

    @Test
    void filtersRunInAscendingOrderValue() throws Exception {
        var execution = new ArrayList<String>();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .filter(new HttpFilter() {
                @Override
                public void doFilter(HttpContext ctx, RouteHandler next)
                        throws Exception {
                    execution.add("late");
                    next.handle(ctx);
                }
            })
            .filter(new HttpFilter() {
                @Override
                public int order() {
                    return -200;
                }

                @Override
                public void doFilter(HttpContext ctx, RouteHandler next)
                        throws Exception {
                    execution.add("early");
                    next.handle(ctx);
                }
            })
            .filter(new HttpFilter() {
                @Override
                public void doFilter(HttpContext ctx, RouteHandler next)
                        throws Exception {
                    execution.add("default");
                    next.handle(ctx);
                }
            })
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try {
            var resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + server.port() + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            assertEquals(List.of("early", "late", "default"), execution,
                "filters must run in ascending order value, stable within "
                    + "the same order");
        } finally {
            server.stop();
        }
    }

    @Test
    void gzipCompressesCompressibleBodiesWhenAccepted() throws Exception {
        int port = freePort();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .route(Route.get("/text", ctx -> {
                ctx.setHeader("Content-Type", "text/plain");
                ctx.send(200, "A".repeat(1000));
            }))
            .route(Route.get("/small", ctx -> {
                ctx.setHeader("Content-Type", "text/plain");
                ctx.send(200, "tiny");
            }))
            .build();
        server.start();
        try {
            RawResponse compressed = request(port, "/text", "Accept-Encoding: gzip\r\n");
            assertTrue(compressed.headers.contains("Content-Encoding: gzip"),
                "compressible response must be gzipped when accepted: "
                    + compressed.headers);
            assertTrue(compressed.headers.contains("Vary: Accept-Encoding"),
                "gzip responses must advertise Vary: Accept-Encoding");
            assertEquals("A".repeat(1000), gunzip(compressed.body),
                "gzip body must decompress to the original payload");

            RawResponse plain = request(port, "/text", "");
            assertFalse(plain.headers.contains("Content-Encoding"),
                "no Accept-Encoding must yield an uncompressed response");
            assertEquals("A".repeat(1000), new String(plain.body,
                StandardCharsets.UTF_8));

            RawResponse small = request(port, "/small", "Accept-Encoding: gzip\r\n");
            assertFalse(small.headers.contains("Content-Encoding"),
                "bodies below the compression minimum size must not be gzipped");

            RawResponse qzero = request(port, "/text",
                "Accept-Encoding: gzip;q=0\r\n");
            assertFalse(qzero.headers.contains("Content-Encoding"),
                "Accept-Encoding: gzip;q=0 must disable compression");
        } finally {
            server.stop();
        }
    }

    @Test
    void unknownLengthStreamingUsesChunkedEncoding() throws Exception {
        int port = freePort();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .route(Route.get("/stream", ctx ->
                ctx.output(new ByteArrayInputStream(
                    "hello-chunked-world".getBytes(StandardCharsets.UTF_8)), -1)))
            .build();
        server.start();
        try {
            RawResponse resp = request(port, "/stream", "");
            assertTrue(resp.headers.contains("Transfer-Encoding: chunked"),
                "unknown-length streaming must use chunked framing: "
                    + resp.headers);
            assertFalse(resp.headers.contains("Content-Length"),
                "chunked responses must not carry Content-Length");
            assertEquals("hello-chunked-world", new String(resp.body,
                StandardCharsets.UTF_8));
        } finally {
            server.stop();
        }
    }

    @Test
    void emptyUnknownLengthStreamTerminatesChunkedBody() throws Exception {
        int port = freePort();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .route(Route.get("/empty", ctx ->
                ctx.output(new ByteArrayInputStream(new byte[0]), -1)))
            .build();
        server.start();
        try {
            RawResponse resp = request(port, "/empty", "");
            assertTrue(resp.headers.contains("Transfer-Encoding: chunked"),
                "unknown-length streaming must use chunked framing: "
                    + resp.headers);
            assertEquals(0, resp.body.length,
                "an empty chunked stream must send the terminal chunk, not hang");
        } finally {
            server.stop();
        }
    }

    @Test
    void gzipStreamingFallsBackToChunkedFraming() throws Exception {
        int port = freePort();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .route(Route.get("/gz", ctx -> {
                ctx.setHeader("Content-Type", "text/plain");
                ctx.output(new ByteArrayInputStream(
                    "compress-me-please".getBytes(StandardCharsets.UTF_8)), 18);
            }))
            .build();
        server.start();
        try {
            RawResponse resp = request(port, "/gz", "Accept-Encoding: gzip\r\n");
            assertTrue(resp.headers.contains("Content-Encoding: gzip"),
                "streaming text responses must be gzipped when accepted");
            assertTrue(resp.headers.contains("Transfer-Encoding: chunked"),
                "gzip streaming must use chunked framing (length unknown): "
                    + resp.headers);
            assertFalse(resp.headers.contains("Content-Length"),
                "gzip streaming must not advertise the uncompressed length");
            assertEquals("compress-me-please", gunzip(resp.body));
        } finally {
            server.stop();
        }
    }

    @Test
    void accessLogWritesOneLinePerRequest() throws Exception {
        ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
        PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
        int port = freePort();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .accessLog(log)
            .route(Route.get("/ok", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
            var resp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/ok"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());

            long deadline = System.currentTimeMillis() + 3000;
            while (!logBytes.toString(StandardCharsets.UTF_8).contains("GET /ok 200")
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            String line = logBytes.toString(StandardCharsets.UTF_8);
            assertTrue(line.contains("GET /ok 200"),
                "access log must contain one request line: " + line);
        } finally {
            server.stop();
        }
    }

    @Test
    void largeStaticFilesUseSendfileFastPath(@TempDir Path tempDir) throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("files"));
        byte[] content = new byte[200 * 1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        Files.write(root.resolve("big.bin"), content);
        Files.write(root.resolve("small.bin"), Arrays.copyOf(content, 1024));

        var metrics = new HttpServerOperationalTest.TestMetrics();
        int port = freePort();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .metrics(metrics)
            .staticFile(StaticResourceMount.directory("/files", root))
            .build();
        server.start();
        try {
            RawResponse full = request(port, "/files/big.bin", "");
            assertEquals(200, status(full.headers));
            assertArrayEquals(content, full.body);
            assertTrue(metrics.counterValue("freeway.http.sendfile.transfers") >= 1,
                "large plain-HTTP file responses must use the sendfile path");

            RawResponse range = request(port, "/files/big.bin",
                "Range: bytes=0-131071\r\n");
            assertEquals(206, status(range.headers));
            assertEquals("bytes 0-131071/" + content.length,
                headerValue(range.headers, "content-range"));
            assertArrayEquals(Arrays.copyOfRange(content, 0, 128 * 1024), range.body);
            assertTrue(metrics.counterValue("freeway.http.sendfile.transfers") >= 2,
                "range responses must also use the sendfile path");

            long before = metrics.counterValue("freeway.http.sendfile.transfers");
            RawResponse small = request(port, "/files/small.bin", "");
            assertEquals(200, status(small.headers));
            assertArrayEquals(Arrays.copyOf(content, 1024), small.body);
            assertEquals(before, metrics.counterValue("freeway.http.sendfile.transfers"),
                "small files must stay on the buffered streaming path");
        } finally {
            server.stop();
        }
    }

    // ── raw HTTP/1.1 helpers ───────────────────────────────────

    private record RawResponse(String headers, byte[] body) {}

    private static RawResponse request(int port, String path, String extraHeaders)
            throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\n"
                    + "Host: x\r\n"
                    + extraHeaders
                    + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            String headers = readUntil(in, "\r\n\r\n");
            int contentLength = contentLength(headers);
            if (headers.contains("Transfer-Encoding: chunked")) {
                return new RawResponse(headers, readChunkedBody(in));
            }
            byte[] body = new byte[contentLength];
            int off = 0;
            while (off < body.length) {
                int n = in.read(body, off, body.length - off);
                if (n < 0) break;
                off += n;
            }
            return new RawResponse(headers, body);
        }
    }

    private static int contentLength(String headers) {
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                return Integer.parseInt(line.substring(15).trim());
            }
        }
        return 0;
    }

    private static byte[] readChunkedBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readUntil(in, "\r\n");
            int size = Integer.parseInt(sizeLine.trim(), 16);
            if (size == 0) {
                readUntil(in, "\r\n"); // trailing CRLF after the last chunk
                break;
            }
            byte[] chunk = new byte[size];
            int off = 0;
            while (off < size) {
                int n = in.read(chunk, off, size - off);
                if (n < 0) throw new IOException("EOF in chunked body");
                off += n;
            }
            out.write(chunk);
            readUntil(in, "\r\n");
        }
        return out.toByteArray();
    }

    private static String readUntil(InputStream in, String marker) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (sb.indexOf(marker) < 0) {
            int c = in.read();
            if (c < 0) break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    private static int status(String headers) {
        int firstSpace = headers.indexOf(' ');
        int secondSpace = headers.indexOf(' ', firstSpace + 1);
        return Integer.parseInt(headers.substring(firstSpace + 1, secondSpace));
    }

    private static String headerValue(String headers, String name) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static String gunzip(byte[] data) throws IOException {
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }
}
