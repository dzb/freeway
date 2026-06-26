package com.jujin.freeway.flow;

import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 流上下文实现
 *
 * @author noear
 * @since 3.5
 */
public class FlowContextImpl implements FlowContext {
    private final Map<String, Object> data = new ConcurrentHashMap<>();
    private FlowTrace trace = new FlowTrace();
    private volatile FlowExchanger exchanger;
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
                JsonObject traceObj = oNode.getObject("trace");
                if (traceObj != null) {
                    ctx.trace = JsonUtils.coerce(traceObj, FlowTrace.class);
                }
            }
        }
        return ctx;
    }

    @Override
    public String toJson() {
        JsonObject oNode = JsonUtils.object();
        oNode.put("stopped", stopped);
        oNode.put("data", data);
        if (trace != null) {
            oNode.put("trace", JsonUtils.stringify(trace));
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
        if (eventBus == null) {
            eventBus = new FlowEventBus();
        }
        return eventBus;
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
