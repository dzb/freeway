package com.jujin.freeway.ioc;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.commons.scoped.Defer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario-driven demonstrations for {@link CallBus}: six architectural
 * patterns from a slice of an e-commerce domain, each mapping to a decision
 * teams actually face. Every scenario is self-contained and doubles as
 * executable documentation.
 *
 * <p><b>The cast</b> — four modules that must talk but must not know each
 * other's internals:</p>
 * <ul>
 *   <li>order — orchestrates checkout</li>
 *   <li>user — owns addresses</li>
 *   <li>inventory — owns stock</li>
 *   <li>pricing / fraud / payment / recommendation — supporting domains</li>
 * </ul>
 */
class CallBusUseCasesTest {

    // ==================== 1. cross-module queries ====================

    /**
     * <b>Scenario: order module composes a checkout from three other
     * modules.</b> Each consumer side declares its own structural interface
     * ({@code UserApi}, {@code StockApi}, {@code PriceApi}) — note none of
     * the provider classes below is imported anywhere. Compile-time edges
     * between modules drop to zero; the topic strings are the whole
     * contract. This is the pattern that replaces "bind everything into one
     * god-context".
     */
    @Test
    void checkoutComposesThreeModulesWithoutCompileTimeDeps() {
        Container app = Freeway.create(binder -> {
            binder.bind(CallBus.class).to(CallBus::new);

            // ---- user module (provider) ----
            binder.bind(UserListener.class).to(container -> {
                var listener = new UserListener();
                container.get(CallBus.class).register("user", listener);
                return listener;
            });

            // ---- inventory module (provider) ----
            binder.bind(InventoryListener.class).to(container -> {
                var listener = new InventoryListener();
                container.get(CallBus.class).register("inventory", listener);
                return listener;
            });

            // ---- pricing module (provider) ----
            binder.bind(PricingListener.class).to(container -> {
                var listener = new PricingListener();
                container.get(CallBus.class).register("pricing", listener);
                return listener;
            });

            // ---- order module (consumer): injects three typed views ----
            binder.bind(CheckoutService.class).to(container -> new CheckoutService(
                container.get(CallBus.class).consumer("user", UserApi.class),
                container.get(CallBus.class).consumer("inventory", StockApi.class),
                container.get(CallBus.class).consumer("pricing", PriceApi.class)
            ));
        });

        // Provider services are lazy — resolving them performs registration.
        app.get(UserListener.class);
        app.get(InventoryListener.class);
        app.get(PricingListener.class);

        Receipt receipt = app.get(CheckoutService.class)
            .checkout("u-1", List.of("sku-a", "sku-b"));

        assertEquals("张三@北京", receipt.shipTo());
        assertEquals(24999, receipt.totalCents());
        assertTrue(receipt.reserved().containsAll(List.of("sku-a", "sku-b")));
    }

    /** Lives in the ORDER module — a structural copy, not an import. */
    interface UserApi {
        String address(String userId);
    }
    /** Lives in the INVENTORY module. */
    interface StockApi {
        boolean reserve(String sku);
    }
    /** Lives in the PRICING module. */
    interface PriceApi {
        int cents(String sku);
    }

    record Receipt(String shipTo, long totalCents, List<String> reserved) {}

    /** Order module service — depends only on its own interfaces above. */
    static class CheckoutService {
        private final UserApi users;
        private final StockApi stock;
        private final PriceApi prices;

        CheckoutService(UserApi users, StockApi stock, PriceApi prices) {
            this.users = users;
            this.stock = stock;
            this.prices = prices;
        }

        Receipt checkout(String userId, List<String> skus) {
            String address = users.address(userId);
            List<String> reserved = skus.stream()
                .filter(stock::reserve)
                .toList();
            long total = reserved.stream()
                .mapToLong(prices::cents)
                .sum();
            return new Receipt(address, total, reserved);
        }
    }

    // ---- provider implementations (each in its own module) ----

    static class UserListener {
        public String address(String userId) {
            return "u-1".equals(userId) ? "张三@北京" : "unknown";
        }
    }

    static class InventoryListener {
        public boolean reserve(String sku) {
            return !sku.equals("sku-sold-out");
        }
    }

    static class PricingListener {
        public int cents(String sku) {
            return sku.equals("sku-b") ? 10000 : 14999;
        }
    }

    // ==================== 2. breaking dependency cycles ====================

    /**
     * <b>Scenario: pricing needs stock status, inventory needs prices for
     * valuation.</b> Bound as plain IoC services this pair is an unresolvable
     * cycle. As call peers, each side sees only its own interface and a topic
     * prefix — the cycle dissolves into two conversations.
     */
    @Test
    void mutualQueriesWithoutCircularBindings() {
        Container app = Freeway.create(binder -> {
            binder.bind(CallBus.class).to(CallBus::new);
            binder.bind(ValuationListener.class).to(container -> {
                var listener = new ValuationListener(
                    container.get(CallBus.class).consumer("stock", StockView.class));
                container.get(CallBus.class).register("valuation", listener);
                return listener;
            });
            binder.bind(StockListener.class).to(container -> {
                var listener = new StockListener(
                    container.get(CallBus.class).consumer("valuation", ValuationView.class));
                container.get(CallBus.class).register("stock", listener);
                return listener;
            });
        });

        // Resolution order proves neither side needs the other constructed.
        app.get(StockListener.class);
        app.get(ValuationListener.class);

        assertEquals(20000,
            app.get(ValuationListener.class).positionValue("SKU-X"));
    }

    /** Owned by the STOCK side. */
    interface StockView {
        int units(String sku);
    }
    /** Owned by the VALUATION side. */
    interface ValuationView {
        int centsPerUnit(String sku);
    }

    /** Inventory asks pricing what a unit is worth. */
    static class StockListener {
        private final ValuationView valuations;
        StockListener(ValuationView valuations) { this.valuations = valuations; }
        public int units(String sku) { return 4; }
        public int marketValue(String sku) { return units(sku) * valuations.centsPerUnit(sku); }
    }

    /** Pricing asks inventory how many units sit on shelves. */
    static class ValuationListener {
        private final StockView stock;
        ValuationListener(StockView stock) { this.stock = stock; }
        public int centsPerUnit(String sku) { return 5000; }
        public int positionValue(String sku) { return stock.units(sku) * centsPerUnit(sku); }
    }

    // ==================== 3. transactional consistency ====================

    /**
     * <b>Scenario: one checkout transaction mixes a question and a fact.</b>
     * The price LOOKUP is a call — it answers now and sees the
     * mid-transaction world, exactly like a local method call (deferring it
     * would deadlock the joining caller). The confirmation NOTICE is a fact
     * — published on the EventBus, buffered by its {@code Defer}
     * integration, delivered only after commit; a rollback erases it.
     *
     * <p>Asking now and telling later are different channels on purpose:
     * calls cannot be deferred (their replies are consumed inline),
     * broadcasts cannot fail fast (nobody is waiting).</p>
     */
    @Test
    void asksNowTellsLater() {
        Container container = Freeway.create();
        EventBus events = new EventBus(container);
        CallBus calls = new CallBus(container);

        calls.register("pricing", new Object() {
            public int cents(String sku) { return 1000; }
        });
        List<String> notices = new CopyOnWriteArrayList<>();
        events.subscribe("order.confirmed", p -> notices.add((String) p));

        // --- committed transaction ---
        Defer.within(() -> {
            int cents = (Integer) calls.call("pricing.cents", List.of("sku-a"))
                .toCompletableFuture().join();
            assertEquals(1000, cents);
            assertTrue(notices.isEmpty(), "the fact is not told yet");
            events.publish("order.confirmed", "ord-1");
            assertTrue(notices.isEmpty(), "buffered until commit");
        });
        assertEquals(List.of("ord-1"), notices,
            "the fact is delivered after commit");

        // --- rolled-back transaction ---
        Defer.within(scope -> {
            events.publish("order.confirmed", "ord-2");
            scope.rollback();
        });
        assertEquals(List.of("ord-1"), notices,
            "rollback erases the fact, exactly once-semantics preserved");

        events.close();
        calls.close();
    }

    // ==================== 4. optional dependencies ====================

    /**
     * <b>Scenario: recommendations are a nice-to-have module that may not be
     * deployed.</b> The consumer interface carries a default implementation;
     * with no provider bound the product keeps working ("nothing we can
     * suggest"), and binding a provider upgrades behavior with zero consumer
     * changes. Feature flags, gradual rollouts and edge-node deployments all
     * reduce to register-or-not.
     */
    @Test
    void missingModuleDegradesToDefaultThenUpgradesInPlace() {
        var bus = new CallBus(Freeway.create());
        RecommendationApi api = bus.consumer("reco", RecommendationApi.class);

        // No provider deployed — the default answers.
        assertEquals(List.of(), api.forUser("u-1"));

        // The recommendation module ships later...
        bus.register("reco", new Object() {
            public List<String> forUser(String userId) {
                return List.of("kindle", "usb-c");
            }
        });
        // ...and the very next call reaches it.
        assertEquals(List.of("kindle", "usb-c"), api.forUser("u-1"));
        bus.close();
    }

    interface RecommendationApi {
        /** Degradation answer while no recommendation module is deployed. */
        default List<String> forUser(String userId) {
            return List.of();
        }
    }

    // ==================== 5. hot-swapping providers ====================

    /**
     * <b>Scenario: switch payments from sandbox to production mid-flight —
     * or flip an A/B experiment — without touching consumers.</b> Providers
     * are slots, so re-registration atomically repoints every existing proxy.
     */
    @Test
    void providerSwapsUnderAStableConsumerProxy() {
        var bus = new CallBus(Freeway.create());
        bus.register("pay", new Object() {
            public String charge(String orderId) { return "sandbox-receipt"; }
        });

        PaymentApi pay = bus.consumer("pay", PaymentApi.class);
        assertEquals("sandbox-receipt", pay.charge("ord-1"));

        bus.register("pay", new Object() {          // atomic slot replacement
            public String charge(String orderId) { return "prod-receipt"; }
        });
        assertEquals("prod-receipt", pay.charge("ord-2"),
            "same proxy instance, new provider behind it");
        bus.close();
    }

    interface PaymentApi {
        String charge(String orderId);
    }

    // ==================== 6. latency budgets ====================

    /**
     * <b>Scenario: fraud scoring must not stall checkout.</b> The call
     * carries a hard budget ({@code orTimeout}); when the budget lapses the
     * caller degrades to a heuristic instead of failing the purchase.
     * Timeouts compose with the deferred-dispatch path too — see
     * {@code CallBusTest#timeoutFiresWhileCallIsDeferred}.
     */
    @Test
    void latencyBudgetDegradesInsteadOfStalling() throws Exception {
        var bus = new CallBus(Freeway.create());
        var scored = new CountDownLatch(1);       // holds the provider busy
        bus.register("fraud", new Object() {
            public boolean risky(long orderId) throws InterruptedException {
                scored.await();
                return true;
            }
        });

        AtomicReference<String> outcome = new AtomicReference<>("pending");
        Thread pump = Thread.ofVirtual().start(() -> {
            try {
                boolean verdict = (Boolean) bus.call("fraud.risky", List.of(42L),
                        Duration.ofMillis(80))
                    .get(2, TimeUnit.SECONDS);
                outcome.set(verdict ? "reject" : "allow");
            } catch (ExecutionException e) {
                // Budget exhausted: ship anyway under the cheap heuristic.
                outcome.set(e.getCause() instanceof TimeoutException
                    ? "allow-by-heuristic"
                    : "error");
            } catch (Exception e) {
                outcome.set("error");
            }
        });

        long deadline = System.currentTimeMillis() + 2000;
        while ("pending".equals(outcome.get())
               && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        scored.countDown();
        pump.join(2000);

        assertEquals("allow-by-heuristic", outcome.get(),
            "a lapsed budget must degrade, never block the buyer");
        bus.close();
    }
}
