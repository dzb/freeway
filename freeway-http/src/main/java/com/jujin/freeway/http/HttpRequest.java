package com.jujin.freeway.http;

import com.jujin.freeway.http.body.MultipartException;
import com.jujin.freeway.http.body.MultipartForm;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Read side of an HTTP exchange: method, path, parameters, headers, body,
 * and TLS details. Exchange metadata lives on {@link HttpContext}; framework
 * components that only read should depend on this interface.
 */
public interface HttpRequest extends RequestInfo {

    /**
     * Returns the value of a single query parameter coerced to the
     * given type, or empty if absent.
     */
    <T> Optional<T> queryParam(String name, Class<T> type);

    /**
     * Returns the value of a single request header coerced to the
     * given type, or empty if absent.
     */
    <T> Optional<T> header(String name, Class<T> type);

    /**
     * Returns true when this request was received over a TLS connection
     * (HTTPS / WSS). Defaults to {@code false}; transport bridges override it.
     */
    default boolean isSecure() {
        return false;
    }

    /**
     * Returns the TLS session for this request (negotiated protocol, cipher
     * suite, peer certificates), or {@code null} for plain HTTP.
     */
    default SSLSession sslSession() {
        return null;
    }

    /**
     * Returns the client IP address of the connection, or an empty string
     * when the transport does not expose one.
     */
    default String remoteAddress() {
        return "";
    }

    /** Returns the value of a path parameter coerced to the given type. */
    <T> Optional<T> pathVar(String name, Class<T> type);

    /** Returns a request parameter (from query string first, then path). */
    Optional<String> param(String name);

    /** Returns a request parameter coerced to the given type. */
    <T> Optional<T> param(String name, Class<T> type);

    /** Returns true if the request has a multipart/form-data content type. */
    default boolean isMultipart() {
        return header("Content-Type").map(HttpUtils::isMultipartFormData)
            .orElse(false);
    }

    /**
     * Parses and returns the multipart form data, or empty if the request
     * is not a multipart upload.
     */
    default Optional<MultipartForm> multipart() {
        // Guard on the Content-Type before reading the body — parsing a
        // non-multipart request would consume the entire request body for
        // nothing (and could trip the body-size limit on isMultipart()).
        return header("Content-Type")
            .filter(HttpUtils::isMultipartFormData)
            .flatMap(ct -> {
                try {
                    return Optional.of(MultipartForm.parse(ct, body()));
                } catch (IOException e) {
                    throw new MultipartException("Invalid multipart request", e);
                }
            });
    }

    /** Returns the raw request body bytes. */
    byte[] body() throws IOException;

    /**
     * Returns the request body as a streaming input, enforcing the
     * configured {@link HttpContext#maxBodySize(long) maximum body size} as
     * it is read.
     *
     * <p>The default reads the whole body into memory for implementations
     * that only buffer; transport bridges override it to stream directly.</p>
     */
    default InputStream bodyStream() throws IOException {
        return new ByteArrayInputStream(body());
    }

    /** Reads the request body into a string using the charset from the
     *  Content-Type header. */
    String bodyText() throws IOException;

    /** Deserializes the request body as JSON into the given type. */
    <T> T bodyAsJson(Class<T> type) throws IOException;

    /** Deserializes the request body as JSON into the given type. */
    <T> T bodyAsJson(Type type) throws IOException;
}
