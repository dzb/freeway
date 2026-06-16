package com.jujin.freeway.http.route;

import java.util.Objects;
import com.jujin.freeway.commons.validation.BeanValidator;
import com.jujin.freeway.http.body.BodyHandler;
import com.jujin.freeway.http.ValidationException;

public record Route(
    String method,
    String path,
    RouteHandler handler
) {
    public Route {
        method = normalizeMethod(method);
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(handler, "handler");
        PathPattern.validateRegistrationPath(path);
    }

    public static Route of(String method, String path, RouteHandler handler) {
        return new Route(method, path, handler);
    }

    public static Route get(String path, RouteHandler handler) {
        return of("GET", path, handler);
    }

    public static Route post(String path, RouteHandler handler) {
        return of("POST", path, handler);
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

    public static Route patch(String path, RouteHandler handler) {
        return of("PATCH", path, handler);
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

    public static Route options(String path, RouteHandler handler) {
        return of("OPTIONS", path, handler);
    }

    private static String normalizeMethod(String method) {
        return Objects.requireNonNull(method, "method").trim().toUpperCase();
    }
}
