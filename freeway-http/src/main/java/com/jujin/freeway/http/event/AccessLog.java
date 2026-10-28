package com.jujin.freeway.http.event;

import java.io.PrintStream;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Text access log: one line per {@link HttpExchangeEvent}, the shape
 * {@code method path status elapsed-ms remote-address user-agent}.
 *
 * <p>It is an <b>observer</b>, not a filter. The server already measures every
 * exchange and publishes the event outside the filter chain, so this sees the
 * requests a filter cannot: CORS preflights and health probes answered before
 * dispatch. The filter it replaces kept its own clock next to the server's.
 *
 * <p>{@code freeway.http.access-log.enabled} makes {@code HttpModule} write one
 * line to {@code System.out} from the server's event sink — so the line is
 * emitted even when an event bus {@code Defer} scope is active. An application
 * that wants another destination leaves the key off and contributes its own
 * {@code EventSubscriber.of(HttpExchangeEvent.class, event -> …)}: the same
 * event, one line either way, never both.
 *
 * <p>The enabled key writes from the server's sink, before the event is
 * published, so the built-in line is not subject to event-bus {@code Defer}
 * buffering: it is written even if a subscriber later fails. An application
 * that wires this type into its own {@code EventSubscriber} instead gets the
 * bus's delivery semantics — deferred to the commit inside a scope, like every
 * other event.
 */
public final class AccessLog implements Consumer<HttpExchangeEvent> {

    private final PrintStream out;

    public AccessLog() {
        this(System.out);
    }

    public AccessLog(PrintStream out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public void accept(HttpExchangeEvent exchange) {
        String ip = exchange.remoteAddress() == null || exchange.remoteAddress().isEmpty()
            ? "-"
            : exchange.remoteAddress();
        String ua = exchange.userAgent() == null || exchange.userAgent().isEmpty()
            ? "-"
            : exchange.userAgent();
        out.println(exchange.method() + " " + exchange.path()
            + " " + exchange.statusCode()
            + " " + exchange.elapsedMs() + "ms " + ip + " " + ua);
    }
}
