package com.jujin.freeway.ioc;

import java.util.Objects;

/**
 * Thrown when a {@link CallBus} request finds no registered handler for its
 * topic — the request-reply counterpart of {@link DeadEvent}: DeadEvent
 * diagnoses undelivered broadcasts, this signals an unanswered call.
 *
 * <p>Consumer proxies catch it to fall back to interface default methods;
 * direct {@link CallBus#call} callers receive it as the cause inside the
 * standard {@code CompletionException}/{@code ExecutionException} wrapping
 * — one uniform unwrapping rule for every call failure, no special cases.</p>
 */
public final class DeadCallException extends RuntimeException {

    private final String topic;

    /** @param topic the topic that had no handler */
    public DeadCallException(String topic) {
        super("No handler for call topic '" + topic + "'");
        this.topic = Objects.requireNonNull(topic, "topic");
    }

    /** The topic that had no handler. */
    public String topic() {
        return topic;
    }
}
