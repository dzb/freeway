package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.annotation.*;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.UnknownSymbolException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.jujin.freeway.ioc.FreewayTestSupport.*;

/** SymbolExpansionTest: split from the former FreewayTest monolith (behavior-preserving move). */
class SymbolExpansionTest {
    @BeforeEach
    void captureSystemProperties() { FreewayTestSupport.capture(); }

    @AfterEach
    void restoreSystemProperties() { FreewayTestSupport.restore(); }

    @Test
    void rejectsUnknownSymbol() {
        Container container = Freeway.create();
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.create(UnknownSymbolService.class));
        // Structured sentinel: a top-level miss is detectable by type, not
        // message text — resolve(name, default) depends on this distinction.
        assertEquals(UnknownSymbolException.class, ex.getCause().getClass());
    }

    @Test
    void expandEscapesDollarBrace() {
        Container container = Freeway.create();
        SymbolSource symbols = container.get(SymbolSource.class);

        assertEquals("a ${b} c", symbols.expand("a \\${b} c"),
            "\\${ must emit a literal ${");
        assertEquals("${not-a-symbol}", symbols.expand("\\${not-a-symbol}"),
            "an escaped expression must not be resolved");

        // An even backslash run leaves the expression active — the backslashes
        // are literal and ${...} still resolves.
        System.setProperty(NAME_KEY, "resolved");
        assertEquals("a \\\\resolved", symbols.expand("a \\\\${" + NAME_KEY + "}"),
            "an even backslash run stays literal and the expression still resolves");
        System.clearProperty(NAME_KEY);
        container.close();
    }

    @Test
    void expandEscapesDollarBraceInsideResolvedValue() {
        System.setProperty(APP_NAME_KEY, "price is \\${total}");
        Container container = Freeway.create();
        SymbolSource symbols = container.get(SymbolSource.class);

        assertEquals("price is ${total}", symbols.expand("${" + APP_NAME_KEY + "}"),
            "escaped ${ in a resolved value must not be expanded again");
        System.clearProperty(APP_NAME_KEY);
        container.close();
    }

    @Test
    void nestedDefaultValueExpands() {
        // Regression: the closing-brace search stopped at the FIRST '}', so
        // ${a:${b}} parsed the default as "${b" and threw "Unclosed symbol
        // expression" despite the documented nested-default support.
        System.setProperty(NEST_KEY, "nested-value");
        try {
            Container container = Freeway.create();
            SymbolSource symbols = container.get(SymbolSource.class);

            assertEquals("nested-value",
                symbols.expand("${missing:${" + NEST_KEY + "}}"),
                "a nested ${...} inside a default value must expand");
            container.close();
        } finally {
            System.clearProperty(NEST_KEY);
        }
    }

    @Test
    void dashDefaultSeparatorStripsLeadingDash() {
        // Regression: the javadoc advertises ${name:-default}, but the parser
        // took everything after ':' as the default, so ${port:-8080} produced
        // "-8080". A single leading dash after ':' must be dropped (shell
        // semantics); ${name:default} stays verbatim.
        Container container = Freeway.create();
        try {
            SymbolSource symbols = container.get(SymbolSource.class);

            assertEquals("8080", symbols.expand("${freeway.test.missing.port:-8080}"),
                "${name:-default} must strip the leading dash");
            assertEquals("8080", symbols.expand("${freeway.test.missing.port:8080}"),
                "${name:default} must keep the default verbatim");
            assertEquals("-8080", symbols.expand("${freeway.test.missing.port:--8080}"),
                "only ONE leading dash is stripped");
            assertEquals("", symbols.expand("${freeway.test.missing.name:-}"),
                "${name:-} must yield the empty string");
            assertEquals("", symbols.expand("${freeway.test.missing.name:}"),
                "${name:} must yield the empty string");
        } finally {
            container.close();
        }
    }

    @Test
    void dashDefaultSeparatorDoesNotAffectResolvedSymbol() {
        // A value present in the source wins regardless of the default syntax —
        // the ":-" stripping only applies when the fallback is actually used.
        System.setProperty(PORT_KEY, "1234");
        Container container = Freeway.create();
        try {
            SymbolSource symbols = container.get(SymbolSource.class);
            assertEquals("1234", symbols.expand("${" + PORT_KEY + ":-8080}"),
                "a resolved symbol must not have its value dash-stripped");
        } finally {
            System.clearProperty(PORT_KEY);
            container.close();
        }
    }

    @Test
    void configuredValueCoercionErrorIncludesContext() {
        System.setProperty(APP_NAME_KEY, "not-a-list");
        Container container = Freeway.create();
        try {
            RuntimeException ex = assertThrows(RuntimeException.class,
                () -> container.create(UncoercibleListService.class));
            Throwable cause = ex.getCause();
            assertTrue(cause != null && cause.getMessage() != null
                    && cause.getMessage().contains("Cannot coerce configured value"),
                "coercion failure should include config context, got: "
                    + (cause == null ? null : cause.getMessage()));
        } finally {
            System.clearProperty(APP_NAME_KEY);
        }
        container.close();
    }

    @Test
    void rejectsUnclosedSymbolExpression() {
        Container container = Freeway.create();
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.create(UnclosedSymbolService.class));
        assertEquals(IllegalArgumentException.class, ex.getCause().getClass());
    }

    @Test
    void symbolExpansionDepthLimitFailsLoudly() {
        // A self-referencing SymbolProvider must hit the depth guard with a
        // clear error, not a StackOverflowError.
        Container container = Freeway.create(binder ->
            binder.contribute(SymbolProvider.class).add(name -> "${" + name + "}")
        );
        SymbolSource symbols = container.get(SymbolSource.class);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> symbols.resolve("loop"));
        assertTrue(ex.getMessage().contains("depth"),
            "expected depth-limit error, got: " + ex.getMessage());
    }

    @Test
    void literalBracesInsideDefaultAreKept() {
        // Regression: the closing-brace search depth-tracked only '${', so a
        // literal '{' inside a default ended the expression early —
        // ${a:x{y}z} parsed the default as "x{y" and leaked "z}" verbatim.
        // Every '{' now counts, so balanced literal braces stay whole.
        Container container = Freeway.create();
        try {
            SymbolSource symbols = container.get(SymbolSource.class);
            assertEquals("x{y}z", symbols.expand("${freeway.test.missing.template:x{y}z}"),
                "balanced literal braces inside a default must be kept whole");
        } finally {
            container.close();
        }
    }

    @Test
    void whitespaceAroundSymbolNameIsIgnored() {
        // ${ port } is formatting, not identity: the name is trimmed before
        // lookup. Defaults keep their verbatim value.
        System.setProperty(PORT_KEY, "1234");
        Container container = Freeway.create();
        try {
            SymbolSource symbols = container.get(SymbolSource.class);
            assertEquals("1234", symbols.expand("${ " + PORT_KEY + " }"),
                "formatting whitespace around the symbol name must not change lookup");
        } finally {
            System.clearProperty(PORT_KEY);
            container.close();
        }
    }
}
