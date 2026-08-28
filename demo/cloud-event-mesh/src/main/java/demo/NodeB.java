package demo;

import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.events.CloudEventModule;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.mq.kafka.KafkaModule;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Subscriber node of the cross-JVM event-mesh demo.
 *
 * <p>Both channels are installed (WS mesh + Kafka bridge); the same logical
 * event stream arrives over each — a CLASS event and a TOPIC payload are
 * expected TWICE (one copy per channel), and each delivery is counted so
 * the run log proves both channels end-to-end.</p>
 *
 * <p>Runs until killed. See README.md for the full demo walkthrough.</p>
 */
public final class NodeB {

    private static final AtomicInteger deliveries = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        // HTTP port for the /cloud/events WS endpoint (fixed for peer config).
        System.setProperty(HttpConfigKeys.SERVER_PORT, "18080");
        // CloudEventBus: pull "greet."-prefixed types; allowlist the CLASS type.
        System.setProperty(CloudConfigKeys.EVENTS_ENABLED, "true");
        System.setProperty(CloudConfigKeys.EVENTS_SUBSCRIPTIONS, "greet.");
        System.setProperty(CloudConfigKeys.EVENTS_ALLOWED_TYPES, Events.Greeting.class.getName());
        // Kafka: same logical stream via the broker. allowlist BOTH the CLASS
        // type and the TOPIC payload type (java.lang.String) — the bridge
        // gates every deserialization type, exactly like the CE allowlist.
        System.setProperty("freeway.kafka.bootstrap-servers", "127.0.0.1:9092");
        System.setProperty("freeway.kafka.group-id", "mesh-demo-b");
        System.setProperty("freeway.kafka.client-id", "node-b");
        System.setProperty("freeway.kafka.topics", "greet.hello");
        System.setProperty("freeway.kafka.allowed-event-types",
            Events.Greeting.class.getName() + ",java.lang.String");

        var app = FreewayApp.run(args, new HttpModule(), new CloudEventModule(), new KafkaModule());
        EventBus bus = app.get(EventBus.class);

        bus.subscribe(Events.Greeting.class, e ->
            System.out.println("[B] Greeting delivered #" + deliveries.incrementAndGet()
                + ": " + e));
        bus.subscribe("greet.hello", p ->
            System.out.println("[B] topic payload delivered #" + deliveries.incrementAndGet()
                + ": " + p));

        System.out.println("[B] node ready — listening until killed");
        new CountDownLatch(1).await(); // resident subscriber node
    }
}
