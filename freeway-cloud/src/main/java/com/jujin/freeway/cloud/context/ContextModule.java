package com.jujin.freeway.cloud.context;

import com.jujin.freeway.cloud.CloudKeys;
import com.jujin.freeway.cloud.context.internal.AuthCodec;
import com.jujin.freeway.cloud.context.internal.BaggageCodec;
import com.jujin.freeway.cloud.context.internal.ContextFilter;
import com.jujin.freeway.cloud.context.internal.TraceCodec;
import com.jujin.freeway.commons.util.ContextCarrier;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import java.util.Map;
import org.slf4j.MDC;

/**
 * IoC wiring for the context subsystem: {@code InvocationContext} (ScopedValue
 * carrier), {@code ContextCodec} chain, and the inbound {@code ContextFilter}.
 */
@Marker(Builtin.class)
public final class ContextModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        b.contribute(KnownKeys.class)
            .add(KnownKeys.of(ConfigKeys.class, CloudKeys.PREFIX,
                CloudKeys.APP_PREFIX));
        // Whole context, not just trace: the wire rule ("trace only, never
        // principal") guards a process boundary, while an executor thread is
        // the same trust domain — the handler sees what a synchronous publish
        // on the submitting thread would have seen. A traceless submit
        // dispatches bare (the mesh inbound rule).
        // One carrier for every context this module owns: the invocation
        // context (trace/principal/baggage) *and* the MDC fields the tracer
        // writes. A worker therefore logs the same traceId/spanId a
        // synchronous call would, and the previous worker state is restored
        // afterwards so a pooled thread cannot leak one task into the next.
        b.bind(ContextCarrier.class).to(c -> work -> {
            InvocationContext ctx = InvocationContext.current()
                .filter(value -> !value.carriesNothing())
                .orElse(null);
            Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
            boolean hasMdc = capturedMdc != null && !capturedMdc.isEmpty();
            if (ctx == null && !hasMdc) {
                return work;
            }
            return () -> {
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                try {
                    if (hasMdc) {
                        MDC.setContextMap(capturedMdc);
                    } else {
                        MDC.clear();
                    }
                    if (ctx != null) {
                        InvocationContext.runWith(ctx, work);
                    } else {
                        work.run();
                    }
                } finally {
                    if (previousMdc == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previousMdc);
                    }
                }
            };
        });
        b.contribute(ContextCodec.class).add("freeway.cloud.context.trace", new TraceCodec());
        // Container-created so the auth codec can read the
        // freeway.cloud.auth.extract.enabled switch from the SymbolSource.
        b.contribute(ContextCodec.class).addDeferred(AuthCodec.class);
        // After trace/auth: their decode() leaves unset baggage as null, so
        // the merge keeps the baggage parsed here (null wins only when absent).
        b.contribute(ContextCodec.class).add("freeway.cloud.context.baggage", new BaggageCodec());
        b.contribute(HttpFilter.class).addDeferred(ContextFilter.class);
    }
    /** Keys read by the context codecs. */
    public static final class ConfigKeys {
        private ConfigKeys() {}
        public static final String AUTH_EXTRACT_ENABLED = "freeway.cloud.auth.extract.enabled";
    }
}
