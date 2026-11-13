package com.jujin.freeway.ioc;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDescriptionTest {

    interface Widget {
        String value();
    }

    static final class Gadget implements Widget {
        @Override public String value() { return "ok"; }
    }

    record Token(String value) {}

    static final class AppModule implements ModuleEx {
        final AtomicInteger realizations;

        AppModule(AtomicInteger realizations) {
            this.realizations = realizations;
        }

        @Override
        public void bind(Binder binder) {
            binder.bind(Widget.class)
                .to(container -> {
                    realizations.incrementAndGet();
                    return new Gadget();
                })
                .id("widget");
            binder.contribute(Token.class)
                .add("notify", new Token("notify"))
                .after("cache");
            binder.contribute(Token.class).add("cache", new Token("cache"));
        }
    }

    @Test
    void describeIsMetadataOnlyAndDoesNotRealizeLazyServices() {
        AtomicInteger realizations = new AtomicInteger();
        try (Container container = Freeway.create(
                "orders", new AppModule(realizations))) {
            RuntimeDescription description = container.describe();

            assertEquals("orders", description.application());
            assertEquals(0, realizations.get(),
                "describing the container must not realize a lazy provider");

            RuntimeDescription.BindingDescription widget = description.bindings().stream()
                .filter(binding -> binding.type().equals(Widget.class.getName()))
                .findFirst().orElseThrow();
            assertEquals("widget", widget.id());
            assertEquals(Scope.SINGLETON, widget.scope());
            assertFalse(widget.primary());
            assertEquals(0, realizations.get(),
                "binding metadata is read from the sealed binding record");

            RuntimeDescription.ModuleDescription module = description.modules().stream()
                .filter(node -> node.type().equals(AppModule.class.getName()))
                .findFirst().orElseThrow();
            assertEquals("AppModule", module.name());

            RuntimeDescription.ExtensionDescription tokens = description.extensions().stream()
                .filter(extension -> extension.entryType().equals(Token.class.getName()))
                .findFirst().orElseThrow();
            assertEquals(List.of("cache", "notify"),
                tokens.contributions().stream().map(
                    RuntimeDescription.ContributionDescription::id).toList(),
                "the description carries resolved contribution order");
            RuntimeDescription.ContributionDescription notify =
                tokens.contributions().stream()
                    .filter(contribution -> "notify".equals(contribution.id()))
                    .findFirst().orElseThrow();
            assertEquals(List.of("cache"), notify.after());
            assertEquals(Token.class.getName(), notify.valueType());

            Widget service = container.get(Widget.class);
            assertEquals("ok", service.value());
            assertEquals(1, realizations.get(),
                "the real service is still lazy after inspection");
        }
    }

    @Test
    void descriptionContainsBuiltinsWithoutExposingTheirValues() {
        try (Container container = Freeway.create()) {
            RuntimeDescription description = container.describe();

            assertNotNull(description);
            assertTrue(description.bindings().stream()
                .anyMatch(binding -> binding.type()
                    .equals("com.jujin.freeway.ioc.event.EventBus")),
                "built-in services are visible as metadata");
        }
    }
}
