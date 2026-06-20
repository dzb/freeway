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
 * HPACK 编解码器实现（RFC 7541）
 * <p>负责 HTTP/2 头部压缩和解压缩，包括：
 * <ul>
 *   <li>静态表查找（StaticHeaderTable）</li>
 *   <li>动态表管理</li>
 *   <li>Huffman 编码/解码</li>
 *   <li>整数编码/解码</li>
 * </ul>
 */
public final class HPackContext {
    /** 动态表（最大 1024 条目） */
    private final List<Http2HeaderField> dynamicTable = new ArrayList<>(1024);

    /**
     * 读取可变长度整数
     *
     * @param b    字节数组
     * @param p    起始位置
     * @param bits 前缀位数
     * @return 解析结果（新位置和整数值）
     */
    static IntR readInt(byte[] b, int p, int bits) {
        int mask = (1 << bits) - 1;
        int value = b[p] & mask;
        p++;
        if (value < mask) return new IntR(p, value);
        
        // 多字节编码
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
     * 编码字面量头部字段
     */
    private static byte[] encodeLiteral(String name, String value) {
        // 检查 :status 特殊处理
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
     * 编码整数值（可变长度）
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
     * 编码字符串（支持 Huffman 标记）
     */
    static byte[] encodeString(byte[] value) {
        int length = value.length;
        if (length < 128) {
            byte[] result = new byte[1 + length];
            result[0] = (byte) length;
            System.arraycopy(value, 0, result, 1, length);
            return result;
        }
        // 长字符串使用两字节长度编码
        byte[] result = new byte[2 + length];
        result[0] = (byte) (length | 0x80);
        result[1] = (byte) (length >> 7);
        System.arraycopy(value, 0, result, 2, length);
        return result;
    }

    /**
     * 根据索引获取头部字段（静态表或动态表）
     */
    public Http2HeaderField get(int index) {
        if (index > 0 && index <= 61) return StaticHeaderTable.get(index);
        int dynamicIndex = index - 62;
        return dynamicIndex >= 0 && dynamicIndex < dynamicTable.size() ? dynamicTable.get(dynamicIndex) : null;
    }

    /**
     * 解码头部块为字段列表
     *
     * @param block 编码后的字节数组
     * @return 解码后的头部字段列表
     */
    public List<Http2HeaderField> decode(byte[] block) throws IOException {
        var fields = new ArrayList<Http2HeaderField>(8);
        int pos = 0;
        while (pos < block.length) {
            var field = new Http2HeaderField();
            byte firstByte = block[pos];
            
            if ((firstByte & 0x80) != 0) {
                // 索引表示的头部字段
                pos = decodeIndexed(block, pos, field);
            } else if ((firstByte & 0x40) != 0) {
                // 增量索引的字面量头部
                pos = decodeIncremental(block, pos, field);
                dynamicTable.addFirst(field);
            } else if ((firstByte & 0xF0) == 0) {
                // 不索引的字面量头部
                pos = decodeWithoutIndexing(block, pos, field);
            } else if ((firstByte & 0xF0) == 0x10) {
                // 从不索引的字面量头部
                pos = decodeNeverIndexed(block, pos, field);
            } else if ((firstByte & 0xE0) == 0x20) {
                // 动态表大小更新
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

    /**
     * 解码索引表示的头部字段
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
     * 解码增量索引的字面量头部
     */
    private int decodeIncremental(byte[] block, int pos, Http2HeaderField field) throws IOException {
        var result = readInt(block, pos, 6);
        pos = decodeName(block, result.position, result.value, field);
        return decodeValue(block, pos, field);
    }

    /**
     * 解码不索引的字面量头部
     */
    private int decodeWithoutIndexing(byte[] block, int pos, Http2HeaderField field) throws IOException {
        var result = readInt(block, pos, 4);
        pos = decodeName(block, result.position, result.value, field);
        return decodeValue(block, pos, field);
    }

    /**
     * 解码从不索引的字面量头部
     */
    private int decodeNeverIndexed(byte[] block, int pos, Http2HeaderField field) throws IOException {
        var result = readInt(block, pos, 4);
        pos = decodeName(block, result.position, result.value, field);
        return decodeValue(block, pos, field);
    }

    /**
     * 解码动态表大小更新
     */
    private int decodeDynamicTableSize(byte[] block, int pos) throws IOException {
        var result = readInt(block, pos, 5);
        if (result.value > 4096) throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR);
        while (dynamicTable.size() > result.value) dynamicTable.removeLast();
        return result.position;
    }

    /**
     * 解码头部名称（可能是索引或字面量）
     */
    private int decodeName(byte[] block, int pos, int index, Http2HeaderField field) throws IOException {
        if (index == 0) {
            // 字面量名称
            boolean huffmanEncoded = (block[pos] & 0x80) != 0;
            int length = block[pos] & 0x7F;
            pos++;
            String name = huffmanEncoded ? Huffman.decode(block, pos, length) : new String(block, pos, length);
            if (!name.equals(name.toLowerCase())) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
            field.name = name;
            field.normalizedName = Http2HeaderField.normalize(name);
            return pos + length;
        }
        // 索引名称
        var indexedField = get(index);
        if (indexedField == null) throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR);
        field.name = indexedField.name;
        field.normalizedName = indexedField.normalizedName;
        return pos;
    }

    /**
     * 解码头部值（支持 Huffman 编码）
     */
    private int decodeValue(byte[] block, int pos, Http2HeaderField field) throws IOException {
        boolean huffmanEncoded = (block[pos] & 0x80) != 0;
        var result = readInt(block, pos, 7);
        field.value = huffmanEncoded ? Huffman.decode(block, result.position, result.value) : new String(block, result.position, result.value);
        return result.position + result.value;
    }

    /**
     * 写入响应头部到输出流
     *
     * @param headers 响应头部Map
     * @param out     输出流
     * @param streamId 流ID
     * @param endStream 是否结束流
     */
    public void writeResponseHeaders(Map<String, List<String>> headers, OutputStream out, int streamId, boolean endStream) throws IOException {
        var buffer = new ByteArrayOutputStream(256);
        
        // 写入 :status 伪头部
        String status = headers.getOrDefault(":status", List.of("200")).getFirst();
        buffer.write(encodeLiteral(":status", status));
        
        // 写入其他头部字段
        for (var entry : headers.entrySet()) {
            if (entry.getKey().startsWith(":")) continue;  // 跳过伪头部
            String key = Character.toLowerCase(entry.getKey().charAt(0)) + entry.getKey().substring(1);
            for (String value : entry.getValue()) {
                buffer.write(encodeLiteral(key, value));
            }
        }
        
        // 写入帧头和数据
        var flags = endStream 
            ? FrameFlag.FlagSet.of(FrameFlag.END_HEADERS, FrameFlag.END_STREAM) 
            : FrameFlag.FlagSet.of(FrameFlag.END_HEADERS);
        FrameHeader.writeTo(out, buffer.size(), FrameType.HEADERS, flags, streamId);
        buffer.writeTo(out);
    }

    private record IntR(int position, int value) {
    }
}
