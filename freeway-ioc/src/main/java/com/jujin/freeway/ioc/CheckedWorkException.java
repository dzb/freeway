package com.jujin.freeway.ioc;

/** Carries a checked exception through the Supplier-based scope body. */
final class CheckedWorkException extends RuntimeException {
    private final Exception work;

    CheckedWorkException(Exception work) {
        super(null, work, false, false);
        this.work = work;
    }

    Exception work() {
        return work;
    }
}
