package com.jujin.freeway.commons.validation;

public record ValidationError(String field, String message, Object rejectedValue) {

    @Override
    public String toString() {
        return field + ": " + message + (rejectedValue != null ? " (rejected: " + rejectedValue + ")" : "");
    }
}
