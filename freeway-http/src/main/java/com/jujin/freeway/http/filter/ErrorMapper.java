package com.jujin.freeway.http.filter;

import com.jujin.freeway.http.ErrorResponse;
import com.jujin.freeway.http.HttpStatus;
import com.jujin.freeway.http.ValidationException;
import com.jujin.freeway.http.body.BodyTooLargeException;
import com.jujin.freeway.http.body.MultipartException;
import com.jujin.freeway.http.body.UnsupportedMediaTypeException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps an exception to a stable {@link ErrorResponse} without writing to the
 * response. The server is the only writer, so a mapper cannot partially commit
 * a response and the chain can try the next mapper safely.
 */
@FunctionalInterface
public interface ErrorMapper {

    Optional<ErrorResponse> map(Exception failure);

    /**
     * The framework's fallback mappings, consulted after application mappers.
     * Unknown exceptions return {@link Optional#empty()} so the server can
     * produce its final 500 response.
     */
    static ErrorMapper defaults() {
        return ex -> {
            if (ex instanceof BodyTooLargeException) {
                return Optional.of(ErrorResponse.of(
                        HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large",
                        "Payload Too Large")
                    .withDetails(Map.of("message", String.valueOf(ex.getMessage()))));
            }
            if (ex instanceof MultipartException) {
                return Optional.of(ErrorResponse.of(
                    HttpStatus.BAD_REQUEST, "invalid_multipart",
                    "Invalid Multipart Request"));
            }
            if (ex instanceof UnsupportedMediaTypeException) {
                return Optional.of(ErrorResponse.of(
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type",
                        "Unsupported Media Type")
                    .withDetails(Map.of("message", String.valueOf(ex.getMessage()))));
            }
            if (ex instanceof ValidationException ve) {
                List<Map<String, String>> errors = ve.result().errors().stream()
                    .map(e -> Map.of("field", e.field(), "message", e.message()))
                    .toList();
                return Optional.of(ErrorResponse.of(
                        HttpStatus.BAD_REQUEST, "validation_failed",
                        "Validation Failed")
                    .withDetails(Map.of("errors", errors)));
            }
            return Optional.empty();
        };
    }
}
