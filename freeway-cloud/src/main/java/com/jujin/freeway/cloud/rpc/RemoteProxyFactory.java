package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.ioc.CallBus;
import com.jujin.freeway.ioc.DeadCallException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * Typed consumer proxy that can resolve CallBus calls locally, remotely, or
 * both (design doc §3.3). The fluent builder is explicit about the mode —
 * there is no silent default.
 *
 * <pre>{@code
 * UserApi api = RemoteProxyFactory.of(callBus, remoteCaller)
 *     .serviceId("user")     // required for remote dispatch
 *     .mapping("user")       // call-topic prefix
 *     .localFirst()          // or .remoteOnly()
 *     .build(UserApi.class);
 * }</pre>
 *
 * <p><b>localFirst</b>: try the local CallBus slot first — same-process
 * modules skip serialization entirely; a {@link DeadCallException} (no local
 * provider) transparently falls through to the remote caller. <b>remoteOnly</b>:
 * every call goes over the wire. Both modes preserve CallBus positional-arg
 * semantics; Object methods (toString/hashCode/equals) are answered locally
 * and never dispatched.</p>
 *
 * <p>Remote failures surface unchanged: {@link CloudException} for transport
 * failures, and the {@link RemoteInvocationException} carried as its cause
 * when the remote handler threw — callers keep one honest catch shape per
 * failure class. {@link #timeout(Duration)} bounds the whole call, retries
 * included; without it only the configured request-timeout applies.</p>
 */
public final class RemoteProxyFactory {

    /** Dispatch mode — explicit, no default. */
    public enum Mode { LOCAL_FIRST, REMOTE_ONLY }

    private final CallBus callBus;
    private final RemoteCaller remote;
    private String serviceId;
    private String mapping;
    private Mode mode;
    private Duration timeout;

    private RemoteProxyFactory(CallBus callBus, RemoteCaller remote) {
        this.callBus = callBus;
        this.remote = remote;
    }

    /**
     * Starts a factory. Either dependency may be {@code null} when its mode
     * will not be used — but at least one must be present.
     */
    public static RemoteProxyFactory of(CallBus callBus, RemoteCaller remote) {
        if (callBus == null && remote == null) {
            throw new IllegalArgumentException(
                "at least one of callBus/remoteCaller is required");
        }
        return new RemoteProxyFactory(callBus, remote);
    }

    /** Discovery id of the remote service (required for remote dispatch). */
    public RemoteProxyFactory serviceId(String serviceId) {
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
        return this;
    }

    /** Call-topic prefix shared with the provider (e.g. {@code "user"}). */
    public RemoteProxyFactory mapping(String mapping) {
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        return this;
    }

    /** Local slot first, remote on {@code DeadCall}. Requires a non-null CallBus. */
    public RemoteProxyFactory localFirst() {
        this.mode = Mode.LOCAL_FIRST;
        return this;
    }

    /** Every invocation goes over the wire; the CallBus is never consulted. */
    public RemoteProxyFactory remoteOnly() {
        this.mode = Mode.REMOTE_ONLY;
        return this;
    }

    /**
     * End-to-end deadline for every proxied remote call (all retries
     * included). Without it a proxy is bounded only by the transport's
     * per-request timeout — a call can occupy a thread for request-timeout ×
     * attempts plus backoff.
     */
    public RemoteProxyFactory timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /** Builds the proxy. Validates the mode/builder combination up front. */
    public <T> T build(Class<T> api) {
        Objects.requireNonNull(api, "api");
        if (!api.isInterface()) {
            throw new IllegalArgumentException(
                "RemoteProxyFactory can only proxy interfaces: " + api.getName());
        }
        if (mode == null) {
            throw new IllegalStateException(
                "Choose a dispatch mode: localFirst() or remoteOnly()");
        }
        if (mode == Mode.LOCAL_FIRST && callBus == null) {
            throw new IllegalStateException(
                "localFirst() requires a CallBus — construct the factory with of(callBus, remote)");
        }
        if (mode == Mode.REMOTE_ONLY && remote == null) {
            throw new IllegalStateException(
                "remoteOnly() requires a RemoteCaller — construct the factory with of(callBus, remote)");
        }
        if (mode == Mode.REMOTE_ONLY && serviceId == null) {
            throw new IllegalStateException(
                "remoteOnly() requires serviceId(...) — the remote target must be named");
        }
        if (mapping == null) {
            throw new IllegalStateException("mapping(...) is required");
        }
        InvocationHandler handler = mode == Mode.LOCAL_FIRST
            ? this::localFirstDispatch
            : this::remoteOnlyDispatch;
        return api.cast(Proxy.newProxyInstance(
            api.getClassLoader(), new Class<?>[]{api}, handler));
    }

    private Object localFirstDispatch(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return objectMethod(proxy, method, args);
        }
        try {
            return callBus.call(topic(method), asList(args))
                .toCompletableFuture().join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof DeadCallException && remote != null) {
                return remoteDispatch(method, args);
            }
            throw e.getCause() == null ? e : e.getCause();
        }
    }

    private Object remoteOnlyDispatch(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return objectMethod(proxy, method, args);
        }
        return remoteDispatch(method, args);
    }

    private Object remoteDispatch(Method method, Object[] args) throws Throwable {
        try {
            return remote.invoke(serviceId, mapping, method.getName(), asList(args),
                method.getReturnType(), timeout);
        } catch (CloudException e) {
            // Transport failures keep their type; business failures are wrapped
            // (RemoteInvocationException) — both land here as CloudException.
            throw e;
        }
    }

    private Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> getClass().getSimpleName() + "{mapping=" + mapping + "}";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length > 0 && proxy == args[0];
            default -> throw new IllegalStateException(
                "Unsupported Object method: " + method.getName());
        };
    }

    private String topic(Method method) {
        return mapping + "." + method.getName();
    }

    private static List<Object> asList(Object[] args) {
        return args == null ? List.of() : Arrays.asList(args);
    }
}
