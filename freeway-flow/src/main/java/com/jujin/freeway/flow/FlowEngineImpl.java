package com.jujin.freeway.flow;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流引擎实现。
 *
 * <p>迁移说明：
 * <ul>
 *   <li>保留节点遍历、条件分支、子图调用和拦截器链逻辑，但把执行态控制显式收敛在引擎实例内。</li>
 *   <li>驱动器通过 {@code Map<String, FlowDriver>} 注入，按 graph 的 driver 名称（null/"" → "default"）匹配。</li>
 *   <li>暂停、终止、回退等标志只属于单次执行状态，方便恢复和短暂中断后继续执行。</li>
 * </ul>
 * 这样做是为了让移植后的引擎仍保留原行为，同时满足 Freeway 的显式装配模型。</p>
 *
 * @author noear
 * @since 3.0
 */
public class FlowEngineImpl implements FlowEngine {
    protected final Map<String, Graph> graphMap;
    protected final Map<String, FlowDriver> drivers;
    protected final FlowMarkerIndex markerIndex = new FlowMarkerIndex();
    protected final List<FlowOptions.RankedInterceptor> interceptorList;

    public FlowEngineImpl(Map<String, FlowDriver> drivers) {
        this.drivers = new HashMap<>(Objects.requireNonNull(drivers, "drivers"));
        this.graphMap = new HashMap<>();
        this.interceptorList = new ArrayList<>();
    }

    @Override
    public FlowDriver getDriver(Graph graph) {
        Objects.requireNonNull(graph, "graph is null");
        String driverName = graph.getDriver();
        final String lookup = (driverName == null || driverName.isBlank()) ? "default" : driverName;
        FlowDriver driver = drivers.get(lookup);
        if (driver == null) {
            throw new IllegalArgumentException(
                "No driver found for: '" + lookup + "'. " +
                "Register drivers via newInstance(Map.of(\"id\", driver)) or " +
                "binder.contribute(FlowDriver.class).add(id, driver)");
        }
        return driver;
    }

    @Override
    public void register(TaskComponent handler) {
        if (handler != null) {
            markerIndex.register(handler);
        }
    }

    @Override
    public FlowMarkerIndex markerIndex() {
        return markerIndex;
    }

    // --- interceptor ---

    @Override
    public void addInterceptor(FlowInterceptor interceptor, int index) {
        interceptorList.add(new FlowOptions.RankedInterceptor(interceptor, index));
        if (!interceptorList.isEmpty()) Collections.sort(interceptorList);
    }

    @Override
    public void removeInterceptor(FlowInterceptor interceptor) {
        interceptorList.removeIf(r -> r.interceptor() == interceptor);
    }

    // --- graph ---

    @Override
    public void load(Graph graph) {
        String id = Objects.requireNonNull(graph, "graph").getId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Graph id must not be blank");
        }
        if (graphMap.containsKey(id)) {
            throw new IllegalArgumentException("Graph already loaded: " + id);
        }
        graphMap.put(id, graph);
    }

    @Override
    public void unload(String graphId) { graphMap.remove(graphId); }

    @Override
    public Collection<Graph> getGraphs() { return graphMap.values(); }

    @Override
    public Graph getGraph(String graphId) { return graphMap.get(graphId); }

    // ==================== core eval ====================

    @Override
    public void eval(Graph graph, FlowExchanger exchanger, FlowOptions options) throws FlowException {
        Node lastNode = exchanger.context().trace().lastNode(graph);
        FlowExchanger bak = exchanger.context().exchanger();

        // Defensive copy — never mutate the caller's FlowOptions instance
        FlowOptions opts = new FlowOptions();
        if (options != null) {
            opts.interceptorAdd(options.getInterceptorList());
        }
        opts.interceptorAdd(interceptorList);

        try {
            exchanger.context().exchanger(exchanger);
            exchanger.context().stopped(false);
            new FlowInvocation(exchanger, opts, lastNode, this::evalDo).invoke();
        } finally {
            exchanger.context().exchanger(bak);
        }
    }

    protected void evalDo(FlowInvocation inv, FlowOptions options) throws FlowException {
        node_run(inv.getExchanger(), options, inv.getStartNode().getGraph().getStart(), inv.getStartNode());
    }

    // ==================== lifecycle hooks ====================

    protected boolean onNodeStart(FlowExchanger exchanger, FlowOptions options, Node node) {
        if (exchanger.isReverting()) return true;

        for (var ri : options.getInterceptorList()) {
            ri.interceptor().onNodeStart(exchanger.context(), node);
        }
        exchanger.driver().onNodeStart(exchanger, node);

        if (exchanger.isStopped()) return false;
        if (exchanger.isInterrupted()) {
            exchanger.interrupt(false);
            return false;
        }
        return true;
    }

    protected boolean onNodeEnd(FlowExchanger exchanger, FlowOptions options, Node node) {
        if (exchanger.isReverting()) return true;

        for (var ri : options.getInterceptorList()) {
            ri.interceptor().onNodeEnd(exchanger.context(), node);
        }
        exchanger.driver().onNodeEnd(exchanger, node);

        if (exchanger.isStopped()) return false;
        if (exchanger.isInterrupted()) {
            exchanger.interrupt(false);
            return false;
        }
        return true;
    }

    // ==================== condition / task ====================

    protected boolean condition_test(FlowExchanger exchanger, ConditionDesc condition, boolean def) throws FlowException {
        if (condition.isEmpty()) return def;
        try {
            return exchanger.driver().handleCondition(exchanger, condition);
        } catch (FlowException e) {
            throw e;
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e; // configuration errors — preserve original type
        } catch (Throwable e) {
            throw new FlowException("The condition handle failed: " + condition.getGraph().getId() + " / " + condition.getDescription(), e);
        }
    }

    protected boolean task_exec(FlowExchanger exchanger, FlowOptions options, Node node) throws FlowException {
        if (exchanger.isReverting()) return true;

        if (!onNodeStart(exchanger, options, node)) return false;

        if (condition_test(exchanger, node.getWhen(), true)) {
            try {
                exchanger.driver().handleTask(exchanger, node.getTask());
            } catch (FlowException e) {
                throw e;
            } catch (IllegalStateException | IllegalArgumentException e) {
                throw e; // configuration errors — preserve original type
            } catch (Throwable e) {
                throw new FlowException("The task handle failed: " + node.getGraph().getId() + " / " + node.getId(), e);
            }
        }

        if (exchanger.isStopped()) return false;
        if (exchanger.isInterrupted()) {
            exchanger.interrupt(false);
            return false;
        }

        return onNodeEnd(exchanger, options, node);
    }

    // ==================== dispatcher ====================

    protected void node_run(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) throws FlowException {
        if (node == null) return;
        if (exchanger.isStopped()) return;
        if (exchanger.isInterrupted()) {
            exchanger.interrupt(false);
            return;
        }

        if (exchanger.isReverting()) {
            if (node.getId().equals(startNode.getId())
                    && node.getGraph().getId().equals(startNode.getGraph().getId())) {
                exchanger.reverting(false);
            }
        } else {
            exchanger.recordNode(node.getGraph(), node);
        }

        if (!exchanger.isReverting()) {
            if (!exchanger.nextStep(node)) {
                exchanger.stop();
                return;
            }
        }

        switch (node.getType()) {
            case START     -> start_run(exchanger, options, node, startNode);
            case END       -> end_run(exchanger, options, node, startNode);
            case ACTIVITY  -> activity_run(exchanger, options, node, startNode);
            case INCLUSIVE -> inclusive_run(exchanger, options, node, startNode);
            case EXCLUSIVE -> exclusive_run(exchanger, options, node, startNode);
            case PARALLEL  -> parallel_run(exchanger, options, node, startNode);
            case LOOP      -> loop_run(exchanger, options, node, startNode);
        }
    }

    // ==================== START ====================

    protected void start_run(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        if (!onNodeStart(exchanger, options, node)) return;
        if (!onNodeEnd(exchanger, options, node)) return;
        for (Link l : node.getNextLinks()) {
            if (condition_test(exchanger, l.getWhen(), true)) {
                node_run(exchanger, options, l.getNextNode(), startNode);
            }
        }
    }

    // ==================== END ====================

    protected void end_run(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        if (!onNodeStart(exchanger, options, node)) return;
        onNodeEnd(exchanger, options, node);
    }

    // ==================== ACTIVITY ====================

    protected void activity_run(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        if (!task_exec(exchanger, options, node)) return;
        activity_run_out(exchanger, options, node, startNode);
    }

    protected void activity_run_out(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        for (Link l : node.getNextLinks()) {
            if (condition_test(exchanger, l.getWhen(), true)) {
                node_run(exchanger, options, l.getNextNode(), startNode);
            }
        }
    }

    // ==================== EXCLUSIVE ====================

    protected void exclusive_run(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        if (!task_exec(exchanger, options, node)) return;
        exclusive_run_out(exchanger, options, node, startNode);
    }

    protected void exclusive_run_out(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        Link defLine = null;
        for (Link l : node.getNextLinks()) {
            if (l.getWhen().isEmpty()) {
                defLine = l;
            } else if (condition_test(exchanger, l.getWhen(), false)) {
                node_run(exchanger, options, l.getNextNode(), startNode);
                return;
            }
        }
        if (defLine != null) {
            node_run(exchanger, options, defLine.getNextNode(), startNode);
        }
    }

    // ==================== INCLUSIVE ====================

    protected void inclusive_run(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        if (!inclusive_run_in(exchanger, node)) return;
        if (!task_exec(exchanger, options, node)) return;
        inclusive_run_out(exchanger, options, node, startNode);
    }

    @SuppressWarnings("unchecked")
    protected boolean inclusive_run_in(FlowExchanger exchanger, Node node) {
        Stack<Integer> stack = exchanger.temporary().stack(node.getGraph(), "inclusive_run");
        if (node.getPrevLinks().size() > 1) {
            if (!stack.isEmpty()) {
                int startSize = stack.peek();
                int inSize = exchanger.temporary().countIncr(node.getGraph(), node.getId());
                if (startSize > inSize) return false;
                stack.pop();
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    protected void inclusive_run_out(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        Stack<Integer> stack = exchanger.temporary().stack(node.getGraph(), "inclusive_run");
        List<Link> matched = new ArrayList<>();
        for (Link l : node.getNextLinks()) {
            if (condition_test(exchanger, l.getWhen(), true)) matched.add(l);
        }
        if (!matched.isEmpty()) {
            stack.push(matched.size());
            for (Link l : matched) node_run(exchanger, options, l.getNextNode(), startNode);
        }
    }

    // ==================== PARALLEL ====================

    protected void parallel_run(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        if (!parallel_run_in(exchanger, node)) return;
        if (!task_exec(exchanger, options, node)) return;
        parallel_run_out(exchanger, options, node, startNode);
    }

    protected boolean parallel_run_in(FlowExchanger exchanger, Node node) {
        int count = exchanger.temporary().countIncr(node.getGraph(), node.getId());
        return node.getPrevLinks().size() <= count;
    }

    protected void parallel_run_out(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        exchanger.temporary().countSet(node.getGraph(), node.getId(), 0);

        if (exchanger.driver().getExecutor() == null || node.getNextNodes().size() < 2) {
            for (Node n : node.getNextNodes()) node_run(exchanger, options, n, startNode);
        } else {
            CountDownLatch cdl = new CountDownLatch(node.getNextNodes().size());
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            for (Node n : node.getNextNodes()) {
                exchanger.driver().getExecutor().execute(() -> {
                    try {
                        if (errorRef.get() != null) return;
                        node_run(exchanger, options, n, startNode);
                    } catch (Throwable ex) {
                        errorRef.set(ex);
                    } finally {
                        cdl.countDown();
                    }
                });
            }
            try {
                cdl.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FlowException("Parallel execution interrupted", e);
            }
            if (errorRef.get() != null) {
                if (errorRef.get() instanceof FlowException) throw (FlowException) errorRef.get();
                throw new FlowException(errorRef.get());
            }
        }
    }

    // ==================== LOOP ====================

    @SuppressWarnings("unchecked")
    protected void loop_run(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        if (node.getMetaAsString("$for") == null) {
            if (!loop_run_in(exchanger, node)) return;
            if (!task_exec(exchanger, options, node)) return;
            activity_run_out(exchanger, options, node, startNode);
        } else {
            if (!task_exec(exchanger, options, node)) return;
            loop_run_out(exchanger, options, node, startNode);
        }
    }

    @SuppressWarnings("unchecked")
    protected boolean loop_run_in(FlowExchanger exchanger, Node node) {
        Stack<Iterator> stack = exchanger.temporary().stack(node.getGraph(), "loop_run");
        if (!stack.isEmpty()) {
            Iterator<?> iter = stack.peek();
            if (iter.hasNext()) return false;
            stack.pop();
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    protected void loop_run_out(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        String forKey = node.getMetaAsString("$for");
        Object inKey = node.getMeta("$in");

        Object inObj;
        if (inKey instanceof List) {
            inObj = inKey;
        } else if (inKey instanceof String inKeyStr) {
            if (!inKeyStr.contains(":") && !inKeyStr.contains("...")) {
                inObj = exchanger.context().getAs(inKeyStr);
            } else {
                inObj = Stepper.from(inKeyStr);
            }
        } else {
            throw new FlowException("The '$in' must be a list or a string");
        }

        Iterator<?> iter;
        if (inObj instanceof Iterator) iter = (Iterator<?>) inObj;
        else if (inObj instanceof Iterable) iter = ((Iterable<?>) inObj).iterator();
        else throw new FlowException(inKey + " is not a collection");

        Stack<Iterator> stack = exchanger.temporary().stack(node.getGraph(), "loop_run");
        stack.push(iter);

        while (iter.hasNext()) {
            Object item = iter.next();
            exchanger.context().put(forKey, item);
            activity_run_out(exchanger, options, node, startNode);
        }
    }
}
