package com.jujin.freeway.ioc.symbol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The comma-list value type: the encoding (HTTP header convention — comma,
 * trimmed, empty entries dropped) lives in {@link SymbolSpec}, not in
 * per-module utility classes.
 */
class SymbolSpecListTest {

    @Test
    void listSpecDecodesCommaSeparatedValues() {
        SymbolSpec<List<String>> peers = SymbolSpec.list("k", List.of());
        assertEquals(List.of("a:1", "b:2"), peers.parse("a:1, b:2"));
        assertEquals(List.of(), peers.parse(" , ,"),
            "blank entries are dropped — ' , ,' means no entries");
        assertEquals(List.of(), peers.parse(""), "an empty value means unset");
        assertNull(SymbolSpec.list("k", null).parse(null),
            "absent falls back to the declared default (a null default = unset)");
    }

    @Test
    void defaultIsCopiedDefensivelyAndServedForAbsentKeys() {
        SymbolSpec<List<String>> spec = SymbolSpec.list("k", List.of("x"));
        List<String> resolved = spec.parse(null);
        assertEquals(List.of("x"), resolved);
        assertThrows(UnsupportedOperationException.class,
            () -> spec.parse(null).add("y"),
            "the served list is immutable (List.copyOf), not a shared mutable one");
    }

    @Test
    void splitListIsTheSingleHomeOfTheEncoding() {
        assertEquals(List.of(), SymbolSpec.splitList(null));
        assertEquals(List.of(), SymbolSpec.splitList("   "));
        assertEquals(List.of("a"), SymbolSpec.splitList("a,,"));
        assertEquals(List.of("1", "2"), SymbolSpec.splitList(" 1 ,2"));
    }
}
