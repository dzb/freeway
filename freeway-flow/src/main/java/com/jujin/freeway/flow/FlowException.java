package com.jujin.freeway.flow;

/**
 * Flow exception
 *
 * @author noear
 * @since 3.0
 */
public class FlowException extends RuntimeException {

    /** Shared message template for task-handling failures. */
    public static final String TASK_FAILED = "The task handle failed";

    public FlowException(String message) {
        super(message);
    }

    public FlowException(String message, Throwable cause) {
        super(message, cause);
    }

    public FlowException(Throwable cause) {
        super(cause);
    }
}
