package com.jujin.freeway.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.error.Failure;
import com.jujin.freeway.commons.validation.ValidationResult;
import com.jujin.freeway.http.body.BodyTooLargeException;
import com.jujin.freeway.http.body.MultipartException;
import com.jujin.freeway.http.body.UnsupportedMediaTypeException;
import org.junit.jupiter.api.Test;

/** HTTP request rejections are known, non-retryable failures. */
class HttpFailureClassificationTest {

    @Test
    void requestRejectionsHaveAKnownOutcomeAndAreNotRetried() {
        ValidationResult result = new ValidationResult();
        result.addError("name", "required", null);
        assertKnown(new ValidationException(result));
        assertKnown(new BodyTooLargeException(16));
        assertKnown(new MultipartException("bad", new IllegalArgumentException("x")));
        assertKnown(new UnsupportedMediaTypeException("bad type"));
    }

    private static void assertKnown(RuntimeException failure) {
        assertTrue(failure instanceof Failure, failure.getClass().getName());
        Failure classified = (Failure) failure;
        assertFalse(classified.retryable(), failure.getClass().getName());
        assertFalse(classified.outcomeUnknown(), failure.getClass().getName());
    }
}
