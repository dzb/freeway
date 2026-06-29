package com.jujin.freeway.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 流执行选项
 *
 * @author noear
 * @since 3.8.1
 */
public class FlowOptions {
    public static final FlowOptions DEFAULT = new FlowOptions();

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
     * 带优先级的拦截器包装（替代 solon 的 RankEntity）
     */
    public record RankedInterceptor(FlowInterceptor interceptor, int index)
            implements Comparable<RankedInterceptor> {
        @Override
        public int compareTo(RankedInterceptor o) {
            return Integer.compare(index, o.index);
        }
    }
}
