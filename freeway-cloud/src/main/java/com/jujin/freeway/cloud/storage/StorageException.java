package com.jujin.freeway.cloud.storage;

/**
 * Object storage failure: invalid bucket/key (path traversal), I/O errors.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
