package com.jujin.freeway.flow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a string-based marker on a {@link TaskComponent} implementation.
 * Flow graphs reference these markers via the {@code !markerName} prefix
 * in task descriptions, enabling publish-subscribe style matching between
 * DAG nodes and their execution handlers.
 *
 * <pre>{@code
 *   // Declare markers on the handler
 *   &#64;FlowMarker("channel:notification")
 *   &#64;FlowMarker("priority:high")
 *   public class EmailSender implements TaskComponent { ... }
 *
 *   // Reference in graph JSON:
 *   // { "task": "!channel:notification !priority:high" }
 * }</pre>
 *
 * <p>Multiple markers on a handler form a capability set. A node's
 * required markers must be a subset of the handler's markers
 * ({@code containsAll} semantics). The most specific handler
 * (most markers) wins when multiple match.
 *
 * @see FlowMarkerIndex
 */
@Repeatable(FlowMarker.List.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FlowMarker {
    /**
     * The marker name. Convention: {@code category:value}
     * (e.g. {@code "channel:notification"}, {@code "priority:high"}).
     */
    String value();

    /**
     * Container annotation for repeatable {@link FlowMarker}.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        FlowMarker[] value();
    }
}
