/**
 * The HTTP module's observability records, published through the
 * container's event sink so subscribers never import {@code engine}:
 * {@code HttpServerStartedEvent} once after binding, and per-exchange
 * {@code HttpExchangeEvent}/{@code HttpErrorEvent} around each dispatch.
 * All three are published by {@code HttpServer} through the container's
 * event sink, and are not even constructed unless a real sink is wired —
 * the types live here so subscribers never import {@code engine}.
 */
package com.jujin.freeway.http.event;
