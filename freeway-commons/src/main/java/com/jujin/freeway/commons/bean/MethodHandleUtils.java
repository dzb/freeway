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

    // -- public: method + invoke（被 ioc 调用） --

    /**
     * 获取或创建指定方法的MethodHandle。
     * <p>
     * 使用缓存机制提高性能，如果该方法的MethodHandle已存在则直接返回，
     * 否则通过createMethodHandle创建并缓存新的MethodHandle。
     *
     * @param method 要获取MethodHandle的反射方法对象
     * @return 对应的MethodHandle实例
     */
    public static MethodHandle methodHandle(Method method) {
        return METHOD_HANDLES.computeIfAbsent(method, MethodHandleUtils::createMethodHandle);
    }

    /**
     * 使用可变参数调用方法句柄。
     * <p>
     * 此方法提供便捷的调用方式，支持可变数量的参数。如果参数为null，
     * 则转换为空数组进行调用。
     *
     * @param handle 要调用的方法句柄
     * @param args   可变参数列表，可以为null
     * @return 方法调用的返回值
     * @throws Throwable 当方法调用过程中发生异常时抛出
     */
    public static Object invoke(MethodHandle handle, Object... args) throws Throwable {
        return handle.invokeWithArguments(args == null ? new Object[0] : args);
    }

    /**
     * 使用接收者对象和参数数组调用方法句柄。
     * <p>
     * 此方法将接收者对象作为第一个参数，与提供的参数数组合并后调用目标方法句柄。
     * 适用于实例方法的调用场景，其中接收者对象需要作为隐式的第一个参数传递。
     *
     * @param handle   要调用的方法句柄
     * @param receiver 接收者对象，作为方法调用的第一个参数（对于实例方法即为目标对象）
     * @param args     方法参数数组，可以为null或空数组
     * @return 方法调用的返回值
     * @throws Throwable 当方法调用过程中发生异常时抛出
     */
    public static Object invoke(MethodHandle handle, Object receiver, Object[] args) throws Throwable {
        // 构建包含接收者对象的完整参数数组
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
