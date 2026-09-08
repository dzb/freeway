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
 * The one config-file parser: {@code .json} files are read as JSON and
 * flattened to dotted keys, everything else as {@code java.util.Properties}.
 * Shared by the boot config wiring — the classpath cascade, the hot-reload
 * file tier, and the bootstrap log cascade (through the boot-supplied
 * {@code LogConfigHomes}) — so a file parses identically no matter where it
 * lives (classpath, working directory, {@code freeway.config.file}) or which
 * cascade reads it.
 *
 * <p>Both formats are read as UTF-8. Properties text keeps the
 * {@code java.util.Properties} key/value syntax; JSON objects are nested
 * freely and flattened ({@code {"db": {"host": "x"}}} → {@code db.host=x}).
 * A blank JSON document means "no config", mirroring an empty
 * {@code application.properties}.
 */
public final class ConfigFileReader {

    private ConfigFileReader() {}

    /**
     * Reads a filesystem config file, dispatching by extension
     * (case-insensitive): {@code .json} parses as JSON, anything else as
     * properties.
     */
    public static Map<String, String> read(Path file) throws IOException {
        String name = file.getFileName() != null ? file.getFileName().toString() : "";
        if (name.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return json(Files.readString(file, StandardCharsets.UTF_8), file.toString());
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return properties(reader);
        }
    }

    /** Parses properties text into a string-keyed map. */
    public static Map<String, String> properties(Reader reader) throws IOException {
        Properties props = new Properties();
        props.load(reader);
        Map<String, String> values = new LinkedHashMap<>();
        props.forEach((k, v) -> values.put(String.valueOf(k), String.valueOf(v)));
        return values;
    }

    /** Parses a classpath properties resource (UTF-8). */
    public static Map<String, String> properties(InputStream in) throws IOException {
        return properties(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /**
     * Parses JSON config text: a UTF-8 BOM is tolerated, a blank document
     * contributes nothing, nested objects flatten to dotted keys. Malformed
     * JSON fails with the source named in the message.
     */
    public static Map<String, String> json(String text, String name) {
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

    /** Parses a classpath JSON resource. */
    public static Map<String, String> json(InputStream in, String name) throws IOException {
        return json(new String(in.readAllBytes(), StandardCharsets.UTF_8), name);
    }
}
