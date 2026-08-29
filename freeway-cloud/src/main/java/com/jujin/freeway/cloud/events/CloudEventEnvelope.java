package com.jujin.freeway.cloud.events;

import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.EventBridge;
import com.jujin.freeway.ioc.EventBus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * CloudEvents 1.0 (JSON content mode) translator — the single place where a
 * Freeway event becomes a wire frame and back (design doc §2.2).
 *
 * <p>Attribute mapping: {@code type} = event class name (CLASS channel) or
 * the string topic (TOPIC channel); {@code subject} = {@link EventBus.Keyed#key()}
 * (partition-ordering key, preserved across the wire); {@code source} =
 * {@code freeway://{serviceId}}; {@code id} = the dispatch identity the bus
 * minted once and handed to every transport (so the copies can be
 * correlated); extensions {@code fwchannel}/{@code fworigin}/{@code fwtimes}
 * carry the dispatch channel, the originating node identity, and the
 * delivery generation respectively.</p>
 *
 * <p><b>Why the id is a parameter, not minted here:</b> this method runs
 * once per bridge per send. Minting inside it would give every copy of an
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
    public static final String EXT_TIMES = "fwtimes";

    /** Decoded wire frame — everything a consumer needs to route and rebuild. */
    public record Parsed(
        String id,
        String source,
        String type,
        String subject,
        String origin,
        EventBridge.Channel channel,
        int times,
        String dataJson
    ) {}

    /** Translates one outbound event (or topic payload) into a CE JSON frame. */
    public static String translate(
        Object event,
        String topic,
        EventBridge.Channel channel,
        String origin,
        String serviceId,
        JsonCodec codec
    ) {
        // Direct caller, not the bus: no shared identity to reuse, so mint
        // one. Two transports translating the same event through this path
        // produce unrelated ids — precisely what the bus-supplied id avoids.
        return translate(
            event, topic, channel, origin, serviceId, codec,
            UUID.randomUUID().toString());
    }

    /**
     * Translates using {@code eventId} — the identity the bus minted for
     * this dispatch — instead of minting a fresh one. Every bridge that
     * receives the dispatch is handed the same id, so an event bridged over
     * two transports arrives at a peer twice carrying one identity, which is
     * the only thing that makes it deduplicable.
     */
    public static String translate(
        Object event,
        String topic,
        EventBridge.Channel channel,
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

        if (channel == EventBridge.Channel.CLASS) {
            frame.put("type", event.getClass().getName());
            if (event instanceof EventBus.Keyed k) {
                frame.put("subject", k.key());
            }
        } else {
            frame.put("type", topic);
        }
        frame.put(EXT_CHANNEL, channel.name().toLowerCase(java.util.Locale.ROOT));
        frame.put(EXT_ORIGIN, origin);
        frame.put(EXT_TIMES, 1);

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
        EventBridge.Channel channel;
        try {
            channel = EventBridge.Channel.valueOf(channelStr.toUpperCase(java.util.Locale.ROOT));
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
            frame.containsKey(EXT_TIMES) ? frame.getInt(EXT_TIMES) : 1,
            dataJson);
    }

    private static String require(JsonObject frame, String name) {
        String value = frame.getString(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CloudEvent frame missing '" + name + "'");
        }
        return value;
    }
}
