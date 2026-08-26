package com.jujin.freeway.db;

import java.util.List;
import java.util.Map;

/**
 * Batch executor for INSERT / UPDATE / DELETE statements.
 *
 * <p>Add rows with {@link #rows(Object[]...)} or {@link #named(List)} then call {@link #execute()}:
 * <pre>{@code
 * List<ExecuteResult> results = db.batch("INSERT INTO t (a, b) VALUES (?, ?)")
 *     .rows(new Object[]{1, "a"}, new Object[]{2, "b"})
 *     .execute();
 * }</pre>
 */
public interface BatchQuery {
    BatchQuery rows(Object[]... rows);

    BatchQuery named(List<Map<String, Object>> rows);

    List<ExecuteResult> execute();
}
