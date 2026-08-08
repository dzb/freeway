package com.jujin.freeway.flow;

import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Flow context implementation.
 *
 * <p>Migration notes:
 * <ul>
 *   <li>The context is narrowed to a per-flow-execution object; it is no longer treated as a state container persisted across runs.</li>
 *   <li>{@code exchanger}, {@code eventBus} and {@code trace} only hold execution state for replay and debugging; they are not part of the stable JSON protocol.</li>
 *   <li>Serialization explicitly excludes the {@code context} self-reference and supports both historical {@code trace} formats (object and string).</li>
 * </ul>
 * This preserves controlled resume capability while keeping runtime objects out of the stable protocol.</p>
 *
 * @author noear
 * @since 3.5
 */
public class FlowContextImpl implements FlowContext {
    private static final VarHandle EVENT_BUS;

    static {
        try {
            EVENT_BUS = MethodHandles.lookup()
                    .findVarHandle(FlowContextImpl.class, "eventBus", FlowEventBus.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Map<String, Object> data = new ConcurrentHashMap<>();
    private FlowTrace trace = new FlowTrace();
    private volatile FlowExchanger exchanger;
    @SuppressWarnings("unused") // accessed via VarHandle
    private volatile FlowEventBus eventBus;
    private volatile boolean stopped;

    public FlowContextImpl() {
        this(null);
    }

    public FlowContextImpl(String instanceId) {
        put("instanceId", (instanceId == null ? "" : instanceId));
        put("context", this);
    }

    // --- serialization ---

    /**
     * Coerces a trace object then restores the per-graph records, which
     * bean-coercion cannot populate (final field).
     */
    private static FlowTrace restoreTrace(JsonObject traceObj) {
        FlowTrace trace = JsonUtils.coerce(traceObj, FlowTrace.class);
        JsonObject records = traceObj.getObject("lastRecords");
        if (records != null) {
            for (Map.Entry<String, Object> e : records.toMap().entrySet()) {
                // toMap() yields plain LinkedHashMaps for nested objects
                if (e.getValue() instanceof Map<?, ?> recordMap) {
                    trace.restoreRecord(
                        e.getKey(),
                        JsonUtils.coerce(recordMap, NodeRecord.class)
                    );
                }
            }
        }
        return trace;
    }

    @SuppressWarnings("unchecked")
    public static FlowContext fromJson(String json) {
        FlowContextImpl ctx = new FlowContextImpl();
        if (json != null && !json.isEmpty()) {
            JsonObject oNode = JsonUtils.parseObject(json);
            if (oNode.containsKey("stopped")) {
                ctx.stopped = oNode.getBoolean("stopped");
            }
            if (oNode.containsKey("data")) {
                JsonObject dataObj = oNode.getObject("data");
                if (dataObj != null) {
                    ctx.data.putAll(dataObj.toMap());
                }
            }
            if (oNode.containsKey("trace")) {
                // try object first (current format), then string (legacy)
                JsonObject traceObj = oNode.getObject("trace");
                if (traceObj != null) {
                    ctx.trace = restoreTrace(traceObj);
                } else {
                    String traceStr = oNode.getString("trace");
                    if (traceStr != null && !traceStr.isEmpty()) {
                        ctx.trace = restoreTrace(JsonUtils.parseObject(traceStr));
                    }
                }
            }
            if (oNode.containsKey("rootGraphId")) {
                String rootGraphId = oNode.getString("rootGraphId");
                if (rootGraphId != null && ctx.trace != null) {
                    ctx.trace.setRootGraphId(rootGraphId);
                }
            }
        }
        return ctx;
    }

    @Override
    public String toJson() {
        JsonObject oNode = JsonUtils.object();
        oNode.put("stopped", stopped);
        // exclude the self-reference "context" key from data
        JsonObject dataObj = JsonUtils.object();
        data.forEach((k, v) -> {
            if (!"context".equals(k)) {
                dataObj.put(k, v);
            }
        });
        oNode.put("data", dataObj);
        if (trace != null) {
            oNode.put("trace", JsonUtils.coerce(trace, JsonObject.class));
            oNode.put("rootGraphId", trace.getRootGraphId());
        }
        return JsonUtils.stringify(oNode);
    }

    // --- flow control ---

    @Override
    public void interrupt() {
        if (exchanger != null) {
            exchanger.interrupt();
        }
    }

    @Override
    public void stop() {
        if (exchanger != null) {
            exchanger.stop();
        }
        // Serialization reads the field directly — keep it in sync even when
        // this context is used standalone (no exchanger attached).
        stopped = true;
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    @Override
    public void stopped(boolean stopped) {
        this.stopped = stopped;
    }

    // --- trace ---

    @Override
    public FlowTrace trace() { return trace; }

    @Override
    public FlowContext enableTrace(boolean enable) {
        trace.enable(enable);
        return this;
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
        // another thread won the race — get its instance
        return (FlowEventBus) EVENT_BUS.getAcquire(this);
    }

    @Override
    public NodeRecord lastRecord() {
        return trace.lastRecord(null);
    }

    @Override
    public String lastNodeId() {
        return trace.lastNodeId(null);
    }

    // --- data ---

    @Override
    public Map<String, Object> data() { return data; }

    // --- internal ---

    @Override
    public FlowExchanger exchanger() { return exchanger; }

    @Override
    public void exchanger(FlowExchanger exchanger) { this.exchanger = exchanger; }

    // --- data access overrides for fluent return ---

    @Override
    public FlowContext put(String key, Object value) {
        if (value != null) data.put(key, value);
        return this;
    }

    @Override
    public FlowContext putIfAbsent(String key, Object value) {
        if (value != null) data.putIfAbsent(key, value);
        return this;
    }

    @Override
    public FlowContext putAll(Map<String, Object> vars) {
        data.putAll(vars);
        return this;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T computeIfAbsent(String key, Function<String, T> mappingFunction) {
        return (T) data.computeIfAbsent(key, mappingFunction);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getAs(String key) {
        return (T) data.get(key);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOrDefault(String key, T def) {
        return (T) data.getOrDefault(key, def);
    }

    @Override
    public void remove(String key) {
        data.remove(key);
    }

    @Override
    public String getInstanceId() {
        return getAs("instanceId");
    }
}
