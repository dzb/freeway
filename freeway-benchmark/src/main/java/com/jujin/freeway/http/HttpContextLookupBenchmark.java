package com.jujin.freeway.http;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
public class HttpContextLookupBenchmark {

    private BenchContext context;

    @Setup
    public void setup() {
        context = new BenchContext();
    }

    @Benchmark
    public String queryParam() {
        return context.queryParam("page");
    }

    @Benchmark
    public String headerExactLookup() {
        return context.headerExact("X-Trace-Id");
    }

    @Benchmark
    public String headerCaseInsensitiveFallbackLookup() {
        return context.headerCaseInsensitive("x-trace-id");
    }

    @Benchmark
    public String headerMissLookup() {
        return context.headerCaseInsensitive("x-missing-header");
    }

    @Benchmark
    public String paramLookup() {
        return context.param("id");
    }

    private static final class BenchContext extends HttpContext {

        private final Map<String, List<String>> queryParams = Map.of(
            "page",
            List.of("3"),
            "q",
            List.of("freeway")
        );
        private final Map<String, List<String>> headers = Map.of(
            "X-Trace-Id", List.of("trace-1"),
            "Accept", List.of("text/plain"),
            "Content-Type", List.of("application/json"),
            "Cache-Control", List.of("no-cache"),
            "User-Agent", List.of("freeway-bench"),
            "X-Request-Id", List.of("req-1"),
            "X-Forwarded-For", List.of("127.0.0.1"),
            "Authorization", List.of("Bearer token")
        );

        private BenchContext() {
            super(new JsonCodecDefault(), new CoercerDefault());
            pathVars(Map.of("id", "42"));
        }

        @Override
        public String method() {
            return "GET";
        }

        @Override
        public String path() {
            return "/users/42";
        }

        @Override
        public String queryParam(String name) {
            List<String> values = queryParams.get(name);
            return values != null && !values.isEmpty() ? values.getFirst() : null;
        }

        @Override
        public List<String> queryParams(String name) {
            List<String> values = queryParams.get(name);
            return values != null ? List.copyOf(values) : List.of();
        }

        @Override
        public Map<String, List<String>> queryParams() {
            return queryParams;
        }

        @Override
        public String header(String name) {
            return headerCaseInsensitive(name);
        }

        public String headerExact(String name) {
            List<String> values = headers.get(name);
            if (values != null && !values.isEmpty()) {
                return values.getFirst();
            }
            return null;
        }

        public String headerCaseInsensitive(String name) {
            List<String> values = headers.get(name);
            if (values != null && !values.isEmpty()) {
                return values.getFirst();
            }
            for (var entry : headers.entrySet()) {
                if (
                    entry.getKey().equalsIgnoreCase(name) &&
                    !entry.getValue().isEmpty()
                ) {
                    return entry.getValue().getFirst();
                }
            }
            return null;
        }

        @Override
        public List<String> headers(String name) {
            List<String> values = headers.get(name);
            if (values != null) {
                return List.copyOf(values);
            }
            for (var entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return List.copyOf(entry.getValue());
                }
            }
            return List.of();
        }

        @Override
        public byte[] body() {
            return new byte[0];
        }

        @Override
        public RequestContext requestContext() {
            return new RequestContextDefault("bench", Instant.EPOCH);
        }

        @Override
        public HttpContext status(int status) {
            return this;
        }

        @Override
        public int status() {
            return 200;
        }

        @Override
        public HttpContext headerSet(String name, String value) {
            return this;
        }

        @Override
        public HttpContext output(byte[] data) throws IOException {
            return this;
        }

        @Override
        public com.jujin.freeway.http.sse.SseEmitter sse() {
            throw new UnsupportedOperationException();
        }
    }
}
