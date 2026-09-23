package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A module may bind its own primary {@link SymbolSource}. Contributions reach
 * the chain through one channel only — {@code contribute(SymbolProvider.class)}
 * into the extension store — and there is no register/replay step. A
 * replacement therefore takes the same view itself, in the factory that
 * builds it: {@code c.extension(SymbolProvider.class)} (the pattern
 * {@link SymbolSource#of} documents). Boot contributes the application's
 * whole cascade (CLI, environment, config files) as contributions, so a
 * replacement that ignores the view serves only its own tiers.
 */
class SymbolSourceReplacementTest {

    /** A replacement that reads the contributed view live — the documented
     *  pattern: consult it on every lookup, fall back to its own delegate. */
    private static final class ViewSource implements SymbolSource {

        private final Extension<SymbolProvider> contributed;
        private final SymbolSource delegate =
            SymbolSource.of(new CoercerDefault(), SymbolProvider.systemProperties());

        ViewSource(Extension<SymbolProvider> contributed) {
            this.contributed = contributed;
        }

        @Override
        public String resolve(String name) {
            for (SymbolProvider provider : contributed.all()) {
                String value = provider.lookup(name);
                if (value != null) {
                    return value;
                }
            }
            return delegate.resolve(name);
        }

        @Override
        public String expand(String input) {
            return delegate.expand(input);
        }
    }

    /** A class contribution: drains before the container is built, no facade. */
    public static final class ClassTier implements SymbolProvider {
        @Override
        public String lookup(String name) {
            return "class.tier".equals(name) ? "class-tier" : null;
        }

        @Override
        public int order() {
            return 7;
        }
    }

    @Test
    void replacedSourceSeesInstanceContributionThroughTheView() {
        Container container = Freeway.create(binder -> {
            binder.bind(SymbolSource.class)
                .to(c -> new ViewSource(c.extension(SymbolProvider.class)))
                .primary();
            binder.contribute(SymbolProvider.class)
                .add("test-tier", SymbolProvider.of(() -> Map.of("probe", "value"), 7));
        });

        assertEquals("value", container.get(SymbolSource.class).resolve("probe", null),
            "a lookup through the replacement must see the contributed tier");
        container.close();
    }

    @Test
    void replacedSourceSeesClassContributionThroughTheView() {
        // Class contributions materialize in the drain (config layer first)
        // and land in the same store the view reads — no declaration-time
        // facade, no post-load replay into the winner.
        Container container = Freeway.create(binder -> {
            binder.bind(SymbolSource.class)
                .to(c -> new ViewSource(c.extension(SymbolProvider.class)))
                .primary();
            binder.contribute(SymbolProvider.class).add(ClassTier.class);
        });

        assertEquals("class-tier", container.get(SymbolSource.class).resolve("class.tier", null),
            "the replacement must see class-contributed providers from the same view");
        container.close();
    }
}
