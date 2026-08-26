package com.jujin.freeway.commons.bean;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caching utility for {@link MethodHandle}, {@link VarHandle}, and
 * constructor handles used by the bean introspection framework.
 *
 * <p>All handles are lazily created and cached in {@link ClassValue}-keyed
 * concurrent maps: reads are lock-free on every path (including the AOP
 * invocation hot path), entries drop with their declaring class so
 * dynamically loaded classes do not leak, and a first-time race simply lets
 * one winner populate the inner map.
 *
 * <p>Public methods expose method-handle lookup and invocation for external use
 * (primarily by the IoC container), while package-private methods support
 * handle creation for fields and constructors used internally by
 * {@link BeanIntrospector}.
 */
public final class MethodHandleUtils {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodHandles.Lookup PUBLIC =
        MethodHandles.publicLookup();
    /**
     * Per-declaring-class caches: weakly reachable through ClassValue (entries
     * are collected with the class), internally concurrent so lookups never
     * block readers.
     */
    private static final ClassValue<Map<Constructor<?>, MethodHandle>> CONSTRUCTOR_HANDLES =
        new ClassValue<>() {
            @Override
            protected Map<Constructor<?>, MethodHandle> computeValue(Class<?> type) {
                return new ConcurrentHashMap<>();
            }
        };
    private static final ClassValue<Map<Method, MethodHandle>> METHOD_HANDLES =
        new ClassValue<>() {
            @Override
            protected Map<Method, MethodHandle> computeValue(Class<?> type) {
                return new ConcurrentHashMap<>();
            }
        };
    private static final ClassValue<Map<Field, VarHandle>> VAR_HANDLES =
        new ClassValue<>() {
            @Override
            protected Map<Field, VarHandle> computeValue(Class<?> type) {
                return new ConcurrentHashMap<>();
            }
        };

    private MethodHandleUtils() {
    }

    // -- public: method + invoke (called by ioc) --

    /**
     * Returns a cached MethodHandle for the given method.
     * <p>
     * Lock-free on the cached path: the declaring class selects the inner
     * concurrent map and {@code computeIfAbsent} creates at most once under
     * contention.
     *
     * @param method the reflection method object
     * @return the corresponding MethodHandle
     */
    public static MethodHandle methodHandle(Method method) {
        return METHOD_HANDLES.get(method.getDeclaringClass())
            .computeIfAbsent(method, MethodHandleUtils::createMethodHandle);
    }

    /**
     * Invokes a method handle with positional arguments.
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
     * Invokes a method handle on a receiver with the given argument array.
     * <p>
     * Prepends the receiver as the first argument and merges it with the
     * provided argument array before invoking the target method handle.
     * Named separately from {@link #invoke(MethodHandle, Object...)} so a
     * call site cannot silently mean "receiver" where "single argument"
     * (or the reverse) is read.
     *
     * @param handle   the method handle to invoke
     * @param receiver the receiver object (the target for instance methods)
     * @param args     the method arguments, may be null or empty
     * @return the invocation result
     * @throws Throwable if the invocation fails
     */
    public static Object invokeOn(MethodHandle handle, Object receiver, Object[] args) throws Throwable {
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
        return VAR_HANDLES.get(field.getDeclaringClass())
            .computeIfAbsent(field, MethodHandleUtils::createVarHandle);
    }

    /**
     * Returns a cached {@link MethodHandle} for the given constructor.
     *
     * @param constructor the reflection constructor object
     * @return the corresponding MethodHandle
     */
    static MethodHandle constructorHandle(Constructor<?> constructor) {
        return CONSTRUCTOR_HANDLES.get(constructor.getDeclaringClass())
            .computeIfAbsent(constructor, MethodHandleUtils::createConstructorHandle);
    }

    // -- private factories --

    /**
     * Best available lookup for {@code declaringClass}: a private lookup when
     * the module is open to us (application classes, open JDK modules),
     * falling back to {@link MethodHandles#publicLookup()} for public members
     * of non-open modules (e.g. {@code java.base}). Private members of
     * non-open modules are unreachable by any lookup — there is no further
     * fallback (setAccessible fails there too).
     */
    private static MethodHandles.Lookup lookupFor(Class<?> declaringClass) {
        try {
            return MethodHandles.privateLookupIn(declaringClass, LOOKUP);
        } catch (IllegalAccessException e) {
            return PUBLIC;
        }
    }

    private static MethodHandle createMethodHandle(Method method) {
        try {
            return lookupFor(method.getDeclaringClass())
                .unreflect(method)
                .asFixedArity();
        } catch (IllegalAccessException ex) {
            throw new RuntimeException("Cannot access method: " + method, ex);
        }
    }

    private static VarHandle createVarHandle(Field field) {
        try {
            return lookupFor(field.getDeclaringClass()).findVarHandle(
                field.getDeclaringClass(),
                field.getName(),
                field.getType()
            );
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(
                "Cannot create VarHandle for field: " + field,
                ex
            );
        }
    }

    private static MethodHandle createConstructorHandle(Constructor<?> constructor) {
        try {
            return lookupFor(constructor.getDeclaringClass())
                .unreflectConstructor(constructor);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException(
                "Cannot access constructor: " + constructor,
                ex
            );
        }
    }
}
