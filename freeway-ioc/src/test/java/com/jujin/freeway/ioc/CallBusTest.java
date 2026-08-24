package com.jujin.freeway.ioc;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.commons.scoped.Defer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for {@link CallBus}: typed proxy round-trip, positional
 * arguments, error propagation semantics (runtime as-is, checked wrapped,
 * dead-call), default-method degradation, provider hot swap, Defer-scope
 * draining, timeouts, close-fails-pending, and registration guards.
 */
class CallBusTest {

    // ==================== contract ====================

    interface Greeting {
        String greet(String name);

        String greetTwo(String first, String second);

        void shout(String word);

        String echo(int number);

        default String fallback(String name) {
            return "default:" + name;
        }
    }

    static class RealGreeting implements Greeting {
        @Override public String greet(String name) {
            if (name.equals("boom")) throw new IllegalStateException("kaboom");
            return "hi:" + name;
        }
        @Override public String greetTwo(String a, String b) { return a + "+" + b; }
        @Override public void shout(String word) {
            if (word.equals("boom")) throw new IllegalArgumentException("loud-boom");
        }
        @Override public String echo(int number) { return "n=" + number; }
        @Override public String fallback(String name) { return "real:" + name; }
    }

    interface CheckedApi {
        String read() throws IOException;
    }

    private static CallBus busWithProvider() {
        Container container = Freeway.create();
        CallBus bus = new CallBus(container);
        bus.register("greeting", new RealGreeting());
        return bus;
    }

    // ==================== round-trip ====================

    @Test
    void typedProxyRoundTrip() {
        CallBus bus = busWithProvider();
        Greeting greeting = bus.consumer("greeting", Greeting.class);
        assertEquals("hi:dami", greeting.greet("dami"));
        assertEquals(1, bus.stats().served());
        bus.close();
    }

    @Test
    void multipleArgumentsCarriedPositionally() {
        CallBus bus = busWithProvider();
        Greeting greeting = bus.consumer("greeting", Greeting.class);
        assertEquals("a+b", greeting.greetTwo("a", "b"));
        bus.close();
    }

    @Test
    void primitiveArgumentAndReturn() {
        CallBus bus = busWithProvider();
        Greeting greeting = bus.consumer("greeting", Greeting.class);
        assertEquals("n=42", greeting.echo(42));
        bus.close();
    }

    @Test
    void directCallWithoutProxy() throws Exception {
        CallBus bus = busWithProvider();
        Object result = bus.call("greeting.greet", List.of("direct"))
            .toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        assertEquals("hi:direct", result);
        bus.close();
    }

    // ==================== error propagation ====================

    @Test
    void runtimeExceptionPropagatesAsIs() {
        CallBus bus = busWithProvider();
        Greeting greeting = bus.consumer("greeting", Greeting.class);
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> greeting.greet("boom"));
        assertEquals("kaboom", e.getMessage());
        assertEquals(1, bus.stats().failed());
        bus.close();
    }

    @Test
    void voidMethodStillBlocksAndPropagates() {
        CallBus bus = busWithProvider();
        Greeting greeting = bus.consumer("greeting", Greeting.class);
        greeting.shout("hello"); // no error — returns normally
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> greeting.shout("boom"));
        assertEquals("loud-boom", e.getMessage());
        bus.close();
    }

    @Test
    void checkedExceptionArrivesWrappedInCompletionException()
            throws Exception {
        Container container = Freeway.create();
        CallBus bus = new CallBus(container);
        bus.register("files", new CheckedApi() {
            @Override public String read() throws IOException {
                throw new IOException("disk gone");
            }
        });
        CompletableFuture<Object> stage =
            bus.call("files.read", null).toCompletableFuture();

        CompletionException wrapped = assertThrows(CompletionException.class,
            stage::join);
        assertInstanceOf(IOException.class, wrapped.getCause());
        bus.close();
    }

    @Test
    void callWithNoHandlerFailsWithDeadCall() throws Exception {
        Container container = Freeway.create();
        CallBus bus = new CallBus(container);
        CompletableFuture<Object> stage =
            bus.call("nowhere.here", null).toCompletableFuture();

        // One uniform unwrapping rule: join() wraps, the cause is the failure.
        CompletionException wrapped = assertThrows(CompletionException.class, stage::join);
        DeadCallException dead = assertInstanceOf(DeadCallException.class, wrapped.getCause());
        assertEquals("nowhere.here", dead.topic());
        assertFalse(bus.handles("nowhere.here"));
        assertEquals(1, bus.stats().dead());
        bus.close();
    }

    // ==================== default-method degradation ====================

    @Test
    void missingProviderFallsBackToDefaultMethod() {
        Container container = Freeway.create();
        CallBus bus = new CallBus(container);
        Greeting greeting = bus.consumer("greeting", Greeting.class);
        assertEquals("default:nobody", greeting.fallback("nobody"));
        bus.close();
    }

    @Test
    void defaultMethodNotUsedWhenHandlerExists() {
        CallBus bus = busWithProvider();
        Greeting greeting = bus.consumer("greeting", Greeting.class);
        assertEquals("real:x", greeting.fallback("x"),
            "a registered handler must win over the interface default");
        bus.close();
    }

    @Test
    void nonDefaultMethodWithoutProviderStillFailsDead() {
        Container container = Freeway.create();
        CallBus bus = new CallBus(container);
        Greeting greeting = bus.consumer("greeting", Greeting.class);
        assertThrows(DeadCallException.class, () -> greeting.greet("x"));
        bus.close();
    }

    // ==================== provider lifecycle ====================

    @Test
    void reRegistrationHotSwapsProvider() {
        Container container = Freeway.create();
        CallBus bus = new CallBus(container);
        bus.register("greeting", new RealGreeting());

        Object replacement = new Object() {
            public String greet(String name) { return "swapped:" + name; }
        };
        bus.register("greeting", replacement);

        Greeting greeting = bus.consumer("greeting", Greeting.class);
        assertEquals("swapped:hot", greeting.greet("hot"));
        bus.close();
    }

    @Test
    void unregisterRemovesOnlyOwnHandlers() {
        Container container = Freeway.create();
        CallBus bus = new CallBus(container);
        Object first = new Object() {
            public String greet(String name) { return "first"; }
        };
        bus.register("svc", first);
        assertTrue(bus.handles("svc.greet"));

        bus.unregister("svc", first);
        assertFalse(bus.handles("svc.greet"));

        Object second = new Object() {
            public String greet(String name) { return "second"; }
        };
        bus.register("svc", second);
        bus.unregister("other-mapping", second); // wrong mapping: no-op
        assertTrue(bus.handles("svc.greet"));
        bus.close();
    }

    @Test
    void overloadedMethodsRejectedAtRegister() {
        Container container = Freeway.create();
        CallBus bus = new CallBus(container);
        Object ambiguous = new Object() {
            public String find(String q) { return q; }
            public String find(long limit) { return "many"; }
        };
        assertThrows(IllegalArgumentException.class,
            () -> bus.register("search", ambiguous));
        bus.close();
    }

    @Test
    void consumerRequiresInterface() {
        Container container = Freeway.create();
        CallBus bus = new CallBus(container);
        assertThrows(IllegalArgumentException.class,
            () -> bus.consumer("x", String.class));
        bus.close();
    }

    @Test
    void proxyObjectMethodsBehave() {
        CallBus bus = busWithProvider();
        Greeting greeting = bus.consumer("greeting", Greeting.class);
        assertTrue(greeting.toString().contains("greeting"));
        assertEquals(System.identityHashCode(greeting), greeting.hashCode());
        assertTrue(greeting.equals(greeting));
        assertFalse(greeting.equals(new Object()));
        bus.close();
    }

    // ==================== transactions ====================

    @Test
    void callsExecuteImmediatelyInsideDeferScope() {
        Container container = Freeway.create();
        CallBus bus = new CallBus(container);
        bus.register("tx", new Object() {
            public String pay(String id) { return "paid:" + id; }
        });

        Defer.within(() -> {
            // A call inside a transaction behaves like a method call: it
            // answers NOW. Buffering until commit would deadlock the
            // joining caller.
            assertEquals("paid:o1", bus.call("tx.pay", List.of("o1"))
                .toCompletableFuture().join());
            assertEquals(1, bus.stats().served());
        });

        bus.close();
    }

    @Test
    void timeoutExpiresWhileHandlerBlocks() throws Exception {
        Container container = Freeway.create();
        CallBus bus = new CallBus(container);
        var gate = new java.util.concurrent.CountDownLatch(1);
        bus.register("slow", new Object() {
            public String work(String x) throws InterruptedException {
                gate.await();
                return "done";
            }
        });

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread pump = Thread.ofVirtual().start(() -> {
            try {
                // Inline dispatch blocks this thread inside the handler;
                // the budget still fires from the scheduler side.
                bus.call("slow.work", List.of("x"), Duration.ofMillis(80))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                failure.set(e);
            }
        });

        long deadline = System.currentTimeMillis() + 2000;
        while (failure.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        gate.countDown();
        pump.join(2000);

        assertInstanceOf(ExecutionException.class, failure.get());
        assertInstanceOf(TimeoutException.class, failure.get().getCause(),
            "the deadline must fire even though the handler later completed");
        bus.close();
    }

    // ==================== lifecycle guards ====================

    @Test
    void closeNeverLeavesCallersHanging() throws Exception {
        Container container = Freeway.create();
        CallBus bus = new CallBus(container);
        int callers = 8;
        var entered = new java.util.concurrent.CountDownLatch(callers);
        var gate = new java.util.concurrent.CountDownLatch(1);
        bus.register("later", new Object() {
            public String go(String x) throws InterruptedException {
                entered.countDown();
                gate.await();
                return "ok";
            }
        });

        List<String> outcomes = new java.util.concurrent.CopyOnWriteArrayList<>();
        for (int i = 0; i < callers; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    bus.call("later.go", List.of("x"))
                        .toCompletableFuture()
                        .get(2, TimeUnit.SECONDS);
                    outcomes.add("ok");
                } catch (java.util.concurrent.ExecutionException e) {
                    outcomes.add(e.getCause() instanceof IllegalStateException
                        ? "closed"
                        : "other:" + e.getCause());
                } catch (Exception e) {
                    outcomes.add("timeout-or-interrupted");
                }
            });
        }
        Await.until(2000, () -> entered.getCount() == 0);
        bus.close(); // mid-flight: every waiter must be released
        gate.countDown();

        Await.until(3000, () -> outcomes.size() == callers);
        assertTrue(outcomes.stream().allMatch(o ->
                o.equals("ok") || o.equals("closed")),
            "only success or explicit close-failure are legal: " + outcomes);
    }

    @Test
    void closedBusFailsFast() {
        Container container = Freeway.create();
        CallBus bus = new CallBus(container);
        bus.close();
        assertThrows(IllegalStateException.class, () -> bus.call("t", null));
        assertThrows(IllegalStateException.class,
            () -> bus.consumer("t", Greeting.class));
        assertThrows(IllegalStateException.class,
            () -> bus.register("t", new Object()));
        bus.close(); // idempotent
    }
}
