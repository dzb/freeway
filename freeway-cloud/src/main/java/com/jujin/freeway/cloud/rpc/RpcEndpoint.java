package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.CallBus;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.net.URLDecoder;

/**
 * Server side of the remote-CallBus bridge: exposes the container's
 * registered CallBus mappings as {@code POST /rpc/{mapping}/{method}}
 * endpoints. Reuses the local bus for dispatch — inside the serving JVM a
 * remote call behaves exactly like a local one, including in-transaction
 * semantics.
 *
 * <p>Export is <b>explicit</b>: each mapping you hand to {@link #of} becomes
 * reachable; nothing is auto-discovered (design doc §3.2 — no CloudExporter).</p>
 */
public final class RpcEndpoint {


    private static final Logger LOG = LoggerFactory.getLogger(RpcEndpoint.class);
    private final String mapping;
    private final CallBus callBus;
    private final JsonCodec codec;
    /** When false, the handler's free-text message stays on this side. */
    private final boolean propagateMessage;

    private RpcEndpoint(String mapping, CallBus callBus, JsonCodec codec,
                        boolean propagateMessage) {
        this.mapping = mapping;
        this.callBus = callBus;
        this.codec = codec;
        this.propagateMessage = propagateMessage;
    }

    /**
     * Creates the route contribution for one mapping. The handler's exception
     * message is <b>not</b> sent to the caller — see
     * {@link #of(String, CallBus, JsonCodec, boolean)}.
     *
     * @param mapping call-topic prefix to expose (e.g. {@code "user"}) —
     *                topics beyond this prefix stay local-only
     */
    public static Route of(String mapping, CallBus callBus, JsonCodec codec) {
        return of(mapping, callBus, codec, false);
    }

    /**
     * As {@link #of(String, CallBus, JsonCodec)} with an explicit choice about
     * the exception message. The exception <i>class</i> always crosses — it is
     * the caller's dispatch contract. The <i>message</i> is free text and
     * routinely carries SQL, host names and identifiers, so it stays here
     * unless you opt in on a mesh you control end to end.
     *
     * @param propagateMessage send the handler's message to the caller
     */
    public static Route of(String mapping, CallBus callBus, JsonCodec codec,
                           boolean propagateMessage) {
        RpcEndpoint endpoint = new RpcEndpoint(mapping, callBus, codec, propagateMessage);
        return Route.post("/rpc/{mapping}/{method}", endpoint::serve);
    }

    private void serve(HttpContext ctx) throws IOException {
        String rpcVersion = ctx.header(RemoteCaller.VERSION_HEADER).orElse(null);
        if (!RemoteCaller.VERSION.equals(rpcVersion)) {
            reject(ctx, 400, "unsupported rpc version: " + rpcVersion);
            return;
        }
        String mappingPath = ctx.pathVar("mapping").orElse("");
        String method = ctx.pathVar("method").orElse("");
        String topic = mapping + "." + method;
        // The declared prefix gates exposure; a request for a sibling mapping
        // through our path must not be served even if that topic exists locally.
        if (!mapping.equals(mappingPath) || !callBus.handles(topic)) {
            reject(ctx, 404, "no handler for topic " + topic);
            return;
        }

        Object[] args;
        try {
            byte[] rawBody = ctx.body();
            args = decodeArgs(new String(rawBody, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            reject(ctx, 400, "malformed argument array: " + e.getMessage());
            return;
        }
        try {
            Object result = callBus.call(topic, List.of(args)).toCompletableFuture().join();
            if (result == null) {
                ctx.send(200, "");
            } else {
                ctx.setHeader("Content-Type", "application/json");
                ctx.send(200, codec.toJson(result));
            }
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            encodeBusinessFailure(ctx, cause);
        } catch (RuntimeException e) {
            // Inline dispatch surface: DeadCall raced past handles(), or an
            // advice failed before reaching the handler.
            encodeBusinessFailure(ctx, e);
        }
    }

    /** Positional JSON array → arguments; the element decoder never guesses types. */
    private Object[] decodeArgs(String json) {
        var elements = com.jujin.freeway.commons.json.JsonUtils.parseArray(json);
        List<Object> args = new ArrayList<>(elements.size());
        for (int i = 0; i < elements.size(); i++) {
            Object element = elements.get(i);
            // Scalar leaves stay as primitives/strings; containers are passed
            // as Map/List and re-bound by the handler's own coercion on invoke.
            args.add(element instanceof com.jujin.freeway.commons.json.JsonObject o
                ? o.toMap()
                : element instanceof com.jujin.freeway.commons.json.JsonArray a ? a.toList() : element);
        }
        return args.toArray();
    }

    private void encodeBusinessFailure(HttpContext ctx, Throwable ex) throws IOException {
        // The detail is always available to operators on THIS side; what
        // crosses the boundary is the class (the contract) and, only on
        // request, the free-text message.
        LOG.warn("RPC handler failed for mapping '{}': {}", mapping, ex.toString());
        String className = ex.getClass().getName();
        String message = propagateMessage
            ? String.valueOf(ex.getMessage())
            : "remote handler failed";
        ctx.setStatus(400);
        ctx.setHeader("Content-Type", "application/json");
        ctx.setHeader(RemoteCaller.EXCEPTION_CLASS_HEADER,
            java.net.URLEncoder.encode(className, StandardCharsets.UTF_8));
        ctx.setHeader(RemoteCaller.EXCEPTION_MESSAGE_HEADER,
            java.net.URLEncoder.encode(message, StandardCharsets.UTF_8));
        ctx.send(400, "{\"error\":\"" + escape(className) + "\"}");
    }

    private void reject(HttpContext ctx, int status, String message) throws IOException {
        ctx.setStatus(status);
        ctx.setHeader("Content-Type", "application/json");
        ctx.setHeader("X-RPC-Reject-Reason", message);
        ctx.send(status, "{\"error\":\"" + escape(message) + "\"}");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
