package com.jujin.freeway.http.engine.http2;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public final class BinUtils {
    private BinUtils() {}

    public static int readInt(byte[] bytes, int offset, int length) {
        int result = 0;
        for (int i = offset + length - 1; i >= offset; i--)
            result |= (bytes[i] & 0xff) << (8 * (offset + length - 1 - i));
        return result;
    }

    public static int readInt(byte[] bytes, int offset) {
        return readInt(bytes, offset, 4);
    }

    public static long readLong(byte[] bytes, int offset, int length) {
        long result = 0;
        for (int i = offset + length - 1; i >= offset; i--)
            result |= (long) (bytes[i] & 0xff) << (8 * (offset + length - 1 - i));
        return result;
    }

    public static void writeInt(byte[] buffer, int position, int value, int length) {
        for (int i = 0; i < length; i++)
            buffer[i + position] = (byte) ((value >> (8 * (length - 1 - i))) & 0xff);
    }

    public static void writeInt(byte[] buffer, int position, int value) {
        writeInt(buffer, position, value, 4);
    }

    public static void writeInt(OutputStream outputStream, int value, int length) throws IOException {
        for (int i = length - 1; i >= 0; i--)
            outputStream.write((byte) ((value >> (8 * i)) & 0xff));
    }

    public static void writeInt(OutputStream outputStream, int value) throws IOException {
        writeInt(outputStream, value, 4);
    }

    public static byte[] combine(byte[]... blocks) {
        if (blocks.length == 0) return new byte[0];
        if (blocks.length == 1) return blocks[0];
        int totalLength = 0;
        for (var block : blocks) totalLength += block.length;
        byte[] result = new byte[totalLength];
        int offset = 0;
        for (var block : blocks) {
            System.arraycopy(block, 0, result, offset, block.length);
            offset += block.length;
        }
        return result;
    }

    public static byte[] combine(List<byte[]> blocks) {
        if (blocks.isEmpty()) return new byte[0];
        if (blocks.size() == 1) return blocks.getFirst();
        int totalLength = 0;
        for (var block : blocks) totalLength += block.length;
        byte[] result = new byte[totalLength];
        int offset = 0;
        for (var block : blocks) {
            System.arraycopy(block, 0, result, offset, block.length);
            offset += block.length;
        }
        return result;
    }

    public static byte[] combine(List<byte[]> array1, List<byte[]> array2) {
        int totalLength = 0;
        for (var block : array1) totalLength += block.length;
        for (var block : array2) totalLength += block.length;
        if (totalLength == 0) return new byte[0];
        byte[] result = new byte[totalLength];
        int offset = 0;
        for (var block : array1) {
            System.arraycopy(block, 0, result, offset, block.length);
            offset += block.length;
        }
        for (var block : array2) {
            System.arraycopy(block, 0, result, offset, block.length);
            offset += block.length;
        }
        return result;
    }
}
