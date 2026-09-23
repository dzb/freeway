package com.jujin.freeway.ioc.symbol;

import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SymbolSource#of} — the chain a container-less caller assembles, the
 * standalone adapter recipe being
 * {@code SymbolSource.of(new CoercerDefault(), SymbolProvider.systemProperties())}.
 *
 * <p>It is the same mechanism the container builds, so expansion, typing and
 * tier precedence cannot differ between the two construction paths.
 */
class SymbolSourceOfTest {

    private static final String KEY = "freeway.test.symbol-of";

    @AfterEach
    void clearProperties() {
        for (String name : List.of(KEY, KEY + ".host", KEY + ".port")) {
            System.clearProperty(name);
        }
    }

    private static SymbolSource standalone() {
        return SymbolSource.of(new CoercerDefault(), SymbolProvider.systemProperties());
    }

    @Test
    void resolvesFromItsTiers() {
        System.setProperty(KEY, "value");
        SymbolSource symbols = standalone();

        assertEquals("value", symbols.resolve(KEY));
        assertEquals("value", symbols.resolve(KEY, "fallback"));
    }

    @Test
    void expandsReferencesLikeTheContainersChain() {
        System.setProperty(KEY + ".host", "127.0.0.1");
        System.setProperty(KEY + ".port", "8080");
        SymbolSource symbols = standalone();

        // Expansion is the chain's job on every construction path — a standalone
        // source is a chain of one tier, not a lookup with expansion switched off.
        assertEquals("http://127.0.0.1:8080/",
            symbols.expand("http://${" + KEY + ".host}:${" + KEY + ".port}/"));
    }

    @Test
    void absentSymbolThrowsOnStrictResolveAndFallsBackOnLenientResolve() {
        SymbolSource symbols = standalone();

        UnknownSymbolException error =
            assertThrows(UnknownSymbolException.class, () -> symbols.resolve(KEY));
        // The miss names the key first, then the sources consulted —
        // standalone() here is a chain of one (the system-properties tier).
        assertTrue(error.getMessage().startsWith("Unknown symbol: " + KEY),
            error.getMessage());
        assertTrue(error.getMessage().contains("configured sources (orders 5)"),
            error.getMessage());
        assertEquals("fallback", symbols.resolve(KEY, "fallback"));
        assertNull(symbols.resolve(KEY, null));
    }

    @Test
    void parsesSpecsThroughTheCoercerItWasGiven() {
        // Coercer-backed spec: no per-key parser, typed by the chain's Coercer.
        System.setProperty(KEY, "42");
        assertEquals(42, standalone().resolve(SymbolSpec.of(KEY, Integer.class, 0)));
        assertEquals(0, standalone().resolve(SymbolSpec.of(KEY + ".absent", Integer.class, 0)));

        // A rule the caller registered is honored: the chain parses with the
        // instance it was handed, which is what lets the container pass its own.
        System.setProperty(KEY, "127.0.0.1:8080");
        CoercerDefault coercer = new CoercerDefault().register(
            new CoerceRule<>(String.class, HostPort.class, HostPort::parse));
        SymbolSource withRule =
            SymbolSource.of(coercer, SymbolProvider.systemProperties());

        assertEquals(new HostPort("127.0.0.1", 8080),
            withRule.resolve(SymbolSpec.of(KEY, HostPort.class, HostPort.NONE)));
    }

    @Test
    void ordersTiersByDeclaredOrderOverALiveContributedView() {
        System.setProperty(KEY, "from-sys-props");
        // The second of(...) overload takes the contributed view live — the
        // same channel the container uses (its extension store). Swapping the
        // view for a new list is what "a contribution landed" looks like from
        // out here: the next lookup re-merges.
        SymbolProvider files =
            SymbolProvider.of(() -> Map.of(KEY, "from-files"), SymbolProvider.TIER_FILES);
        var view = new AtomicReference<>(
            List.of(files));
        SymbolSource symbols = SymbolSource.of(
            new CoercerDefault(),
            view::get,
            SymbolProvider.systemProperties());

        assertEquals("from-sys-props", symbols.resolve(KEY),
            "the -D tier outranks a later tier regardless of assembly order");

        view.set(List.of(files,
            SymbolProvider.of(() -> Map.of(KEY, "from-cli"), SymbolProvider.TIER_CLI)));
        assertEquals("from-cli", symbols.resolve(KEY),
            "a tier arriving through the view still wins by declared order");
    }

    /** Standalone-chain parsing target: no built-in coercion exists for it. */
    private record HostPort(String host, int port) {
        static final HostPort NONE = new HostPort("", 0);

        static HostPort parse(String value) {
            int colon = value.indexOf(':');
            return new HostPort(
                value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
        }
    }
}
