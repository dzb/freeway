package com.jujin.freeway.commons.bean;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MethodHandleUtils {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final ConcurrentMap<Constructor<?>, MethodHandle> CONSTRUCTOR_HANDLES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Method, MethodHandle> METHOD_HANDLES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Field, VarHandle> VAR_HANDLES = new ConcurrentHashMap<>();

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
        return METHOD_HANDLES.computeIfAbsent(method, MethodHandleUtils::createMethodHandle);
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

    static VarHandle varHandle(Field field) {
        return VAR_HANDLES.computeIfAbsent(field, MethodHandleUtils::createVarHandle);
    }

    static MethodHandle constructorHandle(Constructor<?> constructor) {
        return CONSTRUCTOR_HANDLES.computeIfAbsent(constructor, MethodHandleUtils::createConstructorHandle);
    }

    // -- private factories --

    private static MethodHandle createMethodHandle(Method method) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), LOOKUP);
            return lookup.unreflect(method).asFixedArity();
        } catch (IllegalAccessException ex) {
            throw new RuntimeException("Cannot access method: " + method, ex);
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
