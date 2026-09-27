package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.SubModule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPI gap-filling for a launch: the modules the composition does not already
 * declare are appended from {@code META-INF/services}.
 *
 * <p>An author's declaration always wins over discovery, and "declared" is
 * answered by {@link #declaredClasses} — bundles expanded, so placing a bundle
 * whose {@code @SubModule} lists an SPI provider is still a declaration, not a
 * duplicate the container will refuse. Runs before the container exists, so it
 * reads the annotation as static metadata and never instantiates a module of
 * its own: providers come from the {@link ServiceLoader} iteration.
 */
final class ModuleDiscovery {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleDiscovery.class);

    private ModuleDiscovery() {
    }

    /**
     * The composition plus the discovered modules not already declared, in that
     * order. The whole iteration is guarded: a broken provider surfaces from
     * {@code hasNext()/next()}, not from the loop body, and must get the same
     * classloader context.
     */
    static List<ModuleEx> fill(List<ModuleEx> modules, ClassLoader loader) {
        Set<Class<?>> known = declaredClasses(modules);
        List<ModuleEx> discovered = new ArrayList<>();
        try {
            for (ModuleEx module : ServiceLoader.load(ModuleEx.class, loader)) {
                if (!known.add(module.getClass())) {
                    LOG.debug("Ignoring discovered module already declared: {}",
                        module.getClass().getSimpleName());
                    continue;
                }
                discovered.add(module);
            }
        } catch (ServiceConfigurationError ex) {
            throw new IllegalStateException(
                "Failed to load a ServiceLoader-discovered ModuleEx provider (classloader: "
                    + loader + ")", ex);
        }
        if (discovered.isEmpty()) {
            return modules;
        }
        List<ModuleEx> all = new ArrayList<>(modules);
        all.addAll(discovered);
        return all;
    }

    /**
     * Every module class these declarations reach, bundles expanded — what discovery must not add a
     * second time. The container refuses a class declared twice, so a bundle whose {@code @SubModule}
     * lists an SPI provider would otherwise fail at startup instead of leaving the author's
     * declaration in place. Expansion mirrors the tree's own (freeway-ioc,
     * {@code ModuleNode.declaredSubModules}): read the annotation, never call a module method, and
     * stop at a class already seen so a cycle terminates here.
     */
    private static Set<Class<?>> declaredClasses(List<ModuleEx> modules) {
        Set<Class<?>> known = new HashSet<>();
        for (ModuleEx module : modules) {
            collectBundles(module.getClass(), known);
        }
        return known;
    }

    private static void collectBundles(Class<? extends ModuleEx> type, Set<Class<?>> known) {
        if (!known.add(type)) {
            return;
        }
        SubModule bundle = type.getAnnotation(SubModule.class);
        if (bundle == null) {
            return;
        }
        for (Class<? extends ModuleEx> submodule : bundle.value()) {
            collectBundles(submodule, known);
        }
    }
}
