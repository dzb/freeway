package com.jujin.freeway2.commons.bean;

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

    // -- public: method + invoke（被 ioc 调用） --

    public static MethodHandle methodHandle(Method method) {
        return METHOD_HANDLES.computeIfAbsent(method, MethodHandleUtils::createMethodHandle);
    }

    public static Object invoke(MethodHandle handle, Object... args) throws Throwable {
        return handle.invokeWithArguments(args == null ? new Object[0] : args);
    }

    public static Object invoke(MethodHandle handle, Object receiver, Object[] args) throws Throwable {
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

    // -- package-private: varHandle/constructorHandle（仅 commons.bean 内部使用） --

    static VarHandle varHandle(Field field) {
        return VAR_HANDLES.computeIfAbsent(field, MethodHandleUtils::createVarHandle);
    }

    static MethodHandle constructorHandle(Constructor<?> constructor) {
        return CONSTRUCTOR_HANDLES.computeIfAbsent(constructor, MethodHandleUtils::createConstructorHandle);
    }

    // -- private 工厂 --

    private static MethodHandle createMethodHandle(Method method) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), LOOKUP);
            return lookup.unreflect(method).asFixedArity();
        } catch (IllegalAccessException ex) {
            throw new RuntimeException("Cannot access method: " + method, ex);
        }
    }

    static VarHandle createVarHandle(Field field) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(field.getDeclaringClass(), LOOKUP);
            return lookup.findVarHandle(field.getDeclaringClass(), field.getName(), field.getType());
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Cannot create VarHandle for field: " + field, ex);
        }
    }

    static MethodHandle createConstructorHandle(Constructor<?> constructor) {
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
