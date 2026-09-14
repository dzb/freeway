package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.UnknownSymbolException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A module may bind its own primary {@link SymbolSource}. The container must then
 * hand that instance every {@link SymbolProvider} the modules contribute — boot
 * contributes the application's whole cascade (CLI, environment, config files)
 * that way, so wiring the contributions into the built-in instance instead means
 * the replacement never sees them and the loss is silent.
 */
class SymbolSourceReplacementTest {

    /** A replacement that records what it was asked to accept; a lookup consults
     *  the accepted providers first and falls back to the standalone source, so
     *  the test can see whether the container handed them over. */
    private static final class RecordingSource implements SymbolSource {

        final List<SymbolProvider> accepted = new CopyOnWriteArrayList<>();
        private final SymbolSource delegate = SymbolSource.systemProperties();

        @Override
        public void register(SymbolProvider provider) {
            accepted.add(provider);
        }

        @Override
        public String resolve(String name) {
            for (SymbolProvider provider : accepted) {
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

    @Test
    void replacedSourceReceivesEveryContributedProvider() {
        RecordingSource replacement = new RecordingSource();

        Container container = Freeway.create(binder -> {
                binder.bind(SymbolSource.class).to(c -> replacement).primary();
            binder.contribute(SymbolProvider.class)
                .add("test-tier", SymbolProvider.of(() -> Map.of("probe", "value"), 7));
        });

        assertEquals("value", container.get(SymbolSource.class).resolve("probe", null),
            "a lookup through the container's SymbolSource must see the contributed tier");
        assertEquals(1, replacement.accepted.size(),
            "the container must register contributions into the resolved source,"
                + " not only into its own built-in instance");
    }

    @Test
    void replacementThatCannotTakeContributionsFailsAtStartup() {
        // The interface's default register() throws: a replacement that cannot
        // take part in the contribution chain would otherwise drop the config
        // cascade in silence, and the failure would surface much later as
        // "my config file is ignored".
        SymbolSource rigid = new SymbolSource() {
            @Override
            public String resolve(String name) {
                throw new UnknownSymbolException(name);
            }

            @Override
            public String expand(String input) {
                return input;
            }
        };

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class, () ->
            Freeway.create(binder -> {
                binder.bind(SymbolSource.class).to(c -> rigid).primary();
                binder.contribute(SymbolProvider.class)
                    .add("test-tier", SymbolProvider.of(() -> Map.of("probe", "value"), 7));
            }));

        assertTrue(ex.getMessage().contains("SymbolProvider"), ex.getMessage());
        assertTrue(ex.getMessage().contains("primary"), ex.getMessage());
    }
}
