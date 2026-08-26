package com.jujin.freeway.ioc;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.commons.scoped.Defer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


    // ==================== event types ====================

/** Shared event fixtures split out of the former EventBusTest. */

class PostCreatedEvent implements EventBus.Stoppable {
    private final Post post;
    private final AtomicBoolean stopped = new AtomicBoolean();
    PostCreatedEvent(Post post) { this.post = post; }
    Post post() { return post; }
    @Override public void stop() { stopped.set(true); }
    @Override public boolean isStopped() { return stopped.get(); }
}

class SpecialPostCreatedEvent extends PostCreatedEvent {
    SpecialPostCreatedEvent(Post post) { super(post); }
}

record Post(String title) {}

record CommentAddedEvent(Long postId, String text) {}
