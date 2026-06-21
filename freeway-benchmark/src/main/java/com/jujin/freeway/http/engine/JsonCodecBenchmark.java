package com.jujin.freeway.http.engine;

import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * JMH benchmark for {@link JsonCodecDefault} serialization and deserialization.
 *
 * <p>Covers three payload sizes that map to common Freeway response shapes:
 * <ul>
 *   <li><b>Small</b> — simple status response ({@code {"status":"ok"}})</li>
 *   <li><b>Medium</b> — single resource object (~6 fields)</li>
 *   <li><b>Large</b> — paginated list of 100 resources</li>
 * </ul>
 *
 * <p>All payloads are constructed once at {@link Setup} time so that
 * benchmark measurements reflect only the codec cost.
 */
@State(Scope.Thread)
public class JsonCodecBenchmark {

    private JsonCodecDefault codec;

    private String smallJson;
    private String mediumJson;
    private String largeJson;

    private Map<String, Object> smallObject;
    private Map<String, Object> mediumObject;
    private List<Map<String, Object>> largeObject;

    @Setup
    public void setup() {
        codec = new JsonCodecDefault();

        // --- Small: health-check / ping response ---
        smallObject = Map.of("status", "ok");
        smallJson = "{\"status\":\"ok\"}";

        // --- Medium: single resource with ~6 fields ---
        mediumObject = new LinkedHashMap<>();
        mediumObject.put("id", 42);
        mediumObject.put("name", "Alice");
        mediumObject.put("email", "alice@example.com");
        mediumObject.put("role", "admin");
        mediumObject.put("active", true);
        mediumObject.put("score", 98.5);
        mediumJson = "{\"id\":42,\"name\":\"Alice\",\"email\":\"alice@example.com\","
            + "\"role\":\"admin\",\"active\":true,\"score\":98.5}";

        // --- Large: paginated list of 100 users ---
        var users = new ArrayList<Map<String, Object>>(100);
        var jsonParts = new ArrayList<String>(100);
        for (int i = 0; i < 100; i++) {
            var u = new LinkedHashMap<String, Object>();
            u.put("id", i);
            u.put("name", "User-" + i);
            u.put("email", "user" + i + "@example.com");
            u.put("role", i % 3 == 0 ? "admin" : "user");
            u.put("active", i % 2 == 0);
            u.put("score", i * 1.5);
            users.add(u);

            jsonParts.add("{\"id\":" + i
                + ",\"name\":\"User-" + i + "\""
                + ",\"email\":\"user" + i + "@example.com\""
                + ",\"role\":\"" + (i % 3 == 0 ? "admin" : "user") + "\""
                + ",\"active\":" + (i % 2 == 0)
                + ",\"score\":" + (i * 1.5) + "}");
        }
        largeObject = users;
        largeJson = "[" + String.join(",", jsonParts) + "]";
    }

    // --- toJson (serialization) ---

    @Benchmark
    public String toJsonSmall() {
        return codec.toJson(smallObject);
    }

    @Benchmark
    public String toJsonMedium() {
        return codec.toJson(mediumObject);
    }

    @Benchmark
    public String toJsonLarge() {
        return codec.toJson(largeObject);
    }

    // --- fromJson (deserialization) ---

    @Benchmark
    @SuppressWarnings("unchecked")
    public Map<String, Object> fromJsonSmall() {
        return codec.fromJson(smallJson, Map.class);
    }

    @Benchmark
    @SuppressWarnings("unchecked")
    public Map<String, Object> fromJsonMedium() {
        return codec.fromJson(mediumJson, Map.class);
    }

    @Benchmark
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fromJsonLarge() {
        return codec.fromJson(largeJson, List.class);
    }
}
