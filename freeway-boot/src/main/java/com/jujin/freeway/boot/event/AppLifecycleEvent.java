package com.jujin.freeway.boot.event;

import com.jujin.freeway.boot.AppState;
import com.jujin.freeway.ioc.Container;
import java.util.Objects;

/**
 * Published on the {@code EventBus} at a lifecycle transition of a running
 * {@link com.jujin.freeway.boot.AppRuntime}: {@link AppState#RUNNING} when all
 * hooks have started, {@link AppState#STOPPING} before hooks are stopped.
 *
 * <p>One type with a state field instead of one type per moment: a subscriber
 * filters on {@link #state()} (or ignores the other transitions), and a new
 * lifecycle moment does not add a type. The two transitions still differ in
 * delivery policy — the running signal is best-effort, the stopping signal is a
 * reliability point — which is documented on {@code AppRuntime}.</p>
 */
public record AppLifecycleEvent(Container container, AppState state) {

    public AppLifecycleEvent {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(state, "state");
    }
}
