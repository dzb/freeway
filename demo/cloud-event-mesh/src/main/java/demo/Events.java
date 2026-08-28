package demo;

import com.jujin.freeway.ioc.Topic;

/** Shared event contract for the cross-JVM demo (both nodes compile against it). */
public final class Events {

    /**
     * A CLASS-channel event. {@code @Topic} gives the CE routing key
     * ({@code greet.hello}) that peers declare in their subscriptions;
     * {@code Keyed} makes {@code key()} the CE {@code subject} and the Kafka
     * record key — per-aggregate ordering across both channels.
     */
    @Topic("greet.hello")
    public record Greeting(String name) implements com.jujin.freeway.ioc.EventBus.Keyed {
        @Override
        public String key() {
            return name;
        }
    }

    private Events() {}
}
