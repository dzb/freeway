package demo;

import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.event.CloudEventModule;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.event.EventBus;
import com.jujin.freeway.ioc.event.EventSubscriber;

/**
 * Publisher node of the cross-JVM event-mesh demo.
 *
 * <p>Dials node B over the WS mesh and publishes once on the cloud plane —
 * that publish leaves the JVM because the call names the plane. It then
 * publishes the same fact on the local bus to prove the separation: a local
 * publish stays inside this process even with the mesh loaded. Publish,
 * observe, exit.</p>
 *
 * <p>See README.md for the full demo walkthrough.</p>
 */
public final class NodeA {

    public static void main(String[] args) throws Exception {
        // A's own HTTP port — must differ from B's (both run locally).
        System.setProperty(HttpConfigKeys.SERVER_PORT, "18081");
        // CloudEventBus: dial B; A declares no subscriptions (outbound-only).
        System.setProperty(CloudConfigKeys.EVENT_ENABLED, "true");
        System.setProperty(CloudConfigKeys.EVENT_PEERS, "127.0.0.1:18080");

        var app = FreewayApp.run(args, new HttpModule(), new CloudEventModule(), new ModuleEx() {
            @Override
            public void bind(Binder binder) {
                // The local counterpart: prints to show a LOCAL publish never
                // leaves this JVM — no mesh frame is built for it.
                binder.contribute(EventSubscriber.class).add("local-print",
                    EventSubscriber.of(Events.Greeting.class, g ->
                        System.out.println("[A] local bus heard (and NO peer receives this): " + g)));
            }
        });

        var mesh = app.get(com.jujin.freeway.cloud.event.CloudEventBus.class);
        EventBus bus = app.get(EventBus.class);

        // Let the mesh handshake settle.
        Thread.sleep(3000);

        System.out.println("[A] publishing Greeting(bob): once on the mesh plane, once on the local plane");
        mesh.publish(Events.GREET_TOPIC, new Events.Greeting("bob")); // → crosses to B
        bus.publish(new Events.Greeting("bob"));                       // → stays here

        // Stay alive long enough for B's logs to be observed, then exit.
        Thread.sleep(10_000);
        app.close();
        System.out.println("[A] done");
    }
}
