package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.context.Baggage;
import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.Propagator;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * W3C {@code baggage} propagation (RFC 7071 semantics): injects the current
 * {@link Baggage} as a {@code baggage} header ({@code k=v,k2=v2}), extracts
 * it back on the receiving side. Inbound extraction returns {@code null} when
 * the header is absent or empty, so the merge in {@link PropagationFilter}
 * keeps any baggage established by earlier propagators.
 *
 * <p>Keys and values are percent-encoded (UTF-8) — unreserved characters
 * verbatim, everything else escaped — so a value containing {@code ,},
 * {@code =} or whitespace travels as data instead of corrupting the wire
 * structure. The codec is symmetric, so arbitrary application baggage
 * round-trips byte-for-byte.
 */
public final class BaggagePropagator implements Propagator {

    private static final Logger LOG = LoggerFactory.getLogger(BaggagePropagator.class);

    public static final String HEADER_BAGGAGE = "baggage";

    /** Upper bound on entries carried in one header (W3C recommends
     *  implementation limits; without one, hops multiply memory and header
     *  size unboundedly). */
    public static final int MAX_ENTRIES = 64;
    /** Upper bound on the encoded header value (bytes). */
    public static final int MAX_ENCODED_LENGTH = 4096;

    @Override
    public void inject(InvocationContext ctx, Map<String, String> headers) {
        Baggage baggage = ctx.baggage();
        if (baggage == null || baggage.values().isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        int entries = 0;
        for (Map.Entry<String, String> entry : baggage.values().entrySet()) {
            String encodedKey = encode(entry.getKey());
            String encodedValue = encode(entry.getValue());
            int grew = sb.length() == 0 ? 0 : 1;
            grew += encodedKey.length() + 1 + encodedValue.length();
            if (entries >= MAX_ENTRIES || sb.length() + grew > MAX_ENCODED_LENGTH) {
                LOG.debug("Baggage entry dropped at propagation limit ({} entries / {} chars): {}",
                    MAX_ENTRIES, MAX_ENCODED_LENGTH, entry.getKey());
                break;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(encodedKey).append('=').append(encodedValue);
            entries++;
        }
        if (sb.length() > 0) {
            headers.put(HEADER_BAGGAGE, sb.toString());
        }
    }

    @Override
    public InvocationContext extract(Map<String, String> headers) {
        String raw = headers.get(HEADER_BAGGAGE);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Map<String, String> values = new HashMap<>();
        int entries = 0;
        for (String pair : ConfigLists.splitAndTrim(raw)) {
            if (entries >= MAX_ENTRIES) {
                LOG.debug("Baggage truncated at propagation limit ({} entries)", MAX_ENTRIES);
                break;
            }
            int eq = pair.indexOf('=');
            if (eq > 0) {
                values.put(decode(pair.substring(0, eq).trim()),
                    decode(pair.substring(eq + 1).trim()));
                entries++;
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        return InvocationContext.of(null, null, Baggage.of(values));
    }

    // ── wire codec ─────────────────────────────────────────────────────────

    /** RFC 3986 unreserved characters survive verbatim; every other byte is
     *  percent-encoded, so it can never be mistaken for wire structure.
     *  Package-visible so the other header propagators share one codec. */
    static String encode(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (byte b : raw.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (isUnreserved(c)) {
                out.append(c);
            } else {
                out.append('%').append(HexFormat.of().toHexDigits(b));
            }
        }
        return out.toString();
    }

    private static boolean isUnreserved(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
            || c == '-' || c == '.' || c == '_' || c == '~';
    }

    /** Lenient inverse of {@link #encode}: a malformed escape (a bare
     *  {@code %}) degrades to its literal text instead of failing extraction
     *  — a foreign peer's bad value must not change the failure type the
     *  caller sees. Package-visible so the other header propagators share
     *  one codec. */
    static String decode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()
                && Character.digit(value.charAt(i + 1), 16) >= 0
                && Character.digit(value.charAt(i + 2), 16) >= 0) {
                bytes.write((Character.digit(value.charAt(i + 1), 16) << 4)
                    | Character.digit(value.charAt(i + 2), 16));
                i += 2;
            } else {
                bytes.write(c & 0xFF); // header values arrive byte-wide; pass through as latin-1
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
