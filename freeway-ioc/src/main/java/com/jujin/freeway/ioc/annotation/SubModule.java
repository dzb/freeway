package com.jujin.freeway.ioc.annotation;

import com.jujin.freeway.ioc.ModuleEx;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the modules a module bundles: placing the annotated module also
 * places the listed submodules, after it and in the listed order.
 *
 * <p>A module with this annotation is a bundle — it may still declare its own
 * bindings in {@code bind(Binder)} (the shared surface of the bundle), and its
 * submodules follow it in the composition. The declaration is static metadata,
 * read once while the composition tree is built: the entry point still decides
 * what is placed. A submodule is an ordinary module and can always be placed
 * on its own instead of the bundle — taking a subset is composing the modules
 * you want, so no exclusion API exists.
 *
 * <pre>{@code
 * @SubModule({HttpModule.class, WebSocketModule.class})
 * public final class WebBundle implements ModuleEx {
 *     @Override public void bind(Binder b) { ... }
 * }
 *
 * FreewayApp.run(ModuleNode.app("app", ModuleNode.of(WebBundle.class)));
 * }</pre>
 *
 * <p>Submodules are named by class (annotations cannot carry instances), so a
 * configured submodule is placed as an instance instead. Cycles — a class
 * reaching itself through {@code @SubModule} — are refused while the tree is
 * built.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SubModule {

    /** The modules this module bundles, in composition order. */
    Class<? extends ModuleEx>[] value();
}
