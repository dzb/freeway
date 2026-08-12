package com.jujin.freeway.http.engine;

import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.WebServerBuilder;
import com.jujin.freeway.http.filter.AccessLogFilter;
import com.jujin.freeway.http.route.Route;
import jdk.net.ExtendedSocketOptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HttpServerOperationalTest {

    @Test
    void keepAliveProbeTuningApplied() throws Exception {
        // The server must tune TCP keepalive probes on every accepted socket
        // so dead peers holding open streams are reclaimed quickly. Verify
        // the exact routine HttpSession applies on accept.
        assumeTrue(new Socket().supportedOptions()
                .contains(ExtendedSocketOptions.TCP_KEEPCOUNT),
            "platform must support TCP keepalive probe tuning");
        try (ServerSocket peer = new ServerSocket(0);
             Socket s = new Socket("127.0.0.1", peer.getLocalPort())) {
            peer.accept().close();
            s.setKeepAlive(true);
            HttpSession.configureKeepAliveProbe(s);
            assertEquals(30, s.getOption(ExtendedSocketOptions.TCP_KEEPIDLE));
            assertEquals(15, s.getOption(ExtendedSocketOptions.TCP_KEEPINTERVAL));
            assertEquals(3, s.getOption(ExtendedSocketOptions.TCP_KEEPCOUNT));
        }
    }

    @Test
    void readTimeoutClosesIdleConnection() throws Exception {
        int port = freePort();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, 1024,
                Duration.ofSeconds(2), 1024,
                Duration.ofMillis(500), 0))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            assertEquals(-1, socket.getInputStream().read(),
                "an idle connection must be closed by the server after the read timeout");
        } finally {
            server.stop();
        }
    }

    @Test
    void maxConnectionsRejectsExcessConnections() throws Exception {
        int port = freePort();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, 1024,
                Duration.ofSeconds(2), 1024,
                Duration.ofMinutes(2), 2))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try (Socket first = new Socket("127.0.0.1", port);
             Socket second = new Socket("127.0.0.1", port)) {
            Thread.sleep(200); // let both sessions register
            try (Socket third = new Socket("127.0.0.1", port)) {
                third.setSoTimeout(3000);
                assertEquals(-1, third.getInputStream().read(),
                    "the connection beyond maxConnections must be rejected immediately");
            }
            assertTrue(first.isConnected() && second.isConnected());
        } finally {
            server.stop();
        }
    }

    @Test
    void expect100ContinueGetsInterimResponse() throws Exception {
        int port = freePort();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .route(Route.post("/echo", ctx -> ctx.send(200, ctx.bodyText())))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /echo HTTP/1.1\r\n"
                    + "Host: x\r\n"
                    + "Content-Length: 4\r\n"
                    + "Expect: 100-continue\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String interim = readUntil(socket.getInputStream(), "100 Continue");
            assertTrue(interim.contains("100 Continue"),
                "Expect: 100-continue must be acknowledged before the body: " + interim);

            out.write("data".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String response = readUntil(socket.getInputStream(), "data");
            assertTrue(response.contains("200"), "final response must be 200: " + response);
            assertTrue(response.contains("data"), "body must be echoed: " + response);
        } finally {
            server.stop();
        }
    }

    @Test
    void metricsRecordConnectionsRequestsAndStatus() throws Exception {
        TestMetrics metrics = new TestMetrics();
        int port = freePort();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .metrics(metrics)
            .route(Route.get("/ok", ctx -> ctx.send(200, "ok")))
            .route(Route.get("/boom", ctx -> {
                throw new RuntimeException("boom");
            }))
            .build();
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
            var ok = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/ok"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());

            var missing = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/missing"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());

            var boom = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/boom"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(500, boom.statusCode());

            assertTrue(metrics.counterValue("freeway.http.requests.total") >= 3,
                "requests.total must count every request");
            assertEquals(1, metrics.counterValue("freeway.http.responses.4xx"));
            assertEquals(1, metrics.counterValue("freeway.http.responses.5xx"));
            assertTrue(metrics.counterValue("freeway.http.connections.total") >= 1,
                "connections.total must count accepted connections");
            assertNotNull(metrics.gauges.get("freeway.http.connections.active"),
                "connections.active gauge must be registered");
            assertNotNull(metrics.gauges.get("freeway.http.requests.active"),
                "requests.active gauge must be registered");
            assertTrue(metrics.timerCount("freeway.http.requests.duration") >= 3,
                "requests.duration timer must record every request");
            assertTrue(metrics.timerNanos("freeway.http.requests.duration") > 0,
                "requests.duration timer must accumulate handler time");
        } finally {
            server.stop();
        }
        // Sessions unregister their connection asynchronously after stop();
        // poll until the gauge drains (with a timeout so a real leak fails).
        long deadline = System.currentTimeMillis() + 2000;
        int active = metrics.gauges.get("freeway.http.connections.active").get().intValue();
        while (active > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            active = metrics.gauges.get("freeway.http.connections.active").get().intValue();
        }
        assertEquals(0, active,
            "connections.active gauge must drop to zero after stop");
    }

    @Test
    void http11RequiresSingleHostHeader() throws Exception {
        int port = freePort();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(
                    "GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertTrue(readStatusLine(socket).contains("400"),
                    "HTTP/1.1 without Host must be rejected with 400");
            }
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(
                    ("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertTrue(readStatusLine(socket).contains("400"),
                    "duplicate Host headers must be rejected with 400");
            }
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertTrue(readStatusLine(socket).contains("200"),
                    "HTTP/1.1 with a single Host must succeed");
            }
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(
                    "GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertTrue(readStatusLine(socket).contains("200"),
                    "HTTP/1.0 without Host must still be accepted");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void malformedRequestLineGets400() throws Exception {
        int port = freePort();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(
                "GARBAGE NOT HTTP\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertTrue(readStatusLine(socket).contains("400"),
                "a malformed request line must be answered 400, not dropped");
        } finally {
            server.stop();
        }
    }

    @Test
    void unknownExpectValueGets417() throws Exception {
        int port = freePort();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(
                ("GET / HTTP/1.1\r\nHost: localhost\r\n"
                    + "Expect: something-else\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertTrue(readStatusLine(socket).contains("417"),
                "an unrecognized Expect value must be answered 417 (RFC 7231 §5.1.1)");
        } finally {
            server.stop();
        }
    }

    @Test
    void headerWithoutColonGets400() throws Exception {
        int port = freePort();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(
                ("GET / HTTP/1.1\r\nHost: localhost\r\n"
                    + "NoColonHere\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertTrue(readStatusLine(socket).contains("400"),
                "a header line without a colon is malformed (RFC 7230 §3.2)");
        } finally {
            server.stop();
        }
    }

    @Test
    void accessLogIncludesClientIpAndUserAgent() throws Exception {
        int port = freePort();
        var log = new ByteArrayOutputStream();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, 1024,
                Duration.ofSeconds(2), 1024,
                Duration.ofSeconds(2), 0))
            .filter(new AccessLogFilter(new PrintStream(log, true)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try {
            var client = HttpClient.newBuilder().build();
            client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/"))
                .header("User-Agent", "freeway-accesslog-test")
                .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        } finally {
            server.stop();
        }
        String line = log.toString(StandardCharsets.UTF_8);
        assertTrue(line.contains("127.0.0.1"),
            "access log must include the client IP, got: " + line);
        assertTrue(line.contains("freeway-accesslog-test"),
            "access log must include the User-Agent, got: " + line);
        assertTrue(line.contains("GET / 200"),
            "access log must keep method/path/status, got: " + line);
    }

    @Test
    void writeTimeoutClosesConnectionWhenPeerStopsReading() throws Exception {
        int port = freePort();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, 1024,
                Duration.ofSeconds(2), 1024,
                Duration.ofSeconds(30), 0, Duration.ofMillis(500)))
            .route(Route.get("/big", ctx ->
                ctx.send(200, "x".repeat(16 * 1024 * 1024))))
            .build();
        server.start();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(
                "GET /big HTTP/1.1\r\nHost: x\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            // Do not read: the server's send buffer fills, the write blocks,
            // and the write timeout must close the connection on its own.
            Thread.sleep(1500);
            byte[] buf = new byte[8192];
            long total = 0;
            try {
                while (true) {
                    int n = socket.getInputStream().read(buf);
                    if (n < 0) break;
                    total += n;
                }
            } catch (SocketTimeoutException e) {
                fail("server must close the connection after the write timeout, "
                    + "read " + total + " bytes before timing out");
            }
            assertTrue(total < 16L * 1024 * 1024,
                "only the buffered prefix should have been delivered, got "
                    + total + " bytes");
        } finally {
            server.stop();
        }
    }

    private static String readStatusLine(Socket socket) throws IOException {
        StringBuilder line = new StringBuilder();
        while (line.indexOf("\r\n") < 0) {
            int c = socket.getInputStream().read();
            if (c < 0) break;
            line.append((char) c);
        }
        return line.toString();
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

    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    static final class TestMetrics implements Metrics {
        final Map<String, Counter> counters = new ConcurrentHashMap<>();
        final Map<String, Supplier<Number>> gauges = new ConcurrentHashMap<>();
        final Map<String, Timer> timers = new ConcurrentHashMap<>();

        @Override
        public Counter counter(String name) {
            return counters.computeIfAbsent(name, n -> new AtomicCounter());
        }

        @Override
        public void gauge(String name, Supplier<Number> value) {
            gauges.put(name, value);
        }

        @Override
        public Timer timer(String name) {
            return timers.computeIfAbsent(name, n -> new AtomicTimer());
        }

        long counterValue(String name) {
            Counter c = counters.get(name);
            return c == null ? 0 : c.value();
        }

        long timerCount(String name) {
            Timer t = timers.get(name);
            return t == null ? 0 : t.count();
        }

        long timerNanos(String name) {
            Timer t = timers.get(name);
            return t == null ? 0 : t.totalNanos();
        }
    }

    static final class AtomicCounter implements Metrics.Counter {
        private final AtomicLong value = new AtomicLong();

        @Override public void increment() { value.incrementAndGet(); }
        @Override public void add(long delta) { value.addAndGet(delta); }
        @Override public long value() { return value.get(); }
    }

    static final class AtomicTimer implements Metrics.Timer {
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong totalNanos = new AtomicLong();

        @Override public void record(long nanos) {
            count.incrementAndGet();
            totalNanos.addAndGet(nanos);
        }
        @Override public long count() { return count.get(); }
        @Override public long totalNanos() { return totalNanos.get(); }
    }
}
