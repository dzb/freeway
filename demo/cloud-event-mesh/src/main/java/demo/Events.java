package demo;

/** Shared event contract for the cross-JVM mesh demo (both nodes compile against it). */
public final class Events {

    /** The mesh topic the greeting travels on — the CE {@code type} on the wire.
     *  Routing names on the cloud plane are strings chosen here, never Java
     *  class names: renaming this record cannot break the wire contract. */
    public static final String GREET_TOPIC = "greet.hello";

    /** A plain payload record — no framework annotation, no transport contract. */
    public record Greeting(String name) {}

    private Events() {}
}
