package com.jujin.freeway2.db;

import java.util.List;
import java.util.Map;

public interface BatchQuery {
    BatchQuery rows(Object[]... rows);

    BatchQuery rows(List<Object[]> rows);

    BatchQuery named(List<Map<String, Object>> rows);

    int[] execute();
}
