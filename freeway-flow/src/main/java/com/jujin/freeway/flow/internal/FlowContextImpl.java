package com.jujin.freeway.flow.internal;

import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.flow.FlowContext;
import com.jujin.freeway.flow.FlowEventBus;
import com.jujin.freeway.flow.FlowException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flow context: the data map, the run's event bus and the stop signal.
 *
 * <p>Branch isolation: {@link #beginBranch()} pushes a thread-local write
 * buffer. While a PARALLEL branch runs, every write lands in the buffer and
 * every read sees buffer-then-parent — so branches cannot race on the same
 * key the way they raced before isolation existed. The returned merger folds
 * the buffer into the parent and fails on a genuine write-write conflict
 * (this branch wrote a key whose parent value moved since its first write).
 * {@code join: "shared"} skips the buffer entirely — the opt-out for graphs
 * that share deliberately.</p>
 *
 * <p>A null value means "cleared": it reads back as null and disappears from
 * {@code containsKey} (the backing map forbids null values, so a sentinel
 * stores the clearing inside a layer).</p>
 */
public final class FlowContextImpl implements FlowContext {
    private static final VarHandle EVENT_BUS;
    /** Sentinel for "cleared in this layer" (the CHM root simply drops the key). */
    private static final Object REMOVED = new Object();

    static {
        try {
            EVENT_BUS = MethodHandles.lookup()
                    .findVarHandle(FlowContextImpl.class, "eventBus", FlowEventBus.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** One branch-local write layer: what changed, and the parent value each change started from. */
    private static final class Layer {
        final Map<String, Object> writes = new HashMap<>();
        final Map<String, Object> bases = new HashMap<>();
    }

    private final Map<String, Object> root = new ConcurrentHashMap<>();
    /** Head = innermost (most recently opened) branch layer; iteration is inner→outer. */
    private final ThreadLocal<Deque<Layer>> layers =
        ThreadLocal.withInitial(ArrayDeque::new);
    private final Object mergeLock = new Object();
    @SuppressWarnings("unused") // accessed via VarHandle
    private volatile FlowEventBus eventBus;
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
        stopped(true);
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    @Override
    public void stopped(boolean stopped) {
        this.stopped = stopped;
    }

    // --- event bus ---

    @Override
    public FlowEventBus eventBus() {
        // fast path: acquire ensures visibility of a fully-constructed bus
        FlowEventBus bus = (FlowEventBus) EVENT_BUS.getAcquire(this);
        if (bus != null) {
            return bus;
        }
        // slow path: CAS one instance, discard loser's extra allocation
        FlowEventBus created = new FlowEventBus();
        if (EVENT_BUS.compareAndSet(this, null, created)) {
            return created;
        }
        return (FlowEventBus) EVENT_BUS.getAcquire(this);
    }

    // --- data ---

    @Override
    public Map<String, Object> data() {
        return new DataView();
    }

    @Override
    public Runnable beginBranch() {
        Deque<Layer> stack = layers.get();
        Layer layer = new Layer();
        stack.push(layer);
        return () -> mergeLayer(layer, stack);
    }

    /** Effective value: innermost layer that touched the key wins, then the root. */
    private Object readThrough(String key) {
        for (Layer layer : layers.get()) { // inner→outer
            if (layer.writes.containsKey(key)) {
                Object v = layer.writes.get(key);
                return v == REMOVED ? null : v;
            }
        }
        return root.get(key);
    }

    private void writeThrough(String key, Object value) {
        Deque<Layer> stack = layers.get();
        Layer top = stack.peek();
        Object stored = value == null ? REMOVED : value;
        if (top == null) {
            if (value == null) root.remove(key);
            else root.put(key, value);
            return;
        }
        top.bases.putIfAbsent(key, visibleUnder(top, key));
        top.writes.put(key, stored);
    }

    /** The value a first write in {@code top} starts from: outer layers, then root. */
    private Object visibleUnder(Layer top, String key) {
        boolean belowTop = false;
        for (Layer layer : layers.get()) { // inner→outer
            if (layer == top) {
                belowTop = true;
                continue;
            }
            if (belowTop && layer.writes.containsKey(key)) {
                Object v = layer.writes.get(key);
                return v == REMOVED ? null : v;
            }
        }
        return root.get(key);
    }

    private void mergeLayer(Layer layer, Deque<Layer> stack) {
        synchronized (mergeLock) {
            if (stack.peek() != layer) {
                throw new IllegalStateException(
                    "Branch layers must merge in LIFO order — a nested "
                        + "branch outlived its enclosing walk");
            }
            stack.pop();
            Layer target = stack.peek();
            for (Map.Entry<String, Object> change : layer.writes.entrySet()) {
                String key = change.getKey();
                Object base = layer.bases.get(key);
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
                if (target == null) {
                    if (change.getValue() == REMOVED) root.remove(key);
                    else root.put(key, change.getValue());
                } else {
                    target.bases.putIfAbsent(key, base);
                    target.writes.put(key, change.getValue());
                }
            }
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
        return JsonUtils.stringify(o);
    }

    private Map<String, Object> effectiveSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>(root);
        java.util.Iterator<Layer> outerToInner = layers.get().descendingIterator();
        while (outerToInner.hasNext()) {
            Layer layer = outerToInner.next();
            layer.writes.forEach((k, v) -> {
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
