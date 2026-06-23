package com.jujin.freeway.http.engine.http20.hpack;
import com.jujin.freeway.http.engine.http20.frame.FrameFlag;
import com.jujin.freeway.http.engine.http20.frame.FrameHeader;
import com.jujin.freeway.http.engine.http20.frame.FrameType;
import com.jujin.freeway.http.engine.http20.util.BinUtils;
import com.jujin.freeway.http.engine.http20.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http20.util.Http2Exception;
import com.jujin.freeway.http.engine.http20.util.Http2HeaderField;

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
    /** Dynamic table (max 1024 entries). */
    private final List<Http2HeaderField> dynamicTable = new ArrayList<>(1024);

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
        int length = value.length;
        if (length < 128) {
            byte[] result = new byte[1 + length];
            result[0] = (byte) length;
            System.arraycopy(value, 0, result, 1, length);
            return result;
        }
        // Long strings use two-byte length encoding
        byte[] result = new byte[2 + length];
        result[0] = (byte) (length | 0x80);
        result[1] = (byte) (length >> 7);
        System.arraycopy(value, 0, result, 2, length);
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
                dynamicTable.addFirst(field);
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

    /** Decodes an indexed header field. */
    private int decodeIndexed(byte[] block, int pos, Http2HeaderField field) throws IOException {
        var result = readInt(block, pos, 7);
        var indexedField = get(result.value);
        if (indexedField == null) throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR);
        field.name = indexedField.name;
        field.normalizedName = indexedField.normalizedName;
        field.value = indexedField.value;
        return result.position;
    }

    /** Decodes an incremental-indexed literal header. */
    private int decodeIncremental(byte[] block, int pos, Http2HeaderField field) throws IOException {
        var result = readInt(block, pos, 6);
        pos = decodeName(block, result.position, result.value, field);
        return decodeValue(block, pos, field);
    }

    /** Decodes a without-indexing literal header. */
    private int decodeWithoutIndexing(byte[] block, int pos, Http2HeaderField field) throws IOException {
        var result = readInt(block, pos, 4);
        pos = decodeName(block, result.position, result.value, field);
        return decodeValue(block, pos, field);
    }

    /** Decodes a never-indexed literal header. */
    private int decodeNeverIndexed(byte[] block, int pos, Http2HeaderField field) throws IOException {
        var result = readInt(block, pos, 4);
        pos = decodeName(block, result.position, result.value, field);
        return decodeValue(block, pos, field);
    }

    /** Decodes a dynamic table size update. */
    private int decodeDynamicTableSize(byte[] block, int pos) throws IOException {
        var result = readInt(block, pos, 5);
        if (result.value > 4096) throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR);
        while (dynamicTable.size() > result.value) dynamicTable.removeLast();
        return result.position;
    }

    /**
     * Decodes a header name (may be indexed or literal).
     */
    private int decodeName(byte[] block, int pos, int index, Http2HeaderField field) throws IOException {
        if (index == 0) {
            // Literal name
            boolean huffmanEncoded = (block[pos] & 0x80) != 0;
            int length = block[pos] & 0x7F;
            pos++;
            String name = huffmanEncoded ? Huffman.decode(block, pos, length) : new String(block, pos, length);
            if (!name.equals(name.toLowerCase())) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
            field.name = name;
            field.normalizedName = Http2HeaderField.normalize(name);
            return pos + length;
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
            String key = Character.toLowerCase(entry.getKey().charAt(0)) + entry.getKey().substring(1);
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
