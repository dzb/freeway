package com.jujin.freeway.cloud.rpc;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Typed consumer client for a remote mapping: every interface method becomes
 * one {@code POST /rpc/{mapping}/{method}} call, with the arguments carried
 * positionally as a JSON array.
 *
 * <pre>{@code
 * binder.bind(UserApi.class).to(container -> RemoteProxyFactory
 *     .of(container.get(RemoteCaller.class))
 *     .serviceId("user-service")   // discovery id of the serving instance
 *     .mapping("user")             // call topic prefix the provider exported
 *     .build(UserApi.class));
 * }</pre>
 *
 * <p>Binding the client once at the composition root is the intended shape:
 * call sites stay {@code @Inject UserApi} and never learn whether the
 * implementation is in-process or remote — that decision belongs to the
 * composition (bind the local implementation, or this client), which is also
 * why the interface can be shared without the modules depending on each
 * other.</p>
 *
 * <p>Object methods ({@code toString}/{@code hashCode}/{@code equals}) are
 * answered locally and never dispatched. Remote failures surface unchanged:
 * {@link CloudException} for transport failures, with
 * {@link RemoteInvocationException} as its cause when the remote handler threw
 * a business failure. {@link #timeout(Duration)} bounds a call end to end,
 * retries included; without it the transport's configured request timeout
 * applies.</p>
 *
 * <p>Ambiguous transport outcomes (timeout, mid-flight I/O, 5xx) are replayed
 * only for operations the interface marks {@link Idempotent} — on the method
 * or on the whole interface. Unmarked operations fail after the first
 * ambiguous outcome, because the remote handler may already have applied it.</p>
 */
public final class RemoteProxyFactory {

    private final RemoteCaller remote;
    private String serviceId;
    private String mapping;
    private Duration timeout;

    private RemoteProxyFactory(RemoteCaller remote) {
        this.remote = remote;
    }

    /** Starts a client bound to a caller — the transport the framework wires. */
    public static RemoteProxyFactory of(RemoteCaller remote) {
        return new RemoteProxyFactory(Objects.requireNonNull(remote, "remote"));
    }

    /** Discovery id of the serving service. */
    public RemoteProxyFactory serviceId(String serviceId) {
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
        return this;
    }

    /** Call-topic prefix the provider exported (e.g. {@code "user"}). */
    public RemoteProxyFactory mapping(String mapping) {
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        return this;
    }

    /**
     * End-to-end deadline for every proxied call (all retries included).
     * Without it a call is bounded only by the transport's per-request
     * timeout — it can occupy a thread for request-timeout × attempts plus
     * backoff.
     */
    public RemoteProxyFactory timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /** Builds the client. Validates the declaration up front. */
    public <T> T build(Class<T> api) {
        Objects.requireNonNull(api, "api");
        if (!api.isInterface()) {
            throw new IllegalArgumentException(
                "RemoteProxyFactory can only proxy interfaces: " + api.getName());
        }
        if (serviceId == null) {
            throw new IllegalStateException(
                "serviceId(...) is required — the remote target must be named");
        }
        if (mapping == null) {
            throw new IllegalStateException("mapping(...) is required");
        }
        return api.cast(Proxy.newProxyInstance(
            api.getClassLoader(), new Class<?>[]{api}, this::dispatch));
    }

    private Object dispatch(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return objectMethod(proxy, method, args);
        }
        return remoteDispatch(method, args);
    }

    private Object remoteDispatch(Method method, Object[] args) throws Throwable {
        // The consumer interface owns the replay-safety verdict: an @Idempotent
        // method (or interface) tells the resilience loop that ambiguous
        // outcomes may be replayed. Read reflectively per call — no scanning,
        // no wire change.
        boolean idempotent = method.isAnnotationPresent(Idempotent.class)
            || method.getDeclaringClass().isAnnotationPresent(Idempotent.class);
        // Failures keep their type: transport failures arrive as CloudException,
        // business failures as RemoteInvocationException.
        return remote.invoke(serviceId, mapping, method.getName(), asList(args),
            method.getReturnType(), timeout, idempotent);
    }

    private Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> getClass().getSimpleName()
                + "{serviceId=" + serviceId + ", mapping=" + mapping + "}";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length > 0 && proxy == args[0];
            default -> throw new IllegalStateException(
                "Unsupported Object method: " + method.getName());
        };
    }

    private static List<Object> asList(Object[] args) {
        return args == null ? List.of() : Arrays.asList(args);
    }
}
