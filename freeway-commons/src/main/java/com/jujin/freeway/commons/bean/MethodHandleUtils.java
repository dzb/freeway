package com.jujin.freeway.commons.bean;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Caching utility for {@link MethodHandle}, {@link VarHandle}, and
 * constructor handles used by the bean introspection framework.
 *
 * <p>All handles are lazily created and cached in concurrent maps. Public
 * methods expose method-handle lookup and invocation for external use
 * (primarily by the IoC container), while package-private methods support
 * handle creation for fields and constructors used internally by
 * {@link BeanIntrospector}.
 */
public final class MethodHandleUtils {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    /**
     * Weakly keyed caches: entries are dropped when the reflection objects
     * (and their declaring classes / classloaders) become unreachable, so
     * dynamically loaded classes do not leak through these maps.
     */
    private static final Map<Constructor<?>, MethodHandle> CONSTRUCTOR_HANDLES =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Method, MethodHandle> METHOD_HANDLES =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Field, VarHandle> VAR_HANDLES =
        Collections.synchronizedMap(new WeakHashMap<>());

    private MethodHandleUtils() {
    }

    // -- public: method + invoke (called by ioc) --

    /**
     * Returns a cached MethodHandle for the given method.
     * <p>
     * Uses a concurrent cache: if a MethodHandle already exists for this
     * method it is returned directly; otherwise one is created and cached.
     *
     * @param method the reflection method object
     * @return the corresponding MethodHandle
     */
    public static MethodHandle methodHandle(Method method) {
        MethodHandle cached = METHOD_HANDLES.get(method);
        if (cached != null) {
            return cached;
        }
        MethodHandle created = createMethodHandle(method);
        METHOD_HANDLES.put(method, created);
        return created;
    }

    /**
     * Invokes a method handle with varargs.
     * <p>
     * Convenience wrapper that supports a variable number of arguments.
     * A null argument is treated as an empty array.
     *
     * @param handle the method handle to invoke
     * @param args   the arguments, may be null
     * @return the invocation result
     * @throws Throwable if the invocation fails
     */
    public static Object invoke(MethodHandle handle, Object... args) throws Throwable {
        return handle.invokeWithArguments(args == null ? new Object[0] : args);
    }

    /**
     * Invokes a method handle with a receiver and argument array.
     * <p>
     * Prepends the receiver as the first argument and merges it with the
     * provided argument array before invoking the target method handle.
     * This is useful for instance methods where the receiver must be
     * passed as the implicit first argument.
     *
     * @param handle   the method handle to invoke
     * @param receiver the receiver object (the target for instance methods)
     * @param args     the method arguments, may be null or empty
     * @return the invocation result
     * @throws Throwable if the invocation fails
     */
    public static Object invoke(MethodHandle handle, Object receiver, Object[] args) throws Throwable {
        // Build the full argument array including the receiver
        Object[] invocationArgs;
        if (args == null || args.length == 0) {
            invocationArgs = new Object[]{receiver};
        } else {
            invocationArgs = new Object[args.length + 1];
            invocationArgs[0] = receiver;
            System.arraycopy(args, 0, invocationArgs, 1, args.length);
        }
        return invoke(handle, invocationArgs);
    }

    // -- package-private: varHandle/constructorHandle (internal to commons.bean) --

    /**
     * Returns a cached {@link VarHandle} for the given field.
     *
     * @param field the reflection field object
     * @return the corresponding VarHandle
     */
    static VarHandle varHandle(Field field) {
        VarHandle cached = VAR_HANDLES.get(field);
        if (cached != null) {
            return cached;
        }
        VarHandle created = createVarHandle(field);
        VAR_HANDLES.put(field, created);
        return created;
    }

    /**
     * Returns a cached {@link MethodHandle} for the given constructor.
     *
     * @param constructor the reflection constructor object
     * @return the corresponding MethodHandle
     */
    static MethodHandle constructorHandle(Constructor<?> constructor) {
        MethodHandle cached = CONSTRUCTOR_HANDLES.get(constructor);
        if (cached != null) {
            return cached;
        }
        MethodHandle created = createConstructorHandle(constructor);
        CONSTRUCTOR_HANDLES.put(constructor, created);
        return created;
    }

    // -- private factories --

    private static MethodHandle createMethodHandle(Method method) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), LOOKUP);
            return lookup.unreflect(method).asFixedArity();
        } catch (IllegalAccessException ex) {
            // Module system blocked privateLookupIn (e.g. javax.sql.DataSource
            // from java.sql module). Fall back to setAccessible + reflection.
            method.setAccessible(true);
            try {
                return LOOKUP.unreflect(method).asFixedArity();
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot access method: " + method, e);
            }
        }
    }

    private static VarHandle createVarHandle(Field field) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(field.getDeclaringClass(), LOOKUP);
            return lookup.findVarHandle(field.getDeclaringClass(), field.getName(), field.getType());
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Cannot create VarHandle for field: " + field, ex);
        }
    }

    private static MethodHandle createConstructorHandle(Constructor<?> constructor) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                constructor.getDeclaringClass(),
                LOOKUP
            );
            return lookup.unreflectConstructor(constructor);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException("Cannot access constructor: " + constructor, ex);
        }
    }
}
