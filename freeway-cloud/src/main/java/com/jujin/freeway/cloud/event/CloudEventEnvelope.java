package com.jujin.freeway.cloud.event;

import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.EventSink;
import com.jujin.freeway.ioc.EventBus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * CloudEvents 1.0 (JSON content mode) translator — the single place where a
 * Freeway event becomes a wire frame and back (design doc §2.2).
 *
 * <p>Attribute mapping: {@code type} = event class name (CLASS channel) or
 * the string topic (TOPIC channel); {@code subject} = {@link EventBus.Keyed#key()}
 * on the CLASS channel only — the ordering key applies to typed events, while a
 * topic payload is opaque to the bus and carries no subject; {@code source} =
 * {@code freeway://{serviceId}}; {@code id} = the dispatch identity the bus
 * minted once and handed to every transport (so the copies can be
 * correlated); extensions {@code fwchannel}/{@code fworigin} carry the
 * dispatch channel and the originating node identity, and — only when the
 * sending thread holds a trace — {@code traceparent}/{@code tracestate}
 * carry it (see {@link EventTrace}; the distributed-tracing extension
 * attributes, restored around dispatch on receipt).
 *
 * <p><b>Why the id is a parameter, not minted here:</b> this method runs
 * once per sink per send. Minting inside it would give every copy of an
 * event a different id, and no consumer could ever recognize two copies of
 * the same event as duplicates.</p>
 *
 * <p>Frames failing CE constraints (missing id/type/source) fail loudly —
 * never silently dropped.</p>
 */
public final class CloudEventEnvelope {

    private CloudEventEnvelope() {}

    public static final String SPECVERSION = "1.0";
    public static final String EXT_CHANNEL = "fwchannel";
    public static final String EXT_ORIGIN = "fworigin";

    /** Decoded wire frame — everything a consumer needs to route and rebuild. */
    public record Parsed(
        String id,
        String source,
        String type,
        String subject,
        String origin,
        EventSink.Channel channel,
        String dataJson,
        String traceparent,
        String tracestate
    ) {}

    /**
     * Translates using {@code eventId} — the identity the bus minted for
     * this dispatch — instead of minting a fresh one. Every sink that
     * receives the dispatch is handed the same id, so an event sent over
     * two transports arrives at a peer twice carrying one identity, which is
     * the only thing that makes it deduplicable.
     *
     * @param event    the event or topic payload; JSON-encoded into {@code data}
     * @param topic    the routing topic the sink was called with. Required by
     *                 the {@link EventSink#send} contract on both channels, and
     *                 it becomes the CloudEvents {@code type} on the TOPIC
     *                 channel only — a CLASS frame carries the event class name
     *                 as its type, so the derived topic is not used there
     * @param channel  the local dispatch channel the event was published on
     * @param origin   this node's identity, carried in {@code fworigin}
     * @param serviceId this service's id, forming the CloudEvents {@code source}
     * @param eventId  bus-minted identity of this dispatch; never null
     */
    public static String translate(
        Object event,
        String topic,
        EventSink.Channel channel,
        String origin,
        String serviceId,
        JsonCodec codec,
        String eventId
    ) {
        Objects.requireNonNull(event, "event/payload");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(eventId, "eventId");

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("specversion", SPECVERSION);
        frame.put("id", eventId);
        frame.put("source", "freeway://" + serviceId);
        frame.put("time", java.time.OffsetDateTime.now().toString());

        if (channel == EventSink.Channel.CLASS) {
            frame.put("type", event.getClass().getName());
            if (event instanceof EventBus.Keyed k) {
                frame.put("subject", k.key());
            }
        } else {
            frame.put("type", topic);
        }
        frame.put(EXT_CHANNEL, channel.name().toLowerCase(java.util.Locale.ROOT));
        frame.put(EXT_ORIGIN, origin);
        // The ambient trace, when the sending thread holds one — absent
        // otherwise, so traceless publishes stay byte-identical to before.
        frame.putAll(EventTrace.injectCurrent());

        frame.put("datacontenttype", "application/json");
        // Embed the event as a nested JSON value, NOT as a string — putting
        // the encoded string here would double-encode on the outer toJson.
        frame.put("data", com.jujin.freeway.commons.json.JsonUtils.parse(codec.toJson(event)));

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
        String channelStr = require(frame, EXT_CHANNEL);
        EventSink.Channel channel;
        try {
            channel = EventSink.Channel.valueOf(channelStr.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown fwchannel: " + channelStr);
        }
        // CE allows a null/absent data: absent → dataJson null (callers
        // treat as no payload); present-but-null → "null" (JSON null literal,
        // e.g. TOPIC channel with a null payload — distinct from no data).
        String dataJson = frame.containsKey("data")
            ? Objects.requireNonNullElse(JsonUtils.stringify(frame.get("data")), "null")
            : null;
        return new Parsed(
            id,
            source,
            type,
            frame.getString("subject"),
            java.util.Objects.requireNonNullElse(frame.getString(EXT_ORIGIN), ""),
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
