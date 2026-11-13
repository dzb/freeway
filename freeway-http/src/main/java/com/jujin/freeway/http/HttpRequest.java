package com.jujin.freeway.http;

import com.jujin.freeway.http.body.MultipartException;
import com.jujin.freeway.http.body.MultipartForm;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Read side of an HTTP exchange: method, path, parameters, headers, body,
 * and TLS details. Exchange metadata lives on {@link HttpExchange}; framework
 * components that only read should depend on this interface.
 */
public interface HttpRequest extends RequestView {

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
     * (HTTPS / WSS). This is a transport fact: an implementation must answer
     * it, never default it to false and make a TLS request look plaintext.
     */
    boolean isSecure();

    /**
     * Returns the TLS session for this request (negotiated protocol, cipher
     * suite, peer certificates), or {@code null} for plain HTTP.
     */
    SSLSession sslSession();

    /**
     * Returns the client IP address of the connection, or an empty string
     * when the transport genuinely does not expose one.
     */
    String remoteAddress();

    /** Returns the value of a path parameter coerced to the given type. */
    <T> Optional<T> pathVar(String name, Class<T> type);

    /** Returns a request parameter (from query string first, then path). */
    Optional<String> param(String name);

    /** Returns a request parameter coerced to the given type. */
    <T> Optional<T> param(String name, Class<T> type);

    /** Returns true if the request has a multipart/form-data content type. */
    default boolean isMultipart() {
        return header("Content-Type").map(MediaTypes::isMultipartFormData)
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
            .filter(MediaTypes::isMultipartFormData)
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
     * Returns the request body as a streaming input, enforcing this exchange's
     * {@link HttpExchange#setMaxBodySize(long) maximum body size} as it is read.
     * This is a required capability: a default that buffers would hide the
     * difference between a streaming engine and a materializing one.
     */
    InputStream bodyStream() throws IOException;

    /** Reads the request body into a string using the charset from the
     *  Content-Type header. */
    String bodyText() throws IOException;

    /** Deserializes the request body as JSON into the given type. */
    <T> T bodyAsJson(Class<T> type) throws IOException;

    /** Deserializes the request body as JSON into the given type. */
    <T> T bodyAsJson(Type type) throws IOException;
}
