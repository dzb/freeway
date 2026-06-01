package com.jujin.freeway.http;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Module;
import com.jujin.freeway.ioc.RuntimeHook;
import java.util.Map;

public final class HttpModule implements Module {
    public static final String SERVER_HOOK = "freeway.http.server";

    @Override
    public void bind(Binder binder) {
        binder.bind(RouteIndex.class).to(RouteIndex.class);
        binder.bind(WebSocketIndex.class).to(WebSocketIndex.class);
        binder.bind(JsonCodec.class).to(JsonCodecDefault.class);
        binder.bind(CorsFilter.class).to(CorsFilter.class);
        binder.bind(WebServer.class).to(WebServer.class);
        binder.bind(JdkHttpEngine.class).to(JdkHttpEngine.class).id("jdk");

        binder.contribute(RuntimeHook.class).add(SERVER_HOOK, new RuntimeHook() {
            @Override
            public void start(Container container) {
                container.get(WebServer.class).start();
            }

            @Override
            public void stop(Container container) {
                container.get(WebServer.class).stop();
            }
        });

        binder.contribute(HttpFilter.class).add(new RequestTimingFilter());

        binder.contribute(ExceptionMapper.class).add((ctx, ex) -> {
            if (ex instanceof RequestBodyTooLargeException) {
                ctx.sendJson(413, Map.of(
                    "error", "Payload Too Large",
                    "message", ex.getMessage()
                ));
                return true;
            }
            if (ex instanceof ValidationException ve) {
                var errors = ve.getResult().getErrors().stream()
                    .map(e -> Map.of("field", e.getField(), "message", e.getMessage()))
                    .toList();
                ctx.sendJson(400, Map.of(
                    "error", "Validation Failed",
                    "details", errors
                ));
                return true;
            }
            return false;
        });
    }
}
