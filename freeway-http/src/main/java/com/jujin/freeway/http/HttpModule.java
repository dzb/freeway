package com.jujin.freeway.http;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Module;
import com.jujin.freeway.ioc.ServiceId;
import java.util.Map;

public final class HttpModule implements Module {
    @Override
    public void bind(Binder binder) {
        binder.bind(RouteIndex.class).to(RouteIndex.class);
        binder.bind(WebSocketIndex.class).to(WebSocketIndex.class);
        binder.bind(JsonCodec.class).to(DefaultJsonCodec.class);
        binder.bind(CorsFilter.class).to(CorsFilter.class);
        binder.bind(WebServer.class).to(WebServer.class);
        binder.bind(JdkHttpEngine.class).to(JdkHttpEngine.class).id(ServiceId.of("jdk"));

        binder.contribute(HttpFilter.class).add(new RequestTimingFilter());

        binder.contribute(ExceptionMapper.class).add((ctx, ex) -> {
            if (ex instanceof RequestBodyTooLargeException) {
                ctx.sendJson(413, Map.of(
                    "error", "Payload Too Large",
                    "message", ex.getMessage()
                ));
                return true;
            }
            return false;
        });
    }
}
