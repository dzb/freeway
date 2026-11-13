package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.ModuleDeclaration;
import com.jujin.freeway.ioc.ModuleEx;
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
    static List<ModuleDeclaration> fill(
            List<ModuleDeclaration> declarations, ClassLoader loader) {
        Set<Class<?>> known = declaredClasses(declarations);
        List<ModuleDeclaration> discovered = new ArrayList<>();
        try {
            for (ModuleEx module : ServiceLoader.load(ModuleEx.class, loader)) {
                ModuleDeclaration declaration = ModuleDeclaration.of(module);
                if (!known.add(declaration.type())) {
                    LOG.debug("Ignoring discovered module already declared: {}",
                        declaration.name());
                    continue;
                }
                discovered.add(declaration);
            }
        } catch (ServiceConfigurationError ex) {
            throw new IllegalStateException(
                "Failed to load a ServiceLoader-discovered ModuleEx provider (classloader: "
                    + loader + ")", ex);
        }
        if (discovered.isEmpty()) {
            return declarations;
        }
        // Say what the classpath added: composition is a fact about the run,
        // and the startup tree alone cannot tell a discovered module from a
        // declared one (they render the same). One line, named classes.
        LOG.info(
            "Discovered {} module(s) on the classpath (autoDiscovery): {}",
            discovered.size(),
            discovered.stream().map(declaration -> declaration.type().getName()).toList());
        List<ModuleDeclaration> all = new ArrayList<>(declarations);
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
    private static Set<Class<?>> declaredClasses(List<ModuleDeclaration> declarations) {
        Set<Class<?>> known = new HashSet<>();
        for (ModuleDeclaration declaration : declarations) {
            collectBundles(declaration.type(), known);
        }
        return known;
    }

    private static void collectBundles(Class<? extends ModuleEx> type, Set<Class<?>> known) {
        ModuleDeclaration declaration = ModuleDeclaration.of(type);
        // A nameless declaration never equals an SPI provider class — the same
        // exclusion the composition tree applies.
        if (!declaration.hasStableType() || !known.add(type)) {
            return;
        }
        for (Class<? extends ModuleEx> submodule : declaration.submodules()) {
            collectBundles(submodule, known);
        }
    }
}
