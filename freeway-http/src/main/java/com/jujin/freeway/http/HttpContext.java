package com.jujin.freeway.http;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * One HTTP exchange: the exchange metadata (correlation id, start time,
 * principal, attributes) plus the request and response transport faces.
 *
 * <p>Application handlers receive this combined object for concise lambdas.
 * The convenience set is deliberately small and fixed: read side
 * {@code pathVar/pathVars/param/queryParam/header/bodyAsJson}, write side
 * {@code send/sendJson/status/setHeader/output}. Everything beyond it goes
 * through {@link #request()} and {@link #response()} against the narrow
 * transport contracts — this boundary keeps the facade from growing into a
 * god interface.</p>
 */
public interface HttpContext extends ExchangeMeta {

    /** Read side of the exchange. */
    HttpRequest request();

    /** Write side of the exchange. */
    HttpResponse response();

    // -- convenience: read side (forwarded to request()) --

    default Optional<String> pathVar(String name) {
        return request().pathVar(name);
    }

    default Map<String, String> pathVars() {
        return request().pathVars();
    }

    default Optional<String> param(String name) {
        return request().param(name);
    }

    default Optional<String> queryParam(String name) {
        return request().queryParam(name);
    }

    default Optional<String> header(String name) {
        return request().header(name);
    }

    default <T> T bodyAsJson(Class<T> type) throws IOException {
        return request().bodyAsJson(type);
    }

    // -- convenience: write side (forwarded to response()) --

    default HttpResponse send(int status, String text) throws IOException {
        return response().send(status, text);
    }

    default HttpResponse sendJson(int status, Object value) throws IOException {
        return response().sendJson(status, value);
    }

    default HttpResponse status(int status) {
        return response().status(status);
    }

    default HttpResponse setHeader(String name, String value) {
        return response().setHeader(name, value);
    }

    default HttpResponse output(byte[] data) throws IOException {
        return response().output(data);
    }
}
