package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.commons.bean.MethodHandleUtils;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One exported mapping ready to serve: the handler the container resolved,
 * its public methods by name, and the export's message policy — the dispatch
 * table behind {@code POST /rpc/{mapping}/{method}}.
 *
 * <p>Built once, at startup, so everything checkable is checked there: two
 * public methods sharing a name cannot be told apart by a positional argument
 * array, so an overload fails the boot instead of silently serving the wrong
 * shape. Eligible methods follow the same rule the wire contract implies —
 * public, instance, non-synthetic, nothing inherited from {@link Object}.</p>
 *
 * <p><b>The surface is the declared type's</b>, not the resolved instance's
 * class: iterating {@code handler.getClass()} meant that binding a narrow
 * interface to a wide implementation silently exported the extra public
 * methods, which contradicts the whole point of declaring the mapping. The
 * declared type is the boundary, and a wider implementation changes nothing;
 * the container still guarantees the resolved instance is assignable to it.</p>
 */
final class RpcTarget {

    private final RpcExport export;
    private final Object handler;
    private final Map<String, MethodHandle> methods;

    private RpcTarget(RpcExport export, Object handler, Map<String, MethodHandle> methods) {
        this.export = export;
        this.handler = handler;
        this.methods = methods;
    }

    static RpcTarget of(RpcExport export, Object handler) {
        Objects.requireNonNull(export, "export");
        Objects.requireNonNull(handler, "handler");
        Map<String, MethodHandle> methods = new HashMap<>();
        for (Method method : export.type().getMethods()) {
            int mods = method.getModifiers();
            if (method.getDeclaringClass() == Object.class
                    || Modifier.isStatic(mods)
                    || method.isSynthetic()) {
                continue;
            }
            if (methods.putIfAbsent(method.getName(),
                    MethodHandleUtils.methodHandle(method)) != null) {
                throw new IllegalStateException(
                    "Mapping '" + export.mapping() + "' type "
                        + export.type().getName() + " declares method '"
                        + method.getName() + "' more than once — the positional wire"
                        + " contract cannot tell overloads apart: keep one name per shape");
            }
        }
        return new RpcTarget(export, handler, Map.copyOf(methods));
    }

    RpcExport export() {
        return export;
    }

    Object handler() {
        return handler;
    }

    boolean propagateMessage() {
        return export.propagateMessage();
    }

    /** The named method, or {@code null} when this mapping does not serve it. */
    MethodHandle method(String name) {
        return methods.get(name);
    }
}
