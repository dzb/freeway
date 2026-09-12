package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.ioc.CallBus;
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
 * that lets an application declare an export without ever holding a bus, a
 * codec or a route.
 *
 * <p>Runs before the HTTP server (see {@code CloudRpcModule}), which is what
 * makes one wildcard route enough: the declarations are all in place by the
 * time the route can receive a call. Everything that can be checked is checked
 * here — a duplicate mapping, an unbound handler type — so a wiring mistake
 * fails startup with an actionable message instead of answering 404 in
 * production.
 *
 * <p>Handlers register on the <b>container's</b> {@link CallBus}. That is the
 * same instance local-first client dispatch resolves, so a mapping can never be
 * served on one bus while the client looks on another.
 */
final class RpcExportHook implements RuntimeHook {

    private static final Logger LOG = LoggerFactory.getLogger(RpcExportHook.class);

    private volatile Map<String, RpcExport> exports = Map.of();
    private volatile CallBus callBus;
    private volatile JsonCodec codec;

    @Override
    public void start(Container container) {
        CallBus bus = container.get(CallBus.class);
        JsonCodec json = container.get(JsonCodec.class);

        Map<String, RpcExport> declared = new LinkedHashMap<>();
        for (RpcExport export : container.extension(RpcExport.class).all()) {
            RpcExport duplicate = declared.putIfAbsent(export.mapping(), export);
            if (duplicate != null) {
                throw new IllegalStateException(
                    "Mapping '" + export.mapping() + "' is exported twice ("
                        + duplicate.type().getName() + " and " + export.type().getName()
                        + ") — one mapping has exactly one handler: rename one export");
            }
            bus.register(export.mapping(), handler(container, export));
            LOG.info("Exported RPC mapping '{}' from {}",
                export.mapping(), export.type().getName());
        }

        this.exports = Map.copyOf(declared);
        this.codec = json;
        this.callBus = bus;
    }

    @Override
    public void stop(Container container) {
        // The bus is a container-managed service: closing the container drops
        // the registrations, so there is nothing to undo here.
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

    /** The framework route's handler: gate on the export set, then the protocol. */
    void serve(HttpContext ctx) throws IOException {
        CallBus bus = this.callBus;
        JsonCodec json = this.codec;
        if (bus == null || json == null) {
            // Unreachable through the framework (this hook runs before the HTTP
            // server starts); kept loud rather than silently 404-ing.
            throw new IllegalStateException(
                "RPC exports are not wired — the export hook runs before the HTTP server starts");
        }
        String mapping = ctx.pathVar(RpcPaths.MAPPING_VAR).orElse("");
        RpcExport export = exports.get(mapping);
        if (export == null) {
            // Unexported or malformed mapping: not reachable, by declaration.
            RpcEndpoint.reject(ctx, json, 404, "no export for mapping " + mapping);
            return;
        }
        RpcEndpoint.serve(ctx, mapping, export.propagateMessage(), bus, json);
    }
}
