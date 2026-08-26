package com.jujin.freeway.ioc;

/**
 * Thrown when more than one binding matches a lookup and none is the unique
 * primary — the ambiguity counterpart of {@link MissingBindingException}.
 * Covers: multiple services matching a type (or type+id) without a primary,
 * and multiple primaries for the same type.
 *
 * <p>Extends {@link IllegalArgumentException} for source compatibility with
 * existing catch sites; catch this type to handle resolution ambiguity
 * structurally instead of matching on exception message text. The sibling
 * {@code MissingBindingException} covers the no-match case; registration-time
 * duplicates remain {@link IllegalStateException} (a composition error, not a
 * lookup outcome).
 */
public final class AmbiguousBindingException extends IllegalArgumentException {

    public AmbiguousBindingException(String message) {
        super(message);
    }
}
