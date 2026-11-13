package com.jujin.freeway.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.jujin.freeway.http.internal.HttpHeaders;

/**
 * One implementation of the request read side. The HTTP exchange and an
 * upgraded WebSocket session both compose this object, so query/header lookup,
 * header-name canonicalization and path-variable snapshots cannot diverge.
 *
 * <p>The HTTP form reads query/header snapshots from the transport on demand:
 * an engine may still be filling a test transport before the handler runs, and
 * the exchange must not freeze a prefix. The WebSocket form snapshots the
 * upgrade request once and canonicalizes header names to lowercase. Path
 * variables are the one mutable part, written by the router through
 * {@link #setPathVars(Map)} before the handler runs.</p>
 */
public final class RequestViewSupport implements RequestView {

    private final String method;
    private final String path;
    private final Supplier<Map<String, List<String>>> queryParams;
    private final Supplier<Map<String, List<String>>> headers;
    private final Map<String, String> pathVariables = new LinkedHashMap<>();

    public RequestViewSupport(
            String method,
            String path,
            Map<String, List<String>> queryParams,
            Map<String, List<String>> headers) {
        this(method, path, Map.of(), queryParams, headers);
    }

    public RequestViewSupport(
            String method,
            String path,
            Map<String, String> pathVariables,
            Map<String, List<String>> queryParams,
            Map<String, List<String>> headers) {
        this(method, path, new Snapshots(
            copyMultiValue(queryParams), copyHeaders(headers)));
        if (pathVariables != null) {
            this.pathVariables.putAll(pathVariables);
        }
    }

    private record Snapshots(
            Map<String, List<String>> query,
            Map<String, List<String>> headers) {}

    private RequestViewSupport(String method, String path, Snapshots snapshots) {
        this(method, path,
            () -> snapshots.query(),
            () -> snapshots.headers());
    }

    /** Live form: the maps are read at access time, never frozen at construction. */
    public RequestViewSupport(
            String method,
            String path,
            Supplier<Map<String, List<String>>> queryParams,
            Supplier<Map<String, List<String>>> headers) {
        this.method = Objects.requireNonNull(method, "method");
        this.path = Objects.requireNonNull(path, "path");
        this.queryParams = Objects.requireNonNull(queryParams, "queryParams");
        this.headers = Objects.requireNonNull(headers, "headers");
    }

    private static Map<String, List<String>> copyMultiValue(
            Map<String, List<String>> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, List<String>> copy = new LinkedHashMap<>(source.size());
        source.forEach((key, values) -> {
            if (key != null && values != null) {
                copy.put(key, List.copyOf(values));
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, List<String>> copyHeaders(
            Map<String, List<String>> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, List<String>> copy = new LinkedHashMap<>(source.size());
        source.forEach((key, values) -> {
            if (key != null && values != null) {
                String canonical = key.toLowerCase(Locale.ROOT);
                copy.computeIfAbsent(canonical, ignored -> new ArrayList<>(values.size()))
                    .addAll(values);
            }
        });
        Map<String, List<String>> frozen = new LinkedHashMap<>(copy.size());
        copy.forEach((key, values) -> frozen.put(key, List.copyOf(values)));
        return Collections.unmodifiableMap(frozen);
    }

    private Map<String, List<String>> queryMap() {
        Map<String, List<String>> values = queryParams.get();
        return values != null ? values : Map.of();
    }

    private Map<String, List<String>> headerMap() {
        Map<String, List<String>> values = headers.get();
        return values != null ? values : Map.of();
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public Optional<String> queryParam(String name) {
        List<String> values = queryMap().get(name);
        return values != null && !values.isEmpty()
            ? Optional.of(values.getFirst())
            : Optional.empty();
    }

    @Override
    public List<String> queryParams(String name) {
        return queryMap().getOrDefault(name, List.of());
    }

    @Override
    public Map<String, List<String>> queryParams() {
        return queryMap();
    }

    @Override
    public Optional<String> header(String name) {
        List<String> values = headerValues(name);
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    @Override
    public List<String> headers(String name) {
        return headerValues(name);
    }

    private List<String> headerValues(String name) {
        String requested = Objects.requireNonNull(name, "name");
        Map<String, List<String>> source = headerMap();
        List<String> direct = source.get(requested.toLowerCase(Locale.ROOT));
        if (direct != null) return direct;
        return HttpHeaders.headerValues(source, requested);
    }

    @Override
    public Map<String, List<String>> headers() {
        return headerMap();
    }

    @Override
    public Optional<String> pathVar(String name) {
        return Optional.ofNullable(pathVariables.get(name));
    }

    @Override
    public Map<String, String> pathVars() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pathVariables));
    }

    /** Routing seam: replaces the whole match, never merges two routes. */
    public void setPathVars(Map<String, String> vars) {
        pathVariables.clear();
        if (vars != null) {
            pathVariables.putAll(vars);
        }
    }
}
