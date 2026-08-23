package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.context.Baggage;
import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.Propagator;

import java.util.HashMap;
import java.util.Map;

/**
 * W3C {@code baggage} propagation (RFC 7071 semantics): injects the current
 * {@link Baggage} as a {@code baggage} header ({@code k=v,k2=v2}), extracts
 * it back on the receiving side. Inbound extraction returns {@code null} when
 * the header is absent or empty, so the merge in {@link PropagationFilter}
 * keeps any baggage established by earlier propagators.
 */
public final class BaggagePropagator implements Propagator {

    public static final String HEADER_BAGGAGE = "baggage";

    @Override
    public void inject(InvocationContext ctx, Map<String, String> headers) {
        Baggage baggage = ctx.baggage();
        if (baggage == null || baggage.values().isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : baggage.values().entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        headers.put(HEADER_BAGGAGE, sb.toString());
    }

    @Override
    public InvocationContext extract(Map<String, String> headers) {
        String raw = headers.get(HEADER_BAGGAGE);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Map<String, String> values = new HashMap<>();
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                values.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        return InvocationContext.of(null, null, Baggage.of(values));
    }
}
