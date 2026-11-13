package com.jujin.freeway.http;

import java.io.IOException;
import java.io.InputStream;

import javax.net.ssl.SSLSession;

import com.jujin.freeway.http.sse.SseEmitter;

/**
 * Transport SPI: the request bytes an engine reads and the response bytes it
 * writes. An engine implements this interface and composes {@link
 * HttpExchangeSupport}; it never subclasses a framework type.
 *
 * <p><b>Request side.</b> All returned collections are immutable snapshots.
 * {@link #framedBodyStream()} returns the transport-framed stream (chunked or
 * fixed-length already removed); the framework's {@code
 * HttpExchangeSupport} wraps it in the shared body-size limiter, so every
 * engine enforces {@code maxBodySize} with the same code.</p>
 *
 * <p><b>Response side.</b> {@link HttpExchangeSupport} owns every shared
 * response decision (status/header validation, {@code Date}, {@code
 * Content-Length}, encoding, {@code Vary}) and hands the transport one
 * immutable {@link ResponsePlan}. The transport executes the body once, adds
 * protocol framing ({@code Connection}, {@code Transfer-Encoding}) and owns
 * the commit fact ({@link #isCommitted()}). There is no second body policy in
 * a default method: bytes, stream, file and channel are all values in the
 * plan.</p>
 */
public interface HttpTransport extends RequestHead {

    /** Returns the transport-framed request body stream. */
    InputStream framedBodyStream() throws IOException;

    /** True when the request arrived over TLS. */
    boolean isSecure();

    /** Negotiated TLS session, or null for plaintext. */
    SSLSession sslSession();

    /** Client address, or an empty string when unavailable. */
    String remoteAddress();

    /**
     * True once this transport has committed the response: it has started
     * writing the response head. Before this point a write that fails can
     * still become an error response; after it, the failure is a transport
     * failure and no second head may be written. Implementations set the
     * committed flag immediately before handing the first status/header bytes
     * to the wire, so {@code HttpExchangeSupport.isCommitted()} is the
     * transport's own verdict rather than a flag the support object guessed.
     */
    boolean isCommitted();

    /**
     * Writes one planned response. The head is the final wire head; the body
     * is executed once by this transport. Encoding is stated by the head's
     * {@code Content-Encoding}, framing by the protocol.
     *
     * <p>When {@link ResponseHead#sendsBodyBytes()} is false (HEAD, 204, 205,
     * 304) the transport writes the head only and never touches the body
     * source. A HEAD keeps the {@code Content-Length} the support object
     * declared for it; an unknown length stays unknown rather than being
     * materialized just to invent one.</p>
     */
    void writeResponse(ResponsePlan plan) throws IOException;

    /** Writes the SSE head and returns an emitter over the response stream. */
    SseEmitter openSse(ResponseHead head) throws IOException;
}
