package com.jujin.freeway.cloud.event;

import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * CloudEvents 1.0 (JSON content mode) translator — the single place where a
 * cloud event becomes a wire frame and back (design doc §2.2).
 *
 * <p>Attribute mapping: {@code type} = the topic the event was published on
 * (the plane's only routing key — Java class names never go on the wire);
 * {@code subject} = the optional ordering/partition hint the publisher
 * supplied; {@code source} = {@code freeway://{serviceId}}; {@code id} = the
 * frame identity minted once per publish; extensions
 * {@code fwchannel}/{@code fworigin} carry the dispatch channel and the
 * originating node identity, and — only when the sending thread holds a
 * trace — {@code traceparent}/{@code tracestate} carry it (see
 * {@link EventTrace}; the distributed-tracing extension attributes, restored
 * around dispatch on receipt).</p>
 *
 * <p><b>Reading old frames.</b> A node that predates the bus-to-mesh bridge
 * teardown may still have {@code fwchannel=class} frames in flight; this
 * parser accepts them ({@link Channel#CLASS}) so no frame fails to decode.
 * Decoding is not delivering: the mesh plane routes on topics only, so
 * CLASS-channel frames are dropped at {@link PeerHub#receive} — counted,
 * logged, never deserialized.</p>
 *
 * <p>Frames failing CE constraints (missing id/type/source) fail loudly —
 * never silently dropped.</p>
 */
public final class CloudEventEnvelope {

    private CloudEventEnvelope() {}

    public static final String SPECVERSION = "1.0";
    public static final String EXT_CHANNEL = "fwchannel";
    public static final String EXT_ORIGIN = "fworigin";

    /**
     * The wire's channel vocabulary — the mesh's own, no longer borrowed from
     * any bus type. {@link #TOPIC} is the only channel a node sends;
     * {@link #CLASS} exists so {@link #parse} can name what pre-teardown
     * in-flight frames carry and {@code receive} can drop them by reason.
     */
    public enum Channel {
        /** Legacy: routed by Java class name. Never sent, never delivered. */
        CLASS,
        /** Routed by the topic string carried as the CE {@code type}. */
        TOPIC
    }

    /** Decoded wire frame — everything a consumer needs to route and rebuild. */
    public record Parsed(
        String id,
        String source,
        String type,
        String subject,
        String origin,
        Channel channel,
        String dataJson,
        String traceparent,
        String tracestate
    ) {}

    /**
     * Builds one CloudEvents 1.0 frame for a publish on {@code topic}.
     *
     * @param topic     the routing topic; becomes the CloudEvents {@code type}
     * @param payload   the event payload; JSON-encoded into {@code data}
     * @param subject   optional ordering/partition hint; omitted when null/blank
     * @param origin    this node's identity, carried in {@code fworigin}
     * @param serviceId this service's id, forming the CloudEvents {@code source}
     * @param eventId   frame identity, minted once per publish; never null
     */
    public static String translate(
        String topic,
        Object payload,
        String subject,
        String origin,
        String serviceId,
        JsonCodec codec,
        String eventId
    ) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(eventId, "eventId");

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("specversion", SPECVERSION);
        frame.put("id", eventId);
        frame.put("source", "freeway://" + serviceId);
        frame.put("time", java.time.OffsetDateTime.now().toString());
        frame.put("type", topic);
        if (subject != null && !subject.isBlank()) {
            frame.put("subject", subject);
        }
        frame.put(EXT_CHANNEL, Channel.TOPIC.name().toLowerCase(java.util.Locale.ROOT));
        frame.put(EXT_ORIGIN, origin);
        // The ambient trace, when the sending thread holds one — absent
        // otherwise, so traceless publishes stay byte-identical to before.
        frame.putAll(EventTrace.injectCurrent());

        frame.put("datacontenttype", "application/json");
        // The payload may be null (signal semantics — the topic carries the
        // meaning); null embeds as the JSON null literal, distinct from
        // absent, which parse reports as a null dataJson.
        frame.put("data", payload == null
            ? null
            : JsonUtils.parse(codec.toJson(payload)));

        return codec.toJson(frame);
    }

    /** Parses one wire frame; throws on CE-constraint violations. */
    public static Parsed parse(String json) {
        JsonObject frame = JsonUtils.parseObject(json);
        String spec = frame.getString("specversion");
        if (!SPECVERSION.equals(spec)) {
            throw new IllegalArgumentException(
                "Unsupported specversion '" + spec + "' — expected " + SPECVERSION);
        }
        String id = require(frame, "id");
        String source = require(frame, "source");
        String type = require(frame, "type");
        // An old frame may arrive without fwchannel only if its sender predates
        // fwchannel itself; absent is treated as the legacy class channel —
        // which the receiver drops by reason, never deserializes.
        String channelStr = frame.getString(EXT_CHANNEL);
        Channel channel;
        if (channelStr == null || channelStr.isBlank()) {
            channel = Channel.CLASS;
        } else {
            try {
                channel = Channel.valueOf(channelStr.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown fwchannel: " + channelStr);
            }
        }
        // CE allows a null/absent data: absent → dataJson null (callers
        // treat as no payload); present-but-null → "null" (JSON null literal,
        // e.g. a signal publish — distinct from no data).
        String dataJson = frame.containsKey("data")
            ? Objects.requireNonNullElse(JsonUtils.stringify(frame.get("data")), "null")
            : null;
        return new Parsed(
            id,
            source,
            type,
            frame.getString("subject"),
            Objects.requireNonNullElse(frame.getString(EXT_ORIGIN), ""),
            channel,
            dataJson,
            frame.getString(EventTrace.TRACEPARENT),
            frame.getString(EventTrace.TRACESTATE));
    }

    private static String require(JsonObject frame, String name) {
        String value = frame.getString(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CloudEvent frame missing '" + name + "'");
        }
        return value;
    }
}
