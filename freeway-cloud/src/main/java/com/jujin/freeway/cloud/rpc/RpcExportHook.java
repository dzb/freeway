package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.MissingBindingException;
import com.jujin.freeway.ioc.RuntimeHook;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup assembly for the declared {@link RpcExport RPC exports} — the piece
 * that lets an application declare an export without ever holding a handler
 * instance, a codec or a route.
 *
 * <p>Runs before the HTTP server (see {@code CloudRpcModule}), which is what
 * makes one wildcard route enough: every dispatch table is built by the time
 * the route can receive a call. Everything that can be checked is checked
 * here — a duplicate mapping, an unbound handler type, an overloaded method —
 * so a wiring mistake fails startup with an actionable message instead of
 * answering 404 (or the wrong shape) in production.
 *
 * <p>Handlers are resolved from the container, so they are injected and their
 * lifecycle is the container's; they are invoked directly, without an
 * intermediate registry that could disagree with what was declared. There is
 * nothing to undo on stop for the same reason, so the default no-op stands.
 */
final class RpcExportHook implements RuntimeHook {

    private static final Logger LOG = LoggerFactory.getLogger(RpcExportHook.class);

    private volatile Map<String, RpcTarget> targets = Map.of();
    private volatile JsonCodec codec;

    @Override
    public void start(Container container) {
        JsonCodec json = container.get(JsonCodec.class);

        Map<String, RpcTarget> declared = new LinkedHashMap<>();
        for (RpcExport export : container.extension(RpcExport.class).all()) {
            RpcTarget duplicate = declared.putIfAbsent(export.mapping(),
                RpcTarget.of(export, handler(container, export)));
            if (duplicate != null) {
                throw new IllegalStateException(
                    "Mapping '" + export.mapping() + "' is exported twice ("
                        + duplicate.export().type().getName() + " and "
                        + export.type().getName()
                        + ") — one mapping has exactly one handler: rename one export");
            }
            LOG.info("Exported RPC mapping '{}' from {}",
                export.mapping(), export.type().getName());
        }

        this.targets = Map.copyOf(declared);
        this.codec = json;
    }

    /**
     * The handler the export names, resolved from the container so it is
     * injected and its lifecycle is the container's. An unbound type is a
     * wiring mistake worth stopping for, and the message says exactly what to
     * add.
     */
    private static Object handler(Container container, RpcExport export) {
        try {
            return container.get(export.type());
        } catch (MissingBindingException e) {
            throw new IllegalStateException(
                "Exported mapping '" + export.mapping() + "' names "
                    + export.type().getName() + ", which is not bound — add binder.bind("
                    + export.type().getSimpleName() + ".class) where the export is declared", e);
        }
    }

    /** The framework route's handler: gate on the export table, then the protocol. */
    void serve(HttpContext ctx) throws IOException {
        Map<String, RpcTarget> resolved = this.targets;
        JsonCodec json = this.codec;
        if (json == null) {
            // Unreachable through the framework (this hook runs before the HTTP
            // server starts); kept loud rather than silently 404-ing.
            throw new IllegalStateException(
                "RPC exports are not wired — the export hook runs before the HTTP server starts");
        }
        String mapping = ctx.pathVar(RpcPaths.MAPPING_VAR).orElse("");
        RpcTarget target = resolved.get(mapping);
        if (target == null) {
            // Unexported or malformed mapping: not reachable, by declaration.
            RpcEndpoint.reject(ctx, json, 404, "no export for mapping " + mapping);
            return;
        }
        RpcEndpoint.serve(ctx, mapping, target, json);
    }
}
