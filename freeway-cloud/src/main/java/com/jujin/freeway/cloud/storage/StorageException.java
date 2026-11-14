package com.jujin.freeway.cloud.storage;
import com.jujin.freeway.commons.error.Failure;

/**
 * Object storage failure: invalid bucket/key (path traversal), I/O errors.
 */
public class StorageException extends RuntimeException implements Failure {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
