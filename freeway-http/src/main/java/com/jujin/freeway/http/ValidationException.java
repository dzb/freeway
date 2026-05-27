package com.jujin.freeway.http;

import com.jujin.freeway.commons.validation.ValidationResult;

public class ValidationException extends RuntimeException {
    private final transient ValidationResult result;

    public ValidationException(ValidationResult result) {
        super(result.toString());
        this.result = result;
    }

    public ValidationResult getResult() {
        return result;
    }
}
