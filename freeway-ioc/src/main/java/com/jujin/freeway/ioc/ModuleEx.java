package com.jujin.freeway.ioc;

/**
 * A module is the fundamental building block of a Freeway application.
 * Every module implements this interface and declares its bindings in
 * {@link #bind(Binder)}.
 *
 * <p>Modules are self-contained and declarative — they only declare what
 * should exist, without starting work during {@code bind()}. Actual
 * initialization happens when the container resolves services or when
 * {@link RuntimeHook#start(Container)} fires.
 *
 * <p>Example:
 * <pre>{@code
 * public class AppModule implements ModuleEx {
 *     public void bind(Binder b) {
 *         b.bind(UserService.class).to(UserServiceImpl.class);
 *         b.install(new HttpModule());
 *         b.contribute(Route.class).add(Route.get("/", ctx -> ctx.send(200, "Hi")));
 *     }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface ModuleEx {
    void bind(Binder binder);
}
