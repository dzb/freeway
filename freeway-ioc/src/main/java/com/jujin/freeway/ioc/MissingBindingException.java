package com.jujin.freeway.ioc;

/**
 * Thrown by {@link Container#get} when no binding matches the requested
 * type/id/markers.
 *
 * <p>Extends {@link IllegalArgumentException} for source compatibility with
 * existing catch sites; catch this type to handle a missing binding
 * structurally instead of matching on exception message text. The sibling
 * {@link AmbiguousBindingException} covers the too-many-matches case.
 */
public final class MissingBindingException extends IllegalArgumentException {

    public MissingBindingException(String message) {
        super(message);
    }
}
