package com.jujin.freeway.db;

import java.util.List;
import java.util.Map;

/**
 * Batch executor for INSERT / UPDATE / DELETE statements.
 *
 * <p>Add rows with {@link #rowsRaw(Object[]...)} or {@link #rowsNamed(List)} then call {@link #execute()}:
 * <pre>{@code
 * List<ExecuteResult> results = db.batch("INSERT INTO t (a, b) VALUES (?, ?)")
 *     .rowsRaw(new Object[]{1, "a"}, new Object[]{2, "b"})
 *     .execute();
 * }</pre>
 */
public interface BatchQuery {
    BatchQuery rowsRaw(Object[]... rows);

    BatchQuery rowsNamed(List<Map<String, Object>> rows);

    List<ExecuteResult> execute();
}
