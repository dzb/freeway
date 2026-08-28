package demo;

import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.events.CloudEventModule;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.mq.kafka.KafkaModule;

/**
 * Publisher node of the cross-JVM event-mesh demo.
 *
 * <p>Dials node B over the WS mesh and bridges to Kafka — a single
 * {@code publish} fans out over BOTH channels. Publish once, then exit.</p>
 *
 * <p>See README.md for the full demo walkthrough.</p>
 */
public final class NodeA {

    public static void main(String[] args) throws Exception {
        // A's own HTTP port — must differ from B's (both run locally).
        System.setProperty(HttpConfigKeys.SERVER_PORT, "18081");
        // CloudEventBus: dial B; A declares no subscriptions (outbound-only).
        System.setProperty(CloudConfigKeys.EVENTS_ENABLED, "true");
        System.setProperty(CloudConfigKeys.EVENTS_PEERS, "127.0.0.1:18080");
        System.setProperty(CloudConfigKeys.EVENTS_SUBSCRIPTIONS, "");
        // Kafka: same broker, distinct consumer group and origin identity.
        System.setProperty("freeway.kafka.bootstrap-servers", "127.0.0.1:9092");
        System.setProperty("freeway.kafka.group-id", "mesh-demo-a");
        System.setProperty("freeway.kafka.client-id", "node-a");
        System.setProperty("freeway.kafka.topics", "greet.hello");

        var app = FreewayApp.run(args, new HttpModule(), new CloudEventModule(), new KafkaModule());
        EventBus bus = app.get(EventBus.class);

        // Let the mesh handshake and the Kafka consumer group settle.
        Thread.sleep(3000);

        System.out.println("[A] publishing Greeting(bob) + greet.hello=hello-topic …");
        bus.publish(new Events.Greeting("bob"));    // CLASS channel
        bus.publish("greet.hello", "hello-topic");   // TOPIC channel

        // Stay alive long enough for B's logs to be observed, then exit.
        Thread.sleep(10_000);
        app.close();
        System.out.println("[A] done");
    }
}
