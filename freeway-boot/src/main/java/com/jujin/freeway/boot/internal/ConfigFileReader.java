package com.jujin.freeway.boot.internal;

import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.commons.util.ByteStreams;
import com.jujin.freeway.commons.util.Maps;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
 * <p>Both entries apply the same {@link #MAX_BYTES read cap}, so the bound is
 * a property of the parser rather than of the caller: a runaway file fails
 * identically whether it sits on the classpath or in the working directory.
 *
 * <p>Properties text keeps the {@code java.util.Properties} key/value
 * syntax; JSON objects are nested freely and flattened to dotted keys
 * ({@code {"db": {"host": "x"}}} → {@code db.host=x}). A blank JSON document
 * means "no config", mirroring an empty {@code application.properties}.
 */
final class ConfigFileReader {

    /**
     * Read cap for every config source: a file that exceeds it fails loudly
     * instead of exhausting memory. High enough that only a runaway file hits
     * it — the tests derive their boundary input from this constant, so
     * lowering it cannot leave them passing while no longer exercising the cap.
     */
    static final long MAX_BYTES = 16L * 1024 * 1024;

    private ConfigFileReader() {}

    /** Reads a filesystem config file, dispatching by extension. */
    public static Map<String, String> read(Path file) throws IOException {
        String name = file.getFileName() != null ? file.getFileName().toString() : "";
        try (InputStream in = Files.newInputStream(file)) {
            return read(name, in);
        }
    }

    /** Reads a config source, dispatching by name extension (see the class
     *  javadoc). The stream is fully consumed and closed by this call, and
     *  only {@link #MAX_BYTES} are ever read from it. */
    public static Map<String, String> read(String name, InputStream in) throws IOException {
        try (InputStream bounded = ByteStreams.bounded(in, MAX_BYTES, name)) {
            return parse(name, bounded);
        }
    }

    /** Dispatches on the name extension; see the class javadoc. */
    private static Map<String, String> parse(String name, InputStream in) throws IOException {
        if (name.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return json(new String(in.readAllBytes(), StandardCharsets.UTF_8), name);
        }
        Properties props = new Properties();
        props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
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
            // The JSON parser strips a leading BOM itself, so this is not
            // needed to parse. It is what makes a document consisting only of
            // a BOM count as blank below — "no config", like an empty file —
            // instead of failing as malformed.
            text = text.substring(1);
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
