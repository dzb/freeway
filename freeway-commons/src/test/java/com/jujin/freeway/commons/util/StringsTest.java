package com.jujin.freeway.commons.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StringsTest {

    @Test
    void camelToSnakeHandlesAcronymsAndBoundaries() {
        assertEquals("http_server", Strings.camelToSnake("HTTPServer"));
        assertEquals("url_value", Strings.camelToSnake("URLValue"));
        assertEquals("my_url", Strings.camelToSnake("myURL"));
        assertEquals("camel_case", Strings.camelToSnake("camelCase"));
        assertEquals("simple", Strings.camelToSnake("simple"));
        assertEquals("value2_name", Strings.camelToSnake("value2Name"));
        assertEquals("", Strings.camelToSnake(""));
        assertNull(Strings.camelToSnake(null));
    }

    @Test
    void camelToSnakeDoesNotDoubleUnderscoreSeparators() {
        // Regression: a separator was inserted before an uppercase letter even
        // when the previous character was already '_'.
        assertEquals("user_name", Strings.camelToSnake("user_Name"));
        assertEquals("foo_bar", Strings.camelToSnake("foo_Bar"));
    }
}
