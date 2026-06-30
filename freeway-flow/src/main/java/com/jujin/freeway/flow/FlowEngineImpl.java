package com.jujin.freeway.flow;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流引擎实现（核心图遍历逻辑 + 拦截器链）
 *
 * @author noear
 * @since 3.0
 */
public class FlowEngineImpl implements FlowEngine {
    protected final Map<String, Graph> graphMap;
    protected final Map<String, FlowDriver> driverMap;
    protected final Map<Class<?>, TaskComponent> typedTasks = new ConcurrentHashMap<>();
    protected FlowDriver driverDef;
    protected final List<FlowOptions.RankedInterceptor> interceptorList;

    public FlowEngineImpl(FlowDriver driver) {
        if (driver == null) {
            driver = FlowDriverDefault.getInstance();
        }
        this.driverDef = driver;
        this.graphMap = new HashMap<>();
        this.driverMap = new HashMap<>();
        this.interceptorList = new ArrayList<>();
    }

    @Override
    public FlowDriver getDriver(Graph graph) {
        Objects.requireNonNull(graph, "graph is null");
        if (graph.getDriver() == null || graph.getDriver().isEmpty()) {
            return driverDef;
        }
        FlowDriver driver = driverMap.get(graph.getDriver());
        if (driver == null) {
            throw new IllegalArgumentException("No driver found for: '" + graph.getDriver() + "'");
        }
        return driver;
    }

    @Override
    public void register(String name, FlowDriver driver) {
        if (driver != null) {
            if (name == null) driverDef = driver;
            else driverMap.put(name, driver);
        }
    }

    @Override
    public void unregister(String name) {
        if (name != null && !name.isEmpty()) driverMap.remove(name);
    }

    @Override
    public void register(Class<?> taskType, TaskComponent handler) {
        if (taskType != null && handler != null) typedTasks.put(taskType, handler);
    }

    @Override
    public Map<Class<?>, TaskComponent> typedTasks() {
        return typedTasks;
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
    public void load(Graph graph) { graphMap.put(graph.getId(), graph); }

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

        if (options == null) options = new FlowOptions();
        options.interceptorAdd(interceptorList);

        try {
            exchanger.context().exchanger(exchanger);
            exchanger.context().stopped(false);
            new FlowInvocation(exchanger, options, lastNode, this::evalDo).invoke();
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
            if (!exchanger.nextSetp(node)) {
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
            try { cdl.await(); } catch (InterruptedException ignored) {}
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
