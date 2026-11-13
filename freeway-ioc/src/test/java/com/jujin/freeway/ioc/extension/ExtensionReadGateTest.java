package com.jujin.freeway.ioc.extension;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A container-created store refuses reads until every declared contribution
 * has landed. The standalone one-arg constructor stays readable from the
 * start, which is what extension unit tests rely on.
 */
class ExtensionReadGateTest {

    record Feature(String name) {
    }

    @Test
    void incompleteStoreRefusesEveryRead() {
        AtomicBoolean complete = new AtomicBoolean(false);
        ExtensionStore<Feature> ext = new ExtensionStore<>(Feature.class, complete::get);
        ext.add("one", new Feature("one"));

        assertThrows(IllegalStateException.class, ext::all);
        assertThrows(IllegalStateException.class, ext::asMap);
        assertThrows(IllegalStateException.class, ext::validateOrdering);

        complete.set(true);
        assertEquals(1, ext.all().size());
        assertEquals(1, ext.asMap().size());
        ext.validateOrdering();
    }

    @Test
    void standaloneStoreIsCompleteByDefault() {
        ExtensionStore<Feature> ext = new ExtensionStore<>(Feature.class);
        ext.add("one", new Feature("one"));
        assertEquals(1, ext.all().size());
    }
}
