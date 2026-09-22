package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.commons.bean.MethodHandleUtils;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.Route;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
 * route without the module: {@link #route} for a standalone {@code HttpServer}
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
        RpcTarget.Exported entry = target.method(method);
        if (entry == null) {
            reject(ctx, codec, 404, "no handler for topic " + mapping + "." + method);
            return;
        }

        Object[] args;
        try {
            byte[] rawBody = ctx.body();
            args = decodeArgs(new String(rawBody, StandardCharsets.UTF_8),
                entry.parameterTypes(), entry.method().isVarArgs(), codec);
        } catch (RuntimeException e) {
            reject(ctx, codec, 400, "malformed argument array: " + e.getMessage());
            return;
        }
        try {
            Object result = MethodHandleUtils.invokeOn(entry.handle(), target.handler(), args);
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

    /**
     * Positional JSON array → arguments re-bound to the handler's declared
     * parameter types; the element decoder never guesses types. A container
     * argument (a DTO serialized as an object, a list as an array) arrives as
     * a parsed node and is coerced per the declared type — without this the
     * method handle would receive a raw {@code Map}/{@code List} and fail as
     * a {@link ClassCastException} indistinguishable from the handler's own.
     */
    static Object[] decodeArgs(String json, Type[] parameterTypes, boolean isVarArgs,
            JsonCodec codec) {
        var elements = JsonUtils.parseArray(json);
        // A varargs tail arrives as separate wire elements and is assembled
        // into the array the fixed-arity handle wants — Method.isVarArgs is
        // the authority (its last parameter type is always an array), so an
        // array parameter of a non-varargs method stays a plain parameter.
        int fixed = isVarArgs ? parameterTypes.length - 1 : parameterTypes.length;
        if (isVarArgs ? elements.size() < fixed : elements.size() != parameterTypes.length) {
            throw new IllegalArgumentException(
                "argument array carries " + elements.size() + " element(s) but the "
                    + "handler declares " + Arrays.toString(parameterTypes)
                    + (isVarArgs ? " (varargs tail)" : "")
                    + " — the caller and the export disagree");
        }
        Object[] args = new Object[parameterTypes.length];
        for (int i = 0; i < fixed; i++) {
            args[i] = rebind(elements.get(i), parameterTypes[i], codec);
        }
        if (isVarArgs) {
            Class<?> component =
                ((Class<?>) parameterTypes[parameterTypes.length - 1]).getComponentType();
            Object tail = Array.newInstance(component, elements.size() - fixed);
            for (int i = fixed; i < elements.size(); i++) {
                Array.set(tail, i - fixed, rebind(elements.get(i), component, codec));
            }
            args[fixed] = tail;
        }
        return args;
    }

    private static Object rebind(Object element, Type declared, JsonCodec codec) {
        // Scalar leaves stay as primitives/strings; Object parameters keep
        // the pre-typed wire shape (Map/List); anything else is coerced.
        if (element == null || declared == Object.class) {
            return element instanceof JsonObject o
                ? o.toMap()
                : element instanceof JsonArray a ? a.toList() : element;
        }
        return codec.convert(element, declared);
    }

    private static void encodeBusinessFailure(
            HttpContext ctx, JsonCodec codec, String mapping,
            boolean propagateMessage, Throwable ex) throws IOException {
        // The detail is always available to operators on THIS side; what
        // crosses the boundary is the class (the contract) and, only on
        // request, the free-text message. The warn line names the mapping and
        // the failing class — the signal an alert rule greps — while the stack
        // stays at debug: most of these are expected business failures, and
        // paying a stack trace for each would bury the one that is a bug.
        LOG.warn("RPC handler failed for mapping '{}': {}", mapping, ex.toString());
        LOG.debug("RPC handler failure detail for mapping '{}'", mapping, ex);
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
