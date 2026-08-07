package com.jujin.freeway.flow;

import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 流上下文实现。
 *
 * <p>迁移说明：
 * <ul>
 *   <li>把上下文收敛为单次 flow 执行期对象，不再把它当成可跨运行持久化的状态容器。</li>
 *   <li>{@code exchanger}、{@code eventBus}、{@code trace} 都只保存执行态，方便回放和调试，但不作为稳定 JSON 协议的一部分。</li>
 *   <li>序列化时显式排除 {@code context} 自引用，并兼容 {@code trace} 的对象/字符串两种历史格式。</li>
 * </ul>
 * 这样做是为了保留受控恢复能力，同时避免把运行时对象写进稳定协议。</p>
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
                    ctx.trace = JsonUtils.coerce(traceObj, FlowTrace.class);
                } else {
                    String traceStr = oNode.getString("trace");
                    if (traceStr != null && !traceStr.isEmpty()) {
                        ctx.trace = JsonUtils.coerce(
                            JsonUtils.parseObject(traceStr), FlowTrace.class);
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
