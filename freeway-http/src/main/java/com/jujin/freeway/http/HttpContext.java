package com.jujin.freeway.http;

/**
 * One HTTP exchange: the exchange metadata, the request face, and the
 * response face, all on a single object handed to application handlers.
 *
 * <p>This is a marker interface — it adds no methods. A handler receives an
 * {@code HttpContext} and can use every request, response, and metadata
 * method directly; framework components that need only one side depend on
 * the narrow {@link HttpRequest}/{@link HttpResponse} contracts and accept
 * the context because it is one of them.</p>
 */
public interface HttpContext extends ExchangeMeta, HttpRequest, HttpResponse {
}
