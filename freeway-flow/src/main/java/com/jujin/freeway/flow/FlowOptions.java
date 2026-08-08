package com.jujin.freeway.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Flow execution options
 *
 * @author noear
 * @since 3.8.1
 */
public class FlowOptions {
    // NOTE: no shared DEFAULT instance on purpose — a mutable static would
    // let interceptorAdd() silently mutate process-global state. Create a
    // fresh instance per evaluation instead (all public eval() overloads
    // build one internally).
    private final List<RankedInterceptor> interceptorList = new ArrayList<>();

    public List<RankedInterceptor> getInterceptorList() {
        return interceptorList;
    }

    protected void interceptorAdd(List<RankedInterceptor> interceptors) {
        interceptorList.addAll(interceptors);
        if (!interceptorList.isEmpty()) {
            Collections.sort(interceptorList);
        }
    }

    public FlowOptions interceptorAdd(FlowInterceptor interceptor) {
        return interceptorAdd(interceptor, 0);
    }

    public FlowOptions interceptorAdd(FlowInterceptor interceptor, int index) {
        interceptorList.add(new RankedInterceptor(interceptor, index));
        if (!interceptorList.isEmpty()) {
            Collections.sort(interceptorList);
        }
        return this;
    }

    /**
     * Interceptor wrapper with priority (replaces solon's RankEntity)
     */
    public record RankedInterceptor(FlowInterceptor interceptor, int index)
            implements Comparable<RankedInterceptor> {
        @Override
        public int compareTo(RankedInterceptor o) {
            return Integer.compare(index, o.index);
        }
    }
}
