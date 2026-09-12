package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.commons.bean.MethodHandleUtils;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.Route;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server side of remote invocation: serves
 * {@code POST /rpc/{mapping}/{method}} by invoking the exported handler
 * directly, so a remote call runs in the serving JVM like any other method
 * call — in whatever transaction context the request thread is in.
 *
 * <p><b>Applications do not use this class.</b> They declare what to expose
 * with an {@link RpcExport} contribution; the framework contributes the route
 * and resolves the handlers (see {@code RpcExportHook}). This class is the
 * protocol: version check, handler lookup, argument decoding, dispatch and the
 * exception boundary. Its two entries exist for the two assemblies that need a
 * route without the module: {@link #route} for a standalone {@code WebServer}
 * (an ext engine's {@code RouteIndex}, a custom mount) and {@link #exportsRoute}
 * for the framework's own wildcard route.
 */
public final class RpcEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(RpcEndpoint.class);

    private RpcEndpoint() {}

    /**
     * A route serving one mapping at its own literal path — the assembly for
     * callers that own the handler instance already (standalone servers, ext
     * engines, a custom mount). The application path is the {@link RpcExport}
     * contribution; this is the composition escape hatch.
     */
    public static Route route(RpcExport export, Object handler, JsonCodec codec) {
        Objects.requireNonNull(export, "export");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(codec, "codec");
        RpcTarget target = RpcTarget.of(export, handler);
        return Route.post(
            RpcPaths.routePattern(export.mapping()),
            ctx -> serve(ctx, export.mapping(), target, codec));
    }

    /**
     * The framework's route: one wildcard pattern, gated by the export set the
     * hook resolved at startup. Letting the hook own the gate keeps the export
     * declaration the single source of truth for what is reachable.
     */
    static Route exportsRoute(RpcExportHook hook) {
        Objects.requireNonNull(hook, "hook");
        return Route.post(RpcPaths.ROUTE_PATTERN, hook::serve);
    }

    /**
     * The protocol body, shared by both entries: version check, handler gate,
     * positional-argument decode, dispatch, exception boundary.
     */
    static void serve(HttpContext ctx, String mapping, RpcTarget target, JsonCodec codec)
            throws IOException {
        String rpcVersion = ctx.header(RemoteCaller.VERSION_HEADER).orElse(null);
        if (!RemoteCaller.VERSION.equals(rpcVersion)) {
            reject(ctx, codec, 400, "unsupported rpc version: " + rpcVersion);
            return;
        }
        String method = ctx.pathVar(RpcPaths.METHOD_VAR).orElse("");
        // The export table is the authority on what is reachable: an unexported
        // mapping never gets here, and an unsupported method name is a 404 —
        // the same answer as "nobody exports that name".
        MethodHandle handle = target.method(method);
        if (handle == null) {
            reject(ctx, codec, 404, "no handler for topic " + mapping + "." + method);
            return;
        }

        Object[] args;
        try {
            byte[] rawBody = ctx.body();
            args = decodeArgs(new String(rawBody, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            reject(ctx, codec, 400, "malformed argument array: " + e.getMessage());
            return;
        }
        try {
            Object result = MethodHandleUtils.invokeOn(handle, target.handler(), args);
            if (result == null) {
                ctx.send(200, "");
            } else {
                ctx.setHeader("Content-Type", "application/json");
                ctx.send(200, codec.toJson(result));
            }
        } catch (Throwable e) {
            // Handler failures (business or otherwise) never escape as a 500:
            // the class crosses the boundary, the message only on request.
            encodeBusinessFailure(ctx, codec, mapping, target.propagateMessage(), e);
        }
    }

    /** Positional JSON array → arguments; the element decoder never guesses types. */
    private static Object[] decodeArgs(String json) {
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

    private static void encodeBusinessFailure(
            HttpContext ctx, JsonCodec codec, String mapping,
            boolean propagateMessage, Throwable ex) throws IOException {
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
        ctx.setHeader(RemoteCaller.EXCEPTION_CLASS_HEADER, headerText(className));
        ctx.setHeader(RemoteCaller.EXCEPTION_MESSAGE_HEADER, headerText(message));
        ctx.send(400, errorBody(codec, className));
    }

    static void reject(HttpContext ctx, JsonCodec codec, int status, String message)
            throws IOException {
        ctx.setStatus(status);
        ctx.setHeader("Content-Type", "application/json");
        ctx.setHeader("X-RPC-Reject-Reason", headerText(message));
        ctx.send(status, errorBody(codec, message));
    }

    /**
     * Detail text is form-encoded on the wire. It routinely carries control
     * characters — a decoded path segment, a handler message with a stack
     * trace — and the HTTP layer refuses CTLs in a header value, which would
     * replace this careful 4xx with an unhandled 500.
     */
    private static String headerText(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Bodies go through the codec: an invalid JSON error document is a worse
     *  failure than the one it reports. */
    private static String errorBody(JsonCodec codec, String message) {
        return codec.toJson(Map.of("error", message));
    }
}
