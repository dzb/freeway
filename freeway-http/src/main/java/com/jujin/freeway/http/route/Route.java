package com.jujin.freeway.http.route;

import java.util.Objects;
import java.util.Locale;
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
        PathPattern.validateRegistrationPath(path);
        Objects.requireNonNull(handler, "handler");
    }

    public static Route of(String method, String path, RouteHandler handler) {
        return new Route(method, path, handler);
    }

    /** Creates a route from a handler class — resolved via LazyHandler at request time. */
    public static Route of(String method, String path, Class<? extends RouteHandler> handlerType) {
        return new Route(method, path, new LazyHandler(handlerType));
    }

    public static Route get(String path, RouteHandler handler) {
        return of("GET", path, handler);
    }

    /** Creates a GET route from a handler class. */
    public static Route get(String path, Class<? extends RouteHandler> handlerType) {
        return of("GET", path, handlerType);
    }

    public static Route post(String path, RouteHandler handler) {
        return of("POST", path, handler);
    }

    /** Creates a POST route from a handler class. */
    public static Route post(String path, Class<? extends RouteHandler> handlerType) {
        return of("POST", path, handlerType);
    }

    public static <T> Route post(String path, Class<T> bodyType, BodyHandler<T> handler) {
        return post(path, wrapBody(bodyType, handler));
    }

    public static Route put(String path, RouteHandler handler) {
        return of("PUT", path, handler);
    }

    /** Creates a PUT route from a handler class. */
    public static Route put(String path, Class<? extends RouteHandler> handlerType) {
        return of("PUT", path, handlerType);
    }

    public static <T> Route put(String path, Class<T> bodyType, BodyHandler<T> handler) {
        return put(path, wrapBody(bodyType, handler));
    }

    public static Route delete(String path, RouteHandler handler) {
        return of("DELETE", path, handler);
    }

    /** Creates a DELETE route from a handler class. */
    public static Route delete(String path, Class<? extends RouteHandler> handlerType) {
        return of("DELETE", path, handlerType);
    }

    public static Route patch(String path, RouteHandler handler) {
        return of("PATCH", path, handler);
    }

    /** Creates a PATCH route from a handler class. */
    public static Route patch(String path, Class<? extends RouteHandler> handlerType) {
        return of("PATCH", path, handlerType);
    }

    public static <T> Route patch(String path, Class<T> bodyType, BodyHandler<T> handler) {
        return patch(path, wrapBody(bodyType, handler));
    }

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

    /** Creates a HEAD route from a handler class. */
    public static Route head(String path, Class<? extends RouteHandler> handlerType) {
        return of("HEAD", path, handlerType);
    }

    public static Route options(String path, RouteHandler handler) {
        return of("OPTIONS", path, handler);
    }

    /** Creates an OPTIONS route from a handler class. */
    public static Route options(String path, Class<? extends RouteHandler> handlerType) {
        return of("OPTIONS", path, handlerType);
    }

    private static String normalizeMethod(String method) {
        return Objects.requireNonNull(method, "method").trim().toUpperCase(Locale.ROOT);
    }
}
