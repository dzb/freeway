/**
 * The {@code freeway-http} contract surface — the one package where
 * applications, engine adapters and the container all meet. Everything
 * here carries stable semantics: a shape change surfaces in the compiler
 * and carries its "why" in the CHANGELOG. {@code engine}, its sub-packages
 * and {@code internal} are implementation with no stability promise.
 *
 * <p>The module's model: a running server is
 * {@code HttpEngine + HttpServerConfig + HttpPipeline + HttpServer} around
 * one exchange seam — and this package's types group by that model's
 * four roles:</p>
 *
 * <ul>
 *   <li><strong>The assembly half</strong> — the four anchors with their
 *       entry and handle: {@link HttpEngine} (transport capability),
 *       {@link HttpServerConfig} (transport declaration), {@link HttpPipeline}
 *       (handling declaration), {@link HttpServer} (the compiled server),
 *       {@link HttpModule} (the container face) and {@link HttpServerHandle}
 *       (the lifecycle handle). {@code HttpServer.create(engine, config,
 *       pipeline[, eventSink])} is the one derivation; during assembly the
 *       seam below is compiled away.</li>
 *   <li><strong>The seam's data half</strong> — {@link ExchangeHandler} (the
 *       behaviour) with {@link HttpContext}/{@link AbstractHttpContext} and
 *       the exchange faces {@link HttpRequest}, {@link HttpResponse},
 *       {@link RequestView} (the read-only projection shared with a
 *       WebSocket session), {@link ExchangeMeta} and its
 *       {@link ExchangeMetaDefault} (per-exchange state, constructed
 *       directly rather than looked up). During handle this half is the only
 *       crossing between a pipeline and a transport.</li>
 *   <li><strong>The TLS contract</strong> — {@link SslSettings} (key material
 *       resolution the engines share) and {@link SslContexts} (context
 *       building); the package-private {@code SniKeyManager} is SslContexts'
 *       collaborator. Hot reload of a running engine is engine-self-driven
 *       and lives beside it in {@code engine}.</li>
 *   <li><strong>Shared vocabulary</strong> — {@link HttpStatus},
 *       {@link MediaTypes}, {@link Compression}, {@link ErrorResponses} (the
 *       shared 404/500 writer that keeps dispatcher, sessions and
 *       static-file fallback from drifting), {@link ValidationException} and
 *       this module's config keys {@link HttpConfigKeys}.</li>
 * </ul>
 *
 * <p>The feature packages beside this one ({@code route}, {@code filter},
 * {@code body}, {@code websocket}, {@code sse}, {@code staticfile},
 * {@code event}) are the application-facing DSL: they lean on this
 * vocabulary but never on {@code engine}.</p>
 */
package com.jujin.freeway.http;
