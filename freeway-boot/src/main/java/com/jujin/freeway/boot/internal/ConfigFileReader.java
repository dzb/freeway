package com.jujin.freeway.boot.internal;

import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.commons.util.Maps;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * The one config-file parser. A source is read as JSON when its name ends
 * with {@code .json} (case-insensitive) and as {@code java.util.Properties}
 * otherwise; both formats are read as UTF-8. The two {@code read} entries are
 * mirrors — a filesystem path, or an already-open stream named by its
 * resource name — and the only public surface: the classpath cascade, the
 * working-directory base files, the {@code freeway.config.file} extras and
 * the hot-reload re-read all go through them, so a file parses identically
 * no matter where it lives (classpath, working directory) or when it is
 * read (startup, hot reload).
 *
 * <p>Properties text keeps the {@code java.util.Properties} key/value
 * syntax; JSON objects are nested freely and flattened to dotted keys
 * ({@code {"db": {"host": "x"}}} → {@code db.host=x}). A blank JSON document
 * means "no config", mirroring an empty {@code application.properties}.
 */
public final class ConfigFileReader {

    private ConfigFileReader() {}

    /** Reads a filesystem config file, dispatching by extension. */
    public static Map<String, String> read(Path file) throws IOException {
        String name = file.getFileName() != null ? file.getFileName().toString() : "";
        try (InputStream in = Files.newInputStream(file)) {
            return read(name, in);
        }
    }

    /** Reads a config source, dispatching by name extension (see the class
     *  javadoc). The stream is fully consumed by this call. */
    public static Map<String, String> read(String name, InputStream in) throws IOException {
        if (name.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return json(new String(in.readAllBytes(), StandardCharsets.UTF_8), name);
        }
        return properties(in);
    }

    private static Map<String, String> properties(InputStream in) throws IOException {
        return properties(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static Map<String, String> properties(Reader reader) throws IOException {
        Properties props = new Properties();
        props.load(reader);
        Map<String, String> values = new LinkedHashMap<>();
        props.forEach((k, v) -> values.put(String.valueOf(k), String.valueOf(v)));
        return values;
    }

    /**
     * Parses JSON config text: a UTF-8 BOM is tolerated, a blank document
     * contributes nothing, nested objects flatten to dotted keys. Malformed
     * JSON fails with the source named in the message.
     */
    private static Map<String, String> json(String text, String name) {
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1); // strip UTF-8 BOM like JsonParser
        }
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return Maps.flatten(JsonUtils.parseObject(text).toMap(), ".");
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unable to load " + name, e);
        }
    }
}
