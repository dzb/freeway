package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.annotation.Symbol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An injection annotation on a <em>method</em> parameter cannot be honored,
 * and must say so when the declaring class is injected rather than being
 * silently dropped.
 *
 * <p>It compiles, because {@code PARAMETER} is in {@code @Inject}'s target and
 * has to be — the constructor parameters that do work need it. Nothing in the
 * container reads a method parameter, and nothing could: there is no bytecode
 * weaving to intercept a call to a concrete class, and
 * {@code MethodInvocation.args()} is a defensive copy, so an advisor cannot
 * substitute arguments either. Left alone, the parameter arrives as whatever
 * the caller passed — {@code null} when the caller trusted the container —
 * and the failure surfaces later as a {@code NullPointerException} that names
 * neither the annotation nor the method that declared it.
 *
 * <p>The narrow alternative (dropping {@code PARAMETER} from the target) is
 * not available: it would forbid annotating constructor parameters, which is
 * the one place the annotation works. Hence the injection-time check, which
 * covers the declaring class, its superclasses and every interface it
 * implements.
 */
class MethodParameterInjectionTest {

    interface Greeter {
        String greet();
    }

    static class GreeterImpl implements Greeter {
        @Override
        public String greet() {
            return "hello";
        }
    }

    /**
     * The check runs before the constructor, so it reaches the caller wrapped
     * by the realize path's {@code "Unable to construct …"} frame — the same
     * shape every other configuration error in the framework has. The
     * actionable text is the root cause.
     */
    private static String rootCauseMessage(Throwable thrown) {
        Throwable at = thrown;
        while (at.getCause() != null) {
            at = at.getCause();
        }
        return at.getClass().getSimpleName() + ": " + at.getMessage();
    }

    @Test
    void injectOnAMethodParameterFailsWhenInjectedNamingTheMethod() {
        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> Freeway.create(binder -> {
                binder.bind(Greeter.class).to(GreeterImpl.class);
                binder.bind(Caller.class).to(Caller.class);
            }).get(Caller.class));

        String message = rootCauseMessage(thrown);
        assertTrue(message.contains("IllegalStateException"), message);
        assertTrue(message.contains("method parameter cannot be honored"), message);
        assertTrue(message.contains("Caller.call"), "must name the method: " + message);
        assertTrue(message.contains("@Inject"), "must name the annotation: " + message);
        assertTrue(message.contains("constructor parameter"),
            "must name the way out: " + message);
    }

    @Test
    void symbolOnAMethodParameterIsRejectedToo() {
        // @Symbol has the same target shape and the same blind spot — the
        // resolution path reads it on constructor parameters and fields only.
        String message = rootCauseMessage(assertThrows(RuntimeException.class,
            () -> Freeway.create(binder -> {
                binder.bind(SymbolCaller.class).to(SymbolCaller.class);
            }).get(SymbolCaller.class)));

        assertTrue(message.contains("@Symbol"), message);
        assertTrue(message.contains("SymbolCaller.fetch"), message);
    }

    /**
     * Rejection happens before the constructor, so a class whose constructor
     * has side effects — opening a connection, starting a thread — never gets to
     * perform them for an instance the container is about to refuse to build.
     * The check used to run inside field injection, i.e. after the constructor,
     * where the damage was already done.
     */
    @Test
    void theConstructorIsNotRunForAClassThatWillBeRejected() {
        SideEffectingCaller.CONSTRUCTED.set(0);

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> Freeway.create(binder -> {
                binder.bind(SideEffectingCaller.class).to(SideEffectingCaller.class);
            }).get(SideEffectingCaller.class));

        assertEquals(0, SideEffectingCaller.CONSTRUCTED.get(),
            "the class is not injectable at all, so its constructor must not run: "
                + rootCauseMessage(thrown));
    }

    public static final class SideEffectingCaller {
        static final java.util.concurrent.atomic.AtomicInteger CONSTRUCTED =
            new java.util.concurrent.atomic.AtomicInteger();

        SideEffectingCaller() {
            CONSTRUCTED.incrementAndGet();
        }

        void call(@Inject String ignored) {
        }
    }

    @Test
    void aStrayAnnotationInheritedFromASuperclassIsAlsoRejected() {
        // The check walks the app-class hierarchy, so a base class cannot
        // smuggle the annotation past a subclass that is bound.
        String message = rootCauseMessage(assertThrows(RuntimeException.class,
            () -> Freeway.create(binder -> {
                binder.bind(SubCaller.class).to(SubCaller.class);
            }).get(SubCaller.class)));

        assertTrue(message.contains("BaseCaller"),
            "must name the declaring class, not the bound one: " + message);
    }

    @Test
    void aStrayAnnotationOnAnInterfaceMethodIsAlsoRejected() {
        // An API interface is the likeliest place to write this, and a method's
        // annotations are not inherited by its implementation — so a walk of the
        // class chain alone would never see it, and the promise would be false
        // exactly where it matters most. The implementation is injected
        // directly: binding the interface would hand back a proxy and defer
        // this to the first call, which is a different (also covered) path.
        String message = rootCauseMessage(assertThrows(RuntimeException.class,
            () -> Freeway.create(binder ->
                binder.bind(ApiImpl.class).to(ApiImpl.class)
            ).get(ApiImpl.class)));

        assertTrue(message.contains("AnnotatedApi.fetch"),
            "must name the interface declaring the annotated parameter: " + message);
        assertTrue(message.contains("@Inject"), message);
    }

    @Test
    void thePlacesThatDoWorkAreUnaffected() {
        // The check must not fire on the two shapes it is silent about:
        // a constructor parameter and an @Inject field, in one class.
        try (Container container = Freeway.create(binder -> {
            binder.bind(Greeter.class).to(GreeterImpl.class);
            binder.bind(Proper.class).to(Proper.class);
        })) {
            Proper proper = container.get(Proper.class);
            assertEquals("hello", proper.viaConstructor());
            assertEquals("hello", proper.viaField());
        }
    }

    @Test
    void createAlsoRejectsIt() {
        // Container.create is `new` plus dependency resolution, and it runs
        // the same field injection — so the same honest answer applies there.
        try (Container container = Freeway.create(binder ->
                binder.bind(Greeter.class).to(GreeterImpl.class))) {
            String message = rootCauseMessage(assertThrows(RuntimeException.class,
                () -> container.create(Caller.class)));
            assertTrue(message.contains("Caller.call"), message);
        }
    }

    static class Caller {
        String call(@Inject Greeter greeter) {
            return greeter == null ? "<null>" : greeter.greet();
        }
    }

    interface AnnotatedApi {
        String fetch(@Inject Greeter greeter);
    }

    static class ApiImpl implements AnnotatedApi {
        @Override
        public String fetch(Greeter greeter) {
            return greeter == null ? "<null>" : greeter.greet();
        }
    }

    static class SymbolCaller {
        String fetch(@Symbol("greeting") String value) {
            return value;
        }
    }

    static class BaseCaller {
        String inherited(@Inject Greeter greeter) {
            return greeter == null ? "<null>" : greeter.greet();
        }
    }

    static class SubCaller extends BaseCaller {
    }

    static class Proper {
        private final Greeter constructorInjected;
        @Inject
        private Greeter fieldInjected;

        Proper(@Inject Greeter greeter) {
            this.constructorInjected = greeter;
        }

        String viaConstructor() {
            return constructorInjected.greet();
        }

        String viaField() {
            return fieldInjected.greet();
        }
    }

    /**
     * The rejection above is about <em>where</em> the annotation sits, not
     * about the service being unresolvable — the same class with the
     * dependency on a constructor parameter and a field starts fine.
     */
}
