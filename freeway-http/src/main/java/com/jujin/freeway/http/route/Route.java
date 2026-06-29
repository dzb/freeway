package com.jujin.freeway.http.route;

import java.util.Objects;
import java.util.Locale;
import com.jujin.freeway.commons.validation.BeanValidator;
import com.jujin.freeway.http.body.BodyHandler;
import com.jujin.freeway.http.ValidationException;

public record Route(
    String method,
    String path,
    RouteHandler handler,
    Class<? extends RouteHandler> handlerType
) {
    public Route {
        method = normalizeMethod(method);
        Objects.requireNonNull(path, "path");
        PathPattern.validateRegistrationPath(path);
        if (handler == null && handlerType == null) {
            throw new IllegalArgumentException(
                "Either a handler instance or a handler class must be provided");
        }
        if (handler != null && handlerType != null) {
            throw new IllegalArgumentException(
                "Provide either a handler instance or a handler class, not both");
        }
    }

    /** Backward-compatible constructor: handler instance only. */
    public Route(String method, String path, RouteHandler handler) {
        this(method, path, handler, null);
    }

    public static Route of(String method, String path, RouteHandler handler) {
        return new Route(method, path, handler, null);
    }

    /** Creates a route from a handler class — the container resolves the instance. */
    public static Route of(String method, String path, Class<? extends RouteHandler> handlerType) {
        return new Route(method, path, null, handlerType);
    }

    /** Internal: creates a route with both fields set explicitly. */
    static Route of(String method, String path, RouteHandler handler,
                    Class<? extends RouteHandler> handlerType) {
        return new Route(method, path, handler, handlerType);
    }

    public static Route get(String path, RouteHandler handler) {
        return of("GET", path, handler);
    }

    /** Creates a GET route from a handler class — the container resolves the instance. */
    public static Route get(String path, Class<? extends RouteHandler> handlerType) {
        return of("GET", path, handlerType);
    }

    public static Route post(String path, RouteHandler handler) {
        return of("POST", path, handler);
    }

    /** Creates a POST route from a handler class — the container resolves the instance. */
    public static Route post(String path, Class<? extends RouteHandler> handlerType) {
        return of("POST", path, handlerType);
    }

    /**
     * Creates a POST route that automatically deserializes the request body as JSON
     * and validates it via {@link BeanValidator} before invoking the handler.
     * Validation failures throw {@link ValidationException}, which is mapped to 400
     * by the default exception mapper in {@code HttpModule}.
     */
    public static <T> Route post(String path, Class<T> bodyType, BodyHandler<T> handler) {
        return post(path, wrapBody(bodyType, handler));
    }

    public static Route put(String path, RouteHandler handler) {
        return of("PUT", path, handler);
    }

    /** Creates a PUT route from a handler class — the container resolves the instance. */
    public static Route put(String path, Class<? extends RouteHandler> handlerType) {
        return of("PUT", path, handlerType);
    }

    /**
     * Creates a PUT route that automatically deserializes and validates the request body.
     * @see #post(String, Class, BodyHandler)
     */
    public static <T> Route put(String path, Class<T> bodyType, BodyHandler<T> handler) {
        return put(path, wrapBody(bodyType, handler));
    }

    public static Route delete(String path, RouteHandler handler) {
        return of("DELETE", path, handler);
    }

    /** Creates a DELETE route from a handler class — the container resolves the instance. */
    public static Route delete(String path, Class<? extends RouteHandler> handlerType) {
        return of("DELETE", path, handlerType);
    }

    public static Route patch(String path, RouteHandler handler) {
        return of("PATCH", path, handler);
    }

    /** Creates a PATCH route from a handler class — the container resolves the instance. */
    public static Route patch(String path, Class<? extends RouteHandler> handlerType) {
        return of("PATCH", path, handlerType);
    }

    /**
     * Creates a PATCH route that automatically deserializes and validates the request body.
     * @see #post(String, Class, BodyHandler)
     */
    public static <T> Route patch(String path, Class<T> bodyType, BodyHandler<T> handler) {
        return patch(path, wrapBody(bodyType, handler));
    }

    /**
     * Wraps a {@link BodyHandler} so the request body is deserialized from JSON and
     * validated before the handler executes. If validation fails, a
     * {@link ValidationException} is thrown (mapped to HTTP 400 by default).
     */
    private static <T> RouteHandler wrapBody(Class<T> bodyType, BodyHandler<T> handler) {
        Objects.requireNonNull(bodyType, "bodyType");
        return ctx -> {
            T body = ctx.bodyAsJson(bodyType);
            var result = BeanValidator.validate(body);
            if (result.hasErrors()) {
                throw new ValidationException(result);
            }
            handler.handle(ctx, body);
        };
    }

    public static Route head(String path, RouteHandler handler) {
        return of("HEAD", path, handler);
    }

    /** Creates a HEAD route from a handler class — the container resolves the instance. */
    public static Route head(String path, Class<? extends RouteHandler> handlerType) {
        return of("HEAD", path, handlerType);
    }

    public static Route options(String path, RouteHandler handler) {
        return of("OPTIONS", path, handler);
    }

    /** Creates an OPTIONS route from a handler class — the container resolves the instance. */
    public static Route options(String path, Class<? extends RouteHandler> handlerType) {
        return of("OPTIONS", path, handlerType);
    }

    private static String normalizeMethod(String method) {
        return Objects.requireNonNull(method, "method").trim().toUpperCase(Locale.ROOT);
    }
}
