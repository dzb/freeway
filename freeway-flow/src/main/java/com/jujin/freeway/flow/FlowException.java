package com.jujin.freeway.flow;
import com.jujin.freeway.commons.error.Failure;

/**
 * Flow exception
 */
public class FlowException extends RuntimeException implements Failure {

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
