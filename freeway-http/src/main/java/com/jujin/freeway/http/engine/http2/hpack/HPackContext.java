package com.jujin.freeway.http.engine.http2.hpack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.jujin.freeway.http.HttpUtils;
import com.jujin.freeway.http.engine.http2.frame.FrameFlag;
import com.jujin.freeway.http.engine.http2.frame.FrameHeader;
import com.jujin.freeway.http.engine.http2.frame.FrameType;
import com.jujin.freeway.http.engine.http2.util.BinUtils;
import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;
import com.jujin.freeway.http.engine.http2.util.Http2HeaderField;

/**
 * HPACK codec implementation (RFC 7541).
 * <p>Handles HTTP/2 header compression and decompression including:
 * <ul>
 *   <li>Static table lookup (StaticHeaderTable)</li>
 *   <li>Dynamic table management</li>
 *   <li>Huffman encoding/decoding</li>
 *   <li>Variable-length integer encoding/decoding</li>
 * </ul>
 */
public final class HPackContext {
    /** Hard local ceiling for memory used by an inbound peer's HPACK table. */
    private static final long MAX_INBOUND_DYNAMIC_TABLE_SIZE = 64 * 1024;
    /**
     * Dynamic table (HPACK max table size in bytes, default 4096).
     */
    private final List<Http2HeaderField> dynamicTable = new ArrayList<>(256);
    private long dynamicTableByteSize;
    private long maxDynamicTableSize = 4096;

    /** Adjusts the decoder's dynamic table cap to the encoder's advertised
     *  SETTINGS_HEADER_TABLE_SIZE (RFC 7541 §4.2). */
    public void setMaxDynamicTableSize(long size) {
        // SETTINGS_HEADER_TABLE_SIZE is a 32-bit unsigned value (RFC 7540
        // §6.5.2), so a negative cap is never legitimate. A negative cap
        // would evict the whole dynamic table and make every subsequent
        // in-band size update fail with COMPRESSION_ERROR — permanently
        // poisoning the decoder state. The wire parse is unsigned, so this
        // guards only in-memory misuse; clamp defensively.
        maxDynamicTableSize = Math.min(Math.max(size, 0), MAX_INBOUND_DYNAMIC_TABLE_SIZE);
        trimDynamicTable();
    }

    /**
     * Reads a variable-length integer.
     *
     * @param b    the byte array
     * @param p    the starting position
     * @param bits the number of prefix bits
     * @return the parse result (new position and integer value)
     */
    /**
     * Decodes an HPACK integer (RFC 7541 §5.1). Values beyond 2^31-1 must be
     * rejected with COMPRESSION_ERROR — the int accumulator would otherwise
     * silently wrap and desynchronize the header block.
     */
    static IntR readInt(byte[] b, int p, int bits) throws IOException {
        // A missing prefix byte is just as truncated as a missing
        // continuation byte — both must fail as a compression error, never
        // as an AIOOBE that escapes the codec.
        if (p >= b.length) {
            throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                "Truncated HPACK integer");
        }
        int mask = (1 << bits) - 1;
        long value = b[p] & mask;
        p++;
        if (value < mask) return new IntR(p, (int) value);

        // Multi-byte encoding
        int shift = 0;
        int x;
        do {
            if (p >= b.length) {
                throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                    "Truncated HPACK integer");
            }
            x = b[p] & 0xFF;
            // value += (x & 0x7F) << shift must stay within int range:
            // shift 29+ always overflows; shift == 28 allows up to 7<<28.
            long digit = x & 0x7F;
            if (shift >= 31 || (digit << shift) > Integer.MAX_VALUE - value) {
                throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                    "HPACK integer exceeds 2^31-1");
            }
            value += digit << shift;
            shift += 7;
            p++;
        } while ((x & 0x80) != 0);
        return new IntR(p, (int) value);
    }

    /**
     * Encodes a literal header field.
     */
    private static byte[] encodeLiteral(String name, String value) {
        // :status special handling
        if (":status".equals(name)) {
            byte[] index = StaticHeaderTable.statusIndex(value);
            if (index != null) return index;
        }

        Integer nameIndex = StaticHeaderTable.nameIndex(name);
        byte[] prefix = nameIndex != null ? encodeIntValue(nameIndex, 4) : encodeIntValue(0, 4);
        byte[] nameBytes = nameIndex != null ? null : encodeStringHuffman(name.getBytes(StandardCharsets.UTF_8));
        byte[] valueBytes = encodeStringHuffman(value.getBytes(StandardCharsets.UTF_8));

        return nameBytes == null ? BinUtils.combine(prefix, valueBytes) : BinUtils.combine(prefix, nameBytes, valueBytes);
    }

    /**
     * Encodes an integer value with variable-length encoding.
     */
    static byte[] encodeIntValue(int value, int prefixBits) {
        int mask = (1 << prefixBits) - 1;
        if (value < mask) return new byte[]{(byte) value};

        byte[] result = new byte[]{(byte) mask};
        value -= mask;
        while (value >= 128) {
            result = Arrays.copyOf(result, result.length + 1);
            result[result.length - 1] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        result = Arrays.copyOf(result, result.length + 1);
        result[result.length - 1] = (byte) value;
        return result;
    }

    /**
     * Encodes a string WITHOUT the Huffman flag (H bit clear): length prefix
     * plus raw bytes. Retained for decode tests and symmetric coverage —
     * outbound response headers use {@link #encodeStringHuffman(byte[])}.
     */
    static byte[] encodeString(byte[] value) {
        return encodeString(value, 7);
    }

    /**
     * Encodes a string with the Huffman flag set: length prefix (with the
     * high bit of the prefix byte set) followed by Huffman-encoded bytes.
     */
    static byte[] encodeStringHuffman(byte[] value) {
        return encodeStringHuffman(value, 7);
    }

    static byte[] encodeStringHuffman(byte[] value, int prefixBits) {
        byte[] huffman = Huffman.encode(value);
        int prefixMask = (1 << prefixBits) - 1;
        int huffmanFlag = 1 << prefixBits;
        int length = huffman.length;
        if (length < prefixMask) {
            byte[] result = new byte[1 + length];
            result[0] = (byte) (huffmanFlag | length);
            System.arraycopy(huffman, 0, result, 1, length);
            return result;
        }
        // HPACK integer encoding: prefix holds all 1s, remainder in continuation
        int remaining = length - prefixMask;
        int contBytes = 1;
        while (remaining >= 128) { remaining >>= 7; contBytes++; }
        byte[] result = new byte[1 + contBytes + length];
        result[0] = (byte) (huffmanFlag | prefixMask);
        int pos = 1;
        remaining = length - prefixMask;
        do {
            int b = remaining & 0x7F;
            remaining >>= 7;
            if (remaining > 0) b |= 0x80;
            result[pos++] = (byte) b;
        } while (remaining > 0);
        System.arraycopy(huffman, 0, result, pos, length);
        return result;
    }

    static byte[] encodeString(byte[] value, int prefixBits) {
        int prefixMask = (1 << prefixBits) - 1;
        int length = value.length;
        if (length < prefixMask) {
            byte[] result = new byte[1 + length];
            result[0] = (byte) length;
            System.arraycopy(value, 0, result, 1, length);
            return result;
        }
        // HPACK integer encoding: prefix holds all 1s, remainder in continuation
        int remaining = length - prefixMask;
        int contBytes = 1;
        while (remaining >= 128) { remaining >>= 7; contBytes++; }
        byte[] result = new byte[1 + contBytes + length];
        result[0] = (byte) prefixMask;
        int pos = 1;
        remaining = length - prefixMask;
        do {
            int b = remaining & 0x7F;
            remaining >>= 7;
            if (remaining > 0) b |= 0x80;
            result[pos++] = (byte) b;
        } while (remaining > 0);
        System.arraycopy(value, 0, result, pos, length);
        return result;
    }

    /**
     * Retrieves a header field by index (static or dynamic table).
     */
    public Http2HeaderField get(int index) {
        if (index > 0 && index <= 61) return StaticHeaderTable.get(index);
        int dynamicIndex = index - 62;
        return dynamicIndex >= 0 && dynamicIndex < dynamicTable.size() ? dynamicTable.get(dynamicIndex) : null;
    }

    private static long headerFieldSize(Http2HeaderField f) {
        // RFC 7541 §4.1: size is measured in bytes (name + value + 32).
        long nameBytes = f.name != null ? f.name.getBytes(StandardCharsets.UTF_8).length : 0;
        long valueBytes = f.value != null ? f.value.getBytes(StandardCharsets.UTF_8).length : 0;
        return nameBytes + valueBytes + 32;
    }

    /**
     * Decodes an indexed header field.
     */
    private int decodeIndexed(byte[] block, int pos, Http2HeaderField field) throws IOException {
        var result = readInt(block, pos, 7);
        var indexedField = get(result.value);
        if (indexedField == null) throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR);
        field.name = indexedField.name;
        field.normalizedName = indexedField.normalizedName;
        field.value = indexedField.value;
        return result.position;
    }

    /**
     * Decodes an incremental-indexed literal header.
     */
    private int decodeIncremental(byte[] block, int pos, Http2HeaderField field) throws IOException {
        var result = readInt(block, pos, 6);
        pos = decodeName(block, result.position, result.value, field);
        return decodeValue(block, pos, field);
    }

    /**
     * Decodes a without-indexing literal header.
     */
    private int decodeWithoutIndexing(byte[] block, int pos, Http2HeaderField field) throws IOException {
        var result = readInt(block, pos, 4);
        pos = decodeName(block, result.position, result.value, field);
        return decodeValue(block, pos, field);
    }

    /**
     * Decodes a never-indexed literal header.
     */
    private int decodeNeverIndexed(byte[] block, int pos, Http2HeaderField field) throws IOException {
        var result = readInt(block, pos, 4);
        pos = decodeName(block, result.position, result.value, field);
        return decodeValue(block, pos, field);
    }

    /**
     * Decodes a header block into a list of header fields.
     *
     * @param block the encoded byte array
     * @return the decoded list of header fields
     */
    public List<Http2HeaderField> decode(byte[] block) throws IOException {
        return decode(block, Long.MAX_VALUE);
    }

    /**
     * Decodes an HPACK block, enforcing {@code maxHeaderListSize} on the
     * decoded field list. The connection passes its advertised
     * SETTINGS_MAX_HEADER_LIST_SIZE; raw header-block bytes are bounded by
     * the connection before this method is reached.
     */
    public List<Http2HeaderField> decode(byte[] block, long maxHeaderListSize)
            throws IOException {
        var fields = new ArrayList<Http2HeaderField>(8);
        long headerListSize = 0;
        int pos = 0;
        while (pos < block.length) {
            var field = new Http2HeaderField();
            byte firstByte = block[pos];

            if ((firstByte & 0x80) != 0) {
                // Indexed header field
                pos = decodeIndexed(block, pos, field);
            } else if ((firstByte & 0x40) != 0) {
                // Incremental-indexed literal header
                pos = decodeIncremental(block, pos, field);
                addToDynamicTable(field);
            } else if ((firstByte & 0xF0) == 0) {
                // Without-indexing literal header
                pos = decodeWithoutIndexing(block, pos, field);
            } else if ((firstByte & 0xF0) == 0x10) {
                // Never-indexed literal header
                pos = decodeNeverIndexed(block, pos, field);
            } else if ((firstByte & 0xE0) == 0x20) {
                // Dynamic table size update
                if (!fields.isEmpty()) throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR);
                pos = decodeDynamicTableSize(block, pos);
                continue;
            } else {
                throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR);
            }
            if (field.name != null && field.value != null) {
                headerListSize += (long) field.name.length() + field.value.length() + 32;
                if (headerListSize > maxHeaderListSize) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,
                        "Decoded header list exceeds " + maxHeaderListSize + " bytes");
                }
            }
            fields.add(field);
        }
        return fields;
    }

    /** Decodes a dynamic table size update. */
    private int decodeDynamicTableSize(byte[] block, int pos) throws IOException {
        var result = readInt(block, pos, 5);
        // RFC 7541 §4.2: an in-band size update must not exceed the limit the
        // encoder advertised via SETTINGS_HEADER_TABLE_SIZE.
        if (result.value > maxDynamicTableSize) {
            throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR);
        }
        maxDynamicTableSize = result.value;
        trimDynamicTable();
        return result.position;
    }

    private void trimDynamicTable() {
        while (dynamicTableByteSize > maxDynamicTableSize && !dynamicTable.isEmpty()) {
            var removed = dynamicTable.removeLast();
            dynamicTableByteSize -= headerFieldSize(removed);
        }
    }

    /**
     * Adds an incremental-indexed literal to the dynamic table, evicting
     * least-recently-used entries so the table stays within its size limit
     * (RFC 7541 §4.4). Entries larger than the limit are not stored.
     */
    private void addToDynamicTable(Http2HeaderField field) {
        long size = headerFieldSize(field);
        if (size > maxDynamicTableSize) return;
        dynamicTable.addFirst(field);
        dynamicTableByteSize += size;
        trimDynamicTable();
    }

    /**
     * Decodes a header name (may be indexed or literal).
     */
    private int decodeName(byte[] block, int pos, int index, Http2HeaderField field) throws IOException {
        if (index == 0) {
            // Literal name
            boolean huffmanEncoded = (block[pos] & 0x80) != 0;
            var result = readInt(block, pos, 7);
            if (result.position + result.value > block.length)
                throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR);
            String name = huffmanEncoded
                ? Huffman.decode(block, result.position, result.value)
                : new String(block, result.position, result.value, StandardCharsets.UTF_8);
            if (!name.equals(name.toLowerCase())) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
            // RFC 9113 §8.2 / RFC 9110 §5.1: a literal field name must be a
            // valid HTTP token (lowercase already enforced above). Lowercase
            // non-token names — non-ASCII letters, spaces, CTL — would reach
            // the application header keys and be rejected by the HTTP/1.1
            // front-end later, so reject them here. Pseudo-header names
            // start with ':' and are exempt: HeaderFields validates them
            // against the known pseudo-header set.
            if (!name.startsWith(":") && !HttpUtils.isToken(name))
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
            field.name = name;
            field.normalizedName = Http2HeaderField.normalize(name);
            return result.position + result.value;
        }
        // Indexed name
        var indexedField = get(index);
        if (indexedField == null) throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR);
        field.name = indexedField.name;
        field.normalizedName = indexedField.normalizedName;
        return pos;
    }

    /**
     * Decodes a header value (supports Huffman encoding).
     */
    private int decodeValue(byte[] block, int pos, Http2HeaderField field) throws IOException {
        boolean huffmanEncoded = (block[pos] & 0x80) != 0;
        var result = readInt(block, pos, 7);
        if (result.position + result.value > block.length) throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR);
        field.value = huffmanEncoded ? Huffman.decode(block, result.position, result.value) : new String(block, result.position, result.value, StandardCharsets.UTF_8);
        return result.position + result.value;
    }

    /**
     * Upper bound for the encoded size of one response's headers. Without it,
     * application code setting oversized header values would grow the
     * ByteArrayOutputStream without limit.
     */
    private static final int MAX_RESPONSE_HEADER_BYTES = 64 * 1024;

    /**
     * Writes response headers to the output stream.
     *
     * @param headers   the response header map
     * @param out       the output stream
     * @param streamId  the stream ID
     * @param endStream whether to end the stream
     */
    public void writeResponseHeaders(Map<String, List<String>> headers, OutputStream out, int streamId, boolean endStream) throws IOException {
        out.write(encodeResponseHeaders(headers, streamId, endStream));
    }

    /**
     * Encodes response headers as a complete HEADERS frame (header block +
     * frame header) so callers can queue the frame onto a batched outbound
     * path. Must be invoked under the connection lock: the encoder's dynamic
     * table state advances with each block, so encode order must equal frame
     * send order.
     */
    public byte[] encodeResponseHeaders(
            Map<String, List<String>> headers, int streamId, boolean endStream)
            throws IOException {
        var buffer = new ByteArrayOutputStream(256);

        // Write :status pseudo-header
        String status = headers.getOrDefault(":status", List.of("200")).getFirst();
        writeChecked(buffer, ":status", status);

        // Write remaining header fields
        for (var entry : headers.entrySet()) {
            if (entry.getKey().startsWith(":")) continue;  // skip pseudo-headers
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            for (String value : entry.getValue()) {
                writeChecked(buffer, key, value);
            }
        }

        byte[] headerBlock = buffer.toByteArray();
        var flags = endStream
            ? FrameFlag.FlagSet.of(FrameFlag.END_HEADERS, FrameFlag.END_STREAM)
            : FrameFlag.FlagSet.of(FrameFlag.END_HEADERS);
        byte[] frameHeader = FrameHeader.encode(
            headerBlock.length, FrameType.HEADERS, flags, streamId);
        return BinUtils.combine(frameHeader, headerBlock);
    }

    /**
     * Encodes one header field into {@code buffer} unless the encoded size
     * budget is exceeded. The budget check runs BEFORE encoding so an
     * oversized value is rejected before the encoder allocates its block.
     */
    private static void writeChecked(ByteArrayOutputStream buffer, String key, String value) throws IOException {
        // Upper bound of encodeLiteral: 1 (Huffman flag) + 5 (length prefix)
        // + key length + value length.
        long budget = buffer.size() + (long) key.length() + value.length() + 8;
        if (budget > MAX_RESPONSE_HEADER_BYTES) {
            throw new IOException(
                "Response headers exceed " + MAX_RESPONSE_HEADER_BYTES
                    + " bytes (at header '" + key + "')"
            );
        }
        buffer.write(encodeLiteral(key, value));
    }

    private record IntR(int position, int value) {
    }
}
