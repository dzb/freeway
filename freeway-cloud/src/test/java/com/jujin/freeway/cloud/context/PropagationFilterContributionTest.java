package com.jujin.freeway.cloud.context;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.cloud.context.internal.PropagationFilter;
import com.jujin.freeway.http.HttpExchange;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code PropagationFilter} is itself a deferred contribution. Resolving its
 * propagator list in the constructor used to freeze the modules that happened
 * to publish before it; the lazy supplier reads the sealed store instead.
 */
class PropagationFilterContributionTest {

    public static final class LatePropagator implements Propagator {
        static final AtomicInteger EXTRACTED = new AtomicInteger();

        @Override
        public void inject(InvocationContext ctx, Map<String, String> headers) {
        }

        @Override
        public InvocationContext extract(Map<String, String> headers) {
            EXTRACTED.incrementAndGet();
            return null;
        }
    }

    @Test
    void propagatorContributedAfterTheModuleReachesTheFilter() throws Exception {
        LatePropagator.EXTRACTED.set(0);
        Container container = Freeway.create(
            new CloudContextModule(),
            binder -> binder.contribute(Propagator.class).addDeferred(LatePropagator.class)
        );
        try {
            PropagationFilter filter = (PropagationFilter)
                container.extension(HttpFilter.class).all().stream()
                    .filter(PropagationFilter.class::isInstance)
                    .findFirst()
                    .orElseThrow();

            HttpExchange context = (HttpExchange) Proxy.newProxyInstance(
                HttpExchange.class.getClassLoader(),
                new Class<?>[] {HttpExchange.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("headers")) {
                        return Map.of("x-test", List.of("value"));
                    }
                    if (method.getName().equals("toString")) {
                        return "test-context";
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });

            AtomicInteger next = new AtomicInteger();
            RouteHandler handler = ctx -> next.incrementAndGet();
            filter.doFilter(context, handler);

            assertEquals(1, LatePropagator.EXTRACTED.get(),
                "a Propagator contributed after CloudContextModule must reach the filter");
            assertEquals(1, next.get());
        } finally {
            container.close();
        }
    }
}
