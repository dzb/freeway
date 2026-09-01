package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SymbolProvider} precedence is declared via {@code order()}, never
 * via module install order: reordering the contributing modules must not
 * change which provider wins a key.
 */
class SymbolProviderOrderTest {

    private static SymbolProvider provider(String name, String value, int order) {
        return new SymbolProvider() {
            @Override
            public String lookup(String n) {
                return name.equals(n) ? value : null;
            }

            @Override
            public int order() {
                return order;
            }
        };
    }

    @Test
    void resolutionFollowsDeclaredOrderNotInstallOrder() {
        SymbolProvider secret = provider("k", "from-secret", 10);
        SymbolProvider config = provider("k", "from-config", 20);

        // Reversed install: the higher-precedence provider is contributed LAST.
        assertResolves("from-secret", config, secret);
        assertResolves("from-secret", secret, config);
    }

    @Test
    void equalOrderKeepsContributionOrderAsTiebreak() {
        SymbolProvider first = provider("k", "first", 0);
        SymbolProvider second = provider("k", "second", 0);

        assertResolves("first", first, second);
        assertResolves("second", second, first);
    }

    @Test
    void undeclaredOrderResolvesAfterDeclaredTiers() {
        SymbolProvider boot = provider("k", "from-boot", 0);
        // Lambda-style provider: no declared order — resolves last even when
        // contributed before the declared tier.
        SymbolProvider custom = provider("k", "from-custom", Integer.MAX_VALUE);

        assertResolves("from-boot", custom, boot);
    }

    private static void assertResolves(
        String expected, SymbolProvider first, SymbolProvider second
    ) {
        Container container = Freeway.create(binder -> {
            binder.contribute(SymbolProvider.class).add(first);
            binder.contribute(SymbolProvider.class).add(second);
        });
        try {
            assertEquals(expected, container.get(SymbolSource.class).resolve("k"));
        } finally {
            container.close();
        }
    }
}
