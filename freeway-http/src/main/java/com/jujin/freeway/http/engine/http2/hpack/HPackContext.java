package com.jujin.freeway.http.engine.http2.hpack;
import com.jujin.freeway.http.engine.http2.frame.FrameFlag;
import com.jujin.freeway.http.engine.http2.frame.FrameHeader;
import com.jujin.freeway.http.engine.http2.frame.FrameType;
import com.jujin.freeway.http.engine.http2.util.BinUtils;
import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;
import com.jujin.freeway.http.engine.http2.util.Http2HeaderField;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
    /**
     * Dynamic table (HPACK max table size in bytes, default 4096).
     */
    private final List<Http2HeaderField> dynamicTable = new ArrayList<>(256);
    private long dynamicTableByteSize;
    private long maxDynamicTableSize = 4096;

    /**
     * Reads a variable-length integer.
     *
     * @param b    the byte array
     * @param p    the starting position
     * @param bits the number of prefix bits
     * @return the parse result (new position and integer value)
     */
    static IntR readInt(byte[] b, int p, int bits) {
        int mask = (1 << bits) - 1;
        int value = b[p] & mask;
        p++;
        if (value < mask) return new IntR(p, value);

        // Multi-byte encoding
        int shift = 0;
        int x;
        do {
            if (p >= b.length) throw new ArrayIndexOutOfBoundsException("Truncated HPACK integer");
            x = b[p] & 0xFF;
            value += (x & 0x7F) << shift;
            shift += 7;
            p++;
        } while ((x & 0x80) != 0);
        return new IntR(p, value);
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
        byte[] nameBytes = nameIndex != null ? null : encodeString(name.getBytes());
        byte[] valueBytes = encodeString(value.getBytes());

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
     * Encodes a string (with Huffman flag support).
     */
    static byte[] encodeString(byte[] value) {
        return encodeString(value, 7);
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
        return (f.name != null ? f.name.length() : 0)
                + (f.value != null ? f.value.length() : 0) + 32;
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
        var fields = new ArrayList<Http2HeaderField>(8);
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
            fields.add(field);
        }
        return fields;
    }

    /** Decodes a dynamic table size update. */
    private int decodeDynamicTableSize(byte[] block, int pos) throws IOException {
        var result = readInt(block, pos, 5);
        if (result.value > 4096) throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR);
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
                : new String(block, result.position, result.value);
            if (!name.equals(name.toLowerCase())) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
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
        field.value = huffmanEncoded ? Huffman.decode(block, result.position, result.value) : new String(block, result.position, result.value);
        return result.position + result.value;
    }

    /**
     * Writes response headers to the output stream.
     *
     * @param headers   the response header map
     * @param out       the output stream
     * @param streamId  the stream ID
     * @param endStream whether to end the stream
     */
    public void writeResponseHeaders(Map<String, List<String>> headers, OutputStream out, int streamId, boolean endStream) throws IOException {
        var buffer = new ByteArrayOutputStream(256);

        // Write :status pseudo-header
        String status = headers.getOrDefault(":status", List.of("200")).getFirst();
        buffer.write(encodeLiteral(":status", status));

        // Write remaining header fields
        for (var entry : headers.entrySet()) {
            if (entry.getKey().startsWith(":")) continue;  // skip pseudo-headers
            String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            for (String value : entry.getValue()) {
                buffer.write(encodeLiteral(key, value));
            }
        }

        // Write frame header and data
        var flags = endStream
            ? FrameFlag.FlagSet.of(FrameFlag.END_HEADERS, FrameFlag.END_STREAM)
            : FrameFlag.FlagSet.of(FrameFlag.END_HEADERS);
        FrameHeader.writeTo(out, buffer.size(), FrameType.HEADERS, flags, streamId);
        buffer.writeTo(out);
    }

    private record IntR(int position, int value) {
    }
}
