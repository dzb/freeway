package demo;

import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.event.CloudEventModule;
import com.jujin.freeway.cloud.event.CloudEventSubscription;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.event.EventBus;
import com.jujin.freeway.ioc.event.EventSubscriber;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Subscriber node of the cross-JVM event-mesh demo.
 *
 * <p>Interest is declared at composition with a {@link CloudEventSubscription}
 * contribution — the same declaration tells peers what to pull (hello), gates
 * inbound frames, and routes delivery. The handler then performs the demo's
 * one deliberate act: an explicit MIRROR line publishing the remote fact onto
 * the local bus, where an ordinary {@link EventSubscriber} prints it. Remote
 * facts become local facts only because this file said so.</p>
 *
 * <p>Runs until killed. See README.md for the full demo walkthrough.</p>
 */
public final class NodeB {

    private static final AtomicInteger deliveries = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        // HTTP port for the /cloud/event WS endpoint (fixed for peer config).
        System.setProperty(HttpConfigKeys.SERVER_PORT, "18080");
        // CloudEventBus: no dial peers — this node waits for inbound connections.
        System.setProperty(CloudConfigKeys.EVENT_ENABLED, "true");

        var app = FreewayApp.run(args, new HttpModule(), new CloudEventModule(), new ModuleEx() {
            @Override
            public void bind(Binder binder) {
                // Declared interest: prefix "greet.hello" (= the topic A publishes).
                // The handler captures the container and resolves the bus lazily
                // at first event — by then the bus is built, so no construction
                // cycle. This is the mirror: remote fact → local plane, by choice.
                binder.contribute(CloudEventSubscription.class)
                    .add("greet-mirror", container ->
                        CloudEventSubscription.of(Events.GREET_TOPIC, Events.Greeting.class, g -> {
                            int n = deliveries.incrementAndGet();
                            System.out.println("[B] mesh received #" + n + ": " + g + " — mirroring to the local bus");
                            container.get(EventBus.class).publish(g);
                        }));
                // The local consumer of the mirrored fact — an ordinary bus
                // subscriber, unaware the event ever crossed a process.
                binder.contribute(EventSubscriber.class).add("greet-print",
                    EventSubscriber.of(Events.Greeting.class, g ->
                        System.out.println("[B] local bus heard the mirrored fact: " + g)));
            }
        });
        app.get(EventBus.class); // build the bus now (the mirror resolves it at event time)

        System.out.println("[B] node ready — listening until killed");
        new CountDownLatch(1).await(); // resident subscriber node
    }
}
