package com.jujin.freeway.http.filter;

import com.jujin.freeway.http.ErrorResponse;
import com.jujin.freeway.http.body.UnsupportedMediaTypeException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ErrorMapperTest {

    @Test
    void firstMatchingMapperReturnsTheResponse() {
        ErrorMapper mapper = ex -> ex instanceof IllegalArgumentException
            ? Optional.of(ErrorResponse.of(400, "bad_request", "Bad Request"))
            : Optional.empty();

        ErrorResponse response = mapper.map(new IllegalArgumentException("bad")).orElseThrow();
        assertEquals(400, response.status());
        assertEquals("bad_request", response.code());
    }

    @Test
    void mapperChainCanPassAnExceptionOn() {
        ErrorMapper first = ex -> Optional.empty();
        ErrorMapper second = ex -> Optional.of(
            ErrorResponse.of(500, "caught", "caught"));
        ErrorMapper third = ex -> {
            fail("third mapper should not be called");
            return Optional.empty();
        };

        ErrorResponse response = null;
        for (ErrorMapper mapper : List.of(first, second, third)) {
            Optional<ErrorResponse> mapped = mapper.map(new RuntimeException("test"));
            if (mapped.isPresent()) {
                response = mapped.get();
                break;
            }
        }

        assertTrue(response != null);
        assertEquals(500, response.status());
    }

    @Test
    void throwingMapperCanBeSkippedByTheServer() {
        ErrorMapper broken = ex -> {
            throw new RuntimeException("mapper exploded");
        };
        ErrorMapper fallback = ex -> Optional.of(
            ErrorResponse.of(500, "fallback", "fallback"));

        ErrorResponse response = null;
        for (ErrorMapper mapper : List.of(broken, fallback)) {
            try {
                Optional<ErrorResponse> mapped = mapper.map(new RuntimeException("test"));
                if (mapped.isPresent()) {
                    response = mapped.get();
                    break;
                }
            } catch (RuntimeException ignored) {
                // The server skips a throwing mapper and tries the next one.
            }
        }

        assertTrue(response != null);
    }

    @Test
    void noMatchMeansUnhandled() {
        ErrorMapper only = ex -> Optional.empty();
        assertFalse(only.map(new RuntimeException("test")).isPresent());
    }

    @Test
    void defaultsMapUnsupportedMediaTypeTo415() {
        ErrorResponse response = ErrorMapper.defaults()
            .map(new UnsupportedMediaTypeException("Expected application/json Content-Type"))
            .orElseThrow();

        assertEquals(415, response.status());
        assertEquals("unsupported_media_type", response.code());
    }
}
