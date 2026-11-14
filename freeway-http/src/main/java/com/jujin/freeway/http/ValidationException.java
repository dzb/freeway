package com.jujin.freeway.http;
import com.jujin.freeway.commons.error.Failure;

import com.jujin.freeway.commons.validation.ValidationResult;

public final class ValidationException extends RuntimeException implements Failure {
    private final transient ValidationResult result;

    public ValidationException(ValidationResult result) {
        super(result.toString());
        this.result = result;
    }

    public ValidationResult result() {
        return result;
    }

    @Override
    public boolean outcomeUnknown() {
        return false; // the request was rejected before the handler produced an effect
    }
}
