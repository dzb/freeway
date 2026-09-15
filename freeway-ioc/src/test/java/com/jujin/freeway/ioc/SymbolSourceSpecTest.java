package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.coercion.CoerceRule;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The container's {@link SymbolSource} parses {@link SymbolSpec}s through the
 * container's own Coercer: the chain is assembled with that instance, so a
 * contributed {@code CoerceRule} reaches the one-step {@code resolve(spec)} the
 * same way it reaches {@code @Value} injection.
 */
class SymbolSourceSpecTest {

    private static final String KEY = "freeway.test.container-spec";

    @AfterEach
    void clearProperty() {
        System.clearProperty(KEY);
    }

    @Test
    void parsesCoercerBackedSpecsWithoutTheTwoStepIdiom() {
        System.setProperty(KEY, "PT30S");
        Container container = Freeway.create(binder -> { });
        try {
            SymbolSource symbols = container.get(SymbolSource.class);

            assertEquals(Duration.ofSeconds(30),
                symbols.resolve(SymbolSpec.of(KEY, Duration.class, Duration.ZERO)));
            assertEquals(Duration.ZERO,
                symbols.resolve(SymbolSpec.of(KEY + ".absent", Duration.class, Duration.ZERO)),
                "an absent key resolves to the spec's default");
        } finally {
            container.close();
        }
    }

    @Test
    void contributedCoerceRuleAppliesToSpecParsing() {
        System.setProperty(KEY, "127.0.0.1:8080");
        Container container = Freeway.create(binder ->
            binder.contribute(CoerceRule.class).add(
                new CoerceRule<>(String.class, HostPort.class, HostPort::parse)));
        try {
            assertEquals(new HostPort("127.0.0.1", 8080),
                container.get(SymbolSource.class).resolve(
                    SymbolSpec.of(KEY, HostPort.class, HostPort.NONE)));
        } finally {
            container.close();
        }
    }

    /** Parsing target with no built-in coercion — only the contributed rule. */
    private record HostPort(String host, int port) {
        static final HostPort NONE = new HostPort("", 0);

        static HostPort parse(String value) {
            int colon = value.indexOf(':');
            return new HostPort(
                value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
        }
    }
}
