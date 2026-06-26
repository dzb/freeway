package com.jujin.freeway.flow;

/**
 * 流异常
 *
 * @author noear
 * @since 3.0
 */
public class FlowException extends RuntimeException {
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
