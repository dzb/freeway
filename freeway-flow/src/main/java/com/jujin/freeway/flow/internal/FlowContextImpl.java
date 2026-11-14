package com.jujin.freeway.flow.internal;

import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.commons.util.ContextExecutor;
import com.jujin.freeway.commons.util.LazyValue;
import com.jujin.freeway.flow.FlowContext;
import com.jujin.freeway.flow.FlowContext.Branch;
import com.jujin.freeway.flow.FlowEventBus;
import com.jujin.freeway.flow.FlowException;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Flow context: the data map, the run's event bus and the stop signal.
 *
 * <p>Branch isolation is a {@link ScopedValue} frame, not a thread-local
 * stack: {@link #beginBranch()} creates an immutable child of the current
 * frame, and {@link FlowContext.Branch#run} binds it for the branch body.
 * Nested PARALLEL branches inherit the enclosing frame through
 * {@link #capturing(Executor)}, so an inner branch writes into its outer
 * branch's buffer instead of escaping to the root — the frame is carried by
 * the work, never by the worker's thread.</p>
 *
 * <p>While a branch runs, every write lands in its buffer and every read sees
 * buffer-then-parent, so branches cannot race on the same key. Committing the
 * returned scope folds the buffer into the parent and fails on a genuine
 * write-write conflict (this branch wrote a key whose parent value moved since
 * its first write); closing it without a commit discards the buffer,
 * including when the branch threw. {@code join: "shared"} skips the buffer —
 * writes land in the fork's enclosing frame (or the root at top level).</p>
 *
 * <p>A null value means "cleared": it reads back as null and disappears from
 * {@code containsKey} (the backing map forbids null values, so a sentinel
 * stores the clearing inside a layer).</p>
 */
public final class FlowContextImpl implements FlowContext, FlowContextState {
    /** Sentinel for "cleared in this layer" (the CHM root simply drops the key). */
    private static final Object REMOVED = new Object();

    /** Sentinel for "the parent held no value" — a layer map forbids null. */
    private static final Object NO_BASE = new Object();

    /** One branch-local write layer: what changed, and the parent value each change started from. */
    private static final class Layer {
        final Map<String, Object> writes = new ConcurrentHashMap<>();
        final Map<String, Object> bases = new ConcurrentHashMap<>();
        /** Set when the branch owning this layer committed or closed. */
        volatile boolean settled;
        /** Child frames not yet committed/closed; a parent cannot fold first. */
        final java.util.concurrent.atomic.AtomicInteger openChildren =
            new java.util.concurrent.atomic.AtomicInteger();
    }

    /** One branch's position: its own layer plus the enclosing frame (null = root). */
    private record BranchFrame(Layer layer, BranchFrame parent) {}

    /** The current branch frame; unbound means the root scope. */
    private static final ScopedValue<BranchFrame> FRAME = ScopedValue.newInstance();

    private final Map<String, Object> root = new ConcurrentHashMap<>();
    private final Object mergeLock = new Object();

    /**
     * Wraps an executor so each task runs with the submitting thread's branch
     * frame. This is how a PARALLEL fork carries its scope to the worker:
     * {@link #beginBranch()} inside the task then chains onto the inherited
     * frame instead of starting at the root, and nested forks compose.
     */
    public static Executor capturing(Executor delegate) {
        return ContextExecutor.wrapping(delegate, FRAME);
    }
    private final LazyValue<FlowEventBus> eventBus = LazyValue.of(FlowEventBus::new);
    private volatile boolean stopped;

    public FlowContextImpl() {
        this(null);
    }

    public FlowContextImpl(String instanceId) {
        root.put("instanceId", instanceId == null ? "" : instanceId);
        root.put("context", this);
    }

    // --- flow control ---

    @Override
    public void stop() {
        setStopped(true);
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    @Override
    public void setStopped(boolean stopped) {
        this.stopped = stopped;
    }

    // --- event bus ---

    @Override
    public FlowEventBus eventBus() {
        return eventBus.get();
    }

    // --- data ---

    @Override
    public Map<String, Object> data() {
        return new DataView();
    }

    @Override
    public Branch beginBranch() {
        BranchFrame parent = FRAME.isBound() ? FRAME.get() : null;
        if (parent != null) {
            parent.layer().openChildren.incrementAndGet();
        }
        return new LayerScope(new BranchFrame(new Layer(), parent));
    }

    private static Object maskBase(Object base) {
        return base == null ? NO_BASE : base;
    }

    private static Object unmaskBase(Object masked) {
        return masked == NO_BASE ? null : masked;
    }

    /**
     * One branch's handle to its layer. Commit folds it; close discards it
     * unless it was committed — so the caller chooses between the two by
     * structure (try-with-resources), not by remembering a cleanup call. The
     * discard removes the layer by identity, which keeps the parent stack
     * intact however deeply a failure unwound nested branches.
     */
    private final class LayerScope implements Branch {
        private final BranchFrame frame;
        private boolean settled;

        private LayerScope(BranchFrame frame) {
            this.frame = frame;
        }

        @Override
        public void run(Runnable body) {
            if (settled) {
                throw new IllegalStateException(
                    "Branch scope already closed — running after the scope "
                        + "ended would write into a folded layer");
            }
            ScopedValue.where(FRAME, frame).run(body);
        }

        @Override
        public void commit() {
            if (settled) {
                throw new IllegalStateException(
                    "Branch scope already closed — a commit after the scope "
                        + "ended would fold a layer that is no longer open");
            }
            if (frame.layer().openChildren.get() > 0) {
                throw new IllegalStateException(
                    "Branch layers must merge in LIFO order — a nested "
                        + "branch outlived its enclosing walk");
            }
            mergeLayer(frame);
            settled = true;
            releaseParent();
        }

        @Override
        public void close() {
            if (settled) {
                return;
            }
            if (frame.layer().openChildren.get() > 0) {
                throw new IllegalStateException(
                    "Branch layers must close in LIFO order — a nested "
                        + "branch outlived its enclosing walk");
            }
            settled = true;
            frame.layer().settled = true; // a discarded parent rejects late children
            releaseParent();
        }

        private void releaseParent() {
            BranchFrame parent = frame.parent();
            if (parent != null) {
                parent.layer().openChildren.decrementAndGet();
            }
        }
    }

    /** Effective value: innermost layer that touched the key wins, then the root. */
    private Object readThrough(String key) {
        for (BranchFrame frame = currentFrame(); frame != null; frame = frame.parent()) {
            Layer layer = frame.layer();
            if (layer.writes.containsKey(key)) {
                Object v = layer.writes.get(key);
                return v == REMOVED ? null : v;
            }
        }
        return root.get(key);
    }

    private void writeThrough(String key, Object value) {
        BranchFrame frame = currentFrame();
        Object stored = value == null ? REMOVED : value;
        if (frame == null) {
            if (value == null) root.remove(key);
            else root.put(key, value);
            return;
        }
        Layer top = frame.layer();
        if (!top.bases.containsKey(key)) {
            // A recorded null baseline is a baseline: containsKey keeps a later
            // sibling write from refreshing it and hiding a conflict.
            top.bases.put(key, maskBase(visibleUnder(frame, key)));
        }
        top.writes.put(key, stored);
    }

    private static BranchFrame currentFrame() {
        return FRAME.isBound() ? FRAME.get() : null;
    }

    /** The value a first write in {@code frame} starts from: outer frames, then root. */
    private Object visibleUnder(BranchFrame frame, String key) {
        for (BranchFrame outer = frame.parent(); outer != null; outer = outer.parent()) {
            Layer layer = outer.layer();
            if (layer.writes.containsKey(key)) {
                Object v = layer.writes.get(key);
                return v == REMOVED ? null : v;
            }
        }
        return root.get(key);
    }

    private void mergeLayer(BranchFrame frame) {
        Layer layer = frame.layer();
        BranchFrame parent = frame.parent();
        Layer target = parent == null ? null : parent.layer();
        synchronized (mergeLock) {
            if (target != null && target.settled) {
                throw new IllegalStateException(
                    "Branch layers must merge in LIFO order — a nested "
                        + "branch outlived its enclosing walk");
            }
            // Validate the whole layer before mutating: a conflict on the
            // second key must not leave the first one merged into the parent.
            for (Map.Entry<String, Object> change : layer.writes.entrySet()) {
                String key = change.getKey();
                Object base = unmaskBase(layer.bases.get(key));
                Object current = parentValue(target, key);
                if (!Objects.equals(current, base)) {
                    throw new FlowException(
                        "PARALLEL branches wrote conflicting values for key '"
                            + key + "': parent holds " + current
                            + " while this branch based its write on " + base
                            + " — let one branch own the key, or declare"
                            + " join: \"shared\" on the fork to opt out of"
                            + " conflict detection");
                }
            }
            for (Map.Entry<String, Object> change : layer.writes.entrySet()) {
                String key = change.getKey();
                if (target == null) {
                    if (change.getValue() == REMOVED) root.remove(key);
                    else root.put(key, change.getValue());
                } else {
                    target.bases.putIfAbsent(key, layer.bases.get(key));
                    target.writes.put(key, change.getValue());
                }
            }
            layer.settled = true;
        }
    }

    private Object parentValue(Layer target, String key) {
        if (target != null && target.writes.containsKey(key)) {
            Object v = target.writes.get(key);
            return v == REMOVED ? null : v;
        }
        return root.get(key);
    }

    // --- serialization (diagnostic) ---

    /** Rebuilds a context from a {@link #toJson()} document (data only). */
    public static FlowContextImpl fromJson(String json) {
        FlowContextImpl ctx = new FlowContextImpl();
        if (json != null && !json.isEmpty()) {
            JsonObject o = JsonUtils.parseObject(json);
            Boolean wasStopped = o.getBoolean("stopped");
            ctx.stopped = wasStopped != null && wasStopped;
            JsonObject dataObj = o.getObject("data");
            if (dataObj != null) {
                ctx.root.putAll(dataObj.toMap());
            }
        }
        return ctx;
    }

    @Override
    public String toJson() {
        JsonObject dataObj = JsonUtils.object();
        for (Map.Entry<String, Object> entry : effectiveSnapshot().entrySet()) {
            if (!"context".equals(entry.getKey())) {
                dataObj.put(entry.getKey(), entry.getValue());
            }
        }
        JsonObject o = JsonUtils.object();
        o.put("stopped", stopped);
        o.put("data", dataObj);
        return JsonUtils.toJson(o);
    }

    private Map<String, Object> effectiveSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>(root);
        List<Layer> chain = new ArrayList<>();
        for (BranchFrame frame = currentFrame(); frame != null; frame = frame.parent()) {
            chain.add(frame.layer()); // inner→outer
        }
        for (int i = chain.size() - 1; i >= 0; i--) { // apply outer→inner
            chain.get(i).writes.forEach((k, v) -> {
                if (v == REMOVED) out.remove(k); else out.put(k, v);
            });
        }
        return out;
    }

    /**
     * Live view of the effective data: reads walk the branch layers, writes
     * land in the current layer (or the root outside any branch).
     */
    private final class DataView extends AbstractMap<String, Object> {
        @Override
        public Object get(Object key) {
            return key instanceof String k ? readThrough(k) : null;
        }

        @Override
        public boolean containsKey(Object key) {
            return key instanceof String k && effectiveSnapshot().containsKey(k);
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            Set<Entry<String, Object>> out = new HashSet<>();
            effectiveSnapshot().forEach((k, v) -> out.add(new SimpleEntry<>(k, v)));
            return out;
        }

        @Override
        public Object put(String key, Object value) {
            Object previous = readThrough(key);
            writeThrough(key, value);
            return previous;
        }

        @Override
        public Object remove(Object key) {
            if (!(key instanceof String k)) return null;
            Object previous = readThrough(k);
            writeThrough(k, null);
            return previous;
        }

        @Override
        public void clear() {
            for (String key : effectiveSnapshot().keySet()) remove(key);
        }
    }
}
