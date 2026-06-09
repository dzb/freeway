package com.jujin.freeway.db;

/**
 * {@link Database#execute(String, Object...)} 的返回值。
 * <p>
 * 同时携带影响行数和自增键信息，避免为获取 ID 而额外查询。
 * <p>
 * 典型用法：
 * <pre>{@code
 * // 插入并获取自增 ID
 * long id = db.execute("INSERT INTO users (name) VALUES (?)", "john").id();
 *
 * // 只关心影响行数
 * int rows = db.execute("UPDATE users SET status = ? WHERE id = ?", 1, id).rows();
 * }</pre>
 */
public record ExecuteResult(int rows, long id) {

    /** 是否有自增键返回。 */
    public boolean hasId() {
        return id != 0L;
    }

    @Override
    public String toString() {
        if (id == 0L) {
            return "ExecuteResult[rows=" + rows + "]";
        }
        return "ExecuteResult[rows=" + rows + ", id=" + id + "]";
    }
}
