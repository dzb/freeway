package com.jujin.freeway.http.engine.http2.hpack;
import com.jujin.freeway.http.engine.http2.util.Http2ErrorCode;
import com.jujin.freeway.http.engine.http2.util.Http2Exception;

import java.io.IOException;

/**
 * HPACK Huffman codec (RFC 7541 Appendix B).
 * Decodes Huffman-encoded strings using a table-driven lookup.
 */
public final class Huffman {
    private static final int[][] CODE_TABLE = new int[257][];

    static {
        int[][] table = CODE_TABLE;
        table[0] = new int[]{0x1ff8, 13};
        table[1] = new int[]{0x7fffd8, 23};
        table[2] = new int[]{0xfffffe2, 28};
        table[3] = new int[]{0xfffffe3, 28};
        table[4] = new int[]{0xfffffe4, 28};
        table[5] = new int[]{0xfffffe5, 28};
        table[6] = new int[]{0xfffffe6, 28};
        table[7] = new int[]{0xfffffe7, 28};
        table[8] = new int[]{0xfffffe8, 28};
        table[9] = new int[]{0xffffea, 24};
        table[10] = new int[]{0x3ffffffc, 30};
        table[11] = new int[]{0xfffffe9, 28};
        table[12] = new int[]{0xfffffea, 28};
        table[13] = new int[]{0x3ffffffd, 30};
        table[14] = new int[]{0xfffffeb, 28};
        table[15] = new int[]{0xfffffec, 28};
        table[16] = new int[]{0xfffffed, 28};
        table[17] = new int[]{0xfffffee, 28};
        table[18] = new int[]{0xfffffef, 28};
        table[19] = new int[]{0xffffff0, 28};
        table[20] = new int[]{0xffffff1, 28};
        table[21] = new int[]{0xffffff2, 28};
        table[22] = new int[]{0x3ffffffe, 30};
        table[23] = new int[]{0xffffff3, 28};
        table[24] = new int[]{0xffffff4, 28};
        table[25] = new int[]{0xffffff5, 28};
        table[26] = new int[]{0xffffff6, 28};
        table[27] = new int[]{0xffffff7, 28};
        table[28] = new int[]{0xffffff8, 28};
        table[29] = new int[]{0xffffff9, 28};
        table[30] = new int[]{0xffffffa, 28};
        table[31] = new int[]{0xffffffb, 28};
        table[32] = new int[]{0x14, 6};
        table[33] = new int[]{0x3f8, 10};
        table[34] = new int[]{0x3f9, 10};
        table[35] = new int[]{0xffa, 12};
        table[36] = new int[]{0x1ff9, 13};
        table[37] = new int[]{0x15, 6};
        table[38] = new int[]{0xf8, 8};
        table[39] = new int[]{0x7fa, 11};
        table[40] = new int[]{0x3fa, 10};
        table[41] = new int[]{0x3fb, 10};
        table[42] = new int[]{0xf9, 8};
        table[43] = new int[]{0x7fb, 11};
        table[44] = new int[]{0xfa, 8};
        table[45] = new int[]{0x16, 6};
        table[46] = new int[]{0x17, 6};
        table[47] = new int[]{0x18, 6};
        table[48] = new int[]{0x0, 5};
        table[49] = new int[]{0x1, 5};
        table[50] = new int[]{0x2, 5};
        table[51] = new int[]{0x19, 6};
        table[52] = new int[]{0x1a, 6};
        table[53] = new int[]{0x1b, 6};
        table[54] = new int[]{0x1c, 6};
        table[55] = new int[]{0x1d, 6};
        table[56] = new int[]{0x1e, 6};
        table[57] = new int[]{0x1f, 6};
        table[58] = new int[]{0x5c, 7};
        table[59] = new int[]{0xfb, 8};
        table[60] = new int[]{0x7ffc, 15};
        table[61] = new int[]{0x20, 6};
        table[62] = new int[]{0xffb, 12};
        table[63] = new int[]{0x3fc, 10};
        table[64] = new int[]{0x1ffa, 13};
        table[65] = new int[]{0x21, 6};
        table[66] = new int[]{0x5d, 7};
        table[67] = new int[]{0x5e, 7};
        table[68] = new int[]{0x5f, 7};
        table[69] = new int[]{0x60, 7};
        table[70] = new int[]{0x61, 7};
        table[71] = new int[]{0x62, 7};
        table[72] = new int[]{0x63, 7};
        table[73] = new int[]{0x64, 7};
        table[74] = new int[]{0x65, 7};
        table[75] = new int[]{0x66, 7};
        table[76] = new int[]{0x67, 7};
        table[77] = new int[]{0x68, 7};
        table[78] = new int[]{0x69, 7};
        table[79] = new int[]{0x6a, 7};
        table[80] = new int[]{0x6b, 7};
        table[81] = new int[]{0x6c, 7};
        table[82] = new int[]{0x6d, 7};
        table[83] = new int[]{0x6e, 7};
        table[84] = new int[]{0x6f, 7};
        table[85] = new int[]{0x70, 7};
        table[86] = new int[]{0x71, 7};
        table[87] = new int[]{0x72, 7};
        table[88] = new int[]{0xfc, 8};
        table[89] = new int[]{0x73, 7};
        table[90] = new int[]{0xfd, 8};
        table[91] = new int[]{0x1ffb, 13};
        table[92] = new int[]{0x7fff0, 19};
        table[93] = new int[]{0x1ffc, 13};
        table[94] = new int[]{0x3ffc, 14};
        table[95] = new int[]{0x22, 6};
        table[96] = new int[]{0x7ffd, 15};
        table[97] = new int[]{0x3, 5};
        table[98] = new int[]{0x23, 6};
        table[99] = new int[]{0x4, 5};
        table[100] = new int[]{0x24, 6};
        table[101] = new int[]{0x5, 5};
        table[102] = new int[]{0x25, 6};
        table[103] = new int[]{0x26, 6};
        table[104] = new int[]{0x27, 6};
        table[105] = new int[]{0x6, 5};
        table[106] = new int[]{0x74, 7};
        table[107] = new int[]{0x75, 7};
        table[108] = new int[]{0x28, 6};
        table[109] = new int[]{0x29, 6};
        table[110] = new int[]{0x2a, 6};
        table[111] = new int[]{0x7, 5};
        table[112] = new int[]{0x2b, 6};
        table[113] = new int[]{0x76, 7};
        table[114] = new int[]{0x2c, 6};
        table[115] = new int[]{0x8, 5};
        table[116] = new int[]{0x9, 5};
        table[117] = new int[]{0x2d, 6};
        table[118] = new int[]{0x77, 7};
        table[119] = new int[]{0x78, 7};
        table[120] = new int[]{0x79, 7};
        table[121] = new int[]{0x7a, 7};
        table[122] = new int[]{0x7b, 7};
        table[123] = new int[]{0x7ffe, 15};
        table[124] = new int[]{0x7fc, 11};
        table[125] = new int[]{0x3ffd, 14};
        table[126] = new int[]{0x1ffd, 13};
        table[127] = new int[]{0xffffffc, 28};
        table[128] = new int[]{0xfffe6, 20};
        table[129] = new int[]{0x3fffd2, 22};
        table[130] = new int[]{0xfffe7, 20};
        table[131] = new int[]{0xfffe8, 20};
        table[132] = new int[]{0x3fffd3, 22};
        table[133] = new int[]{0x3fffd4, 22};
        table[134] = new int[]{0x3fffd5, 22};
        table[135] = new int[]{0x7fffd9, 23};
        table[136] = new int[]{0x3fffd6, 22};
        table[137] = new int[]{0x7fffda, 23};
        table[138] = new int[]{0x7fffdb, 23};
        table[139] = new int[]{0x7fffdc, 23};
        table[140] = new int[]{0x7fffdd, 23};
        table[141] = new int[]{0x7fffde, 23};
        table[142] = new int[]{0xffffeb, 24};
        table[143] = new int[]{0x7fffdf, 23};
        table[144] = new int[]{0xffffec, 24};
        table[145] = new int[]{0xffffed, 24};
        table[146] = new int[]{0x3fffd7, 22};
        table[147] = new int[]{0x7fffe0, 23};
        table[148] = new int[]{0xffffee, 24};
        table[149] = new int[]{0x7fffe1, 23};
        table[150] = new int[]{0x7fffe2, 23};
        table[151] = new int[]{0x7fffe3, 23};
        table[152] = new int[]{0x7fffe4, 23};
        table[153] = new int[]{0x1fffdc, 21};
        table[154] = new int[]{0x3fffd8, 22};
        table[155] = new int[]{0x7fffe5, 23};
        table[156] = new int[]{0x3fffd9, 22};
        table[157] = new int[]{0x7fffe6, 23};
        table[158] = new int[]{0x7fffe7, 23};
        table[159] = new int[]{0xffffef, 24};
        table[160] = new int[]{0x3fffda, 22};
        table[161] = new int[]{0x1fffdd, 21};
        table[162] = new int[]{0xfffe9, 20};
        table[163] = new int[]{0x3fffdb, 22};
        table[164] = new int[]{0x3fffdc, 22};
        table[165] = new int[]{0x7fffe8, 23};
        table[166] = new int[]{0x7fffe9, 23};
        table[167] = new int[]{0x1fffde, 21};
        table[168] = new int[]{0x7fffea, 23};
        table[169] = new int[]{0x3fffdd, 22};
        table[170] = new int[]{0x3fffde, 22};
        table[171] = new int[]{0xfffff0, 24};
        table[172] = new int[]{0x1fffdf, 21};
        table[173] = new int[]{0x3fffdf, 22};
        table[174] = new int[]{0x7fffeb, 23};
        table[175] = new int[]{0x7fffec, 23};
        table[176] = new int[]{0x1fffe0, 21};
        table[177] = new int[]{0x1fffe1, 21};
        table[178] = new int[]{0x3fffe0, 22};
        table[179] = new int[]{0x1fffe2, 21};
        table[180] = new int[]{0x7fffed, 23};
        table[181] = new int[]{0x3fffe1, 22};
        table[182] = new int[]{0x7fffee, 23};
        table[183] = new int[]{0x7fffef, 23};
        table[184] = new int[]{0xfffea, 20};
        table[185] = new int[]{0x3fffe2, 22};
        table[186] = new int[]{0x3fffe3, 22};
        table[187] = new int[]{0x3fffe4, 22};
        table[188] = new int[]{0x7ffff0, 23};
        table[189] = new int[]{0x3fffe5, 22};
        table[190] = new int[]{0x3fffe6, 22};
        table[191] = new int[]{0x7ffff1, 23};
        table[192] = new int[]{0x3ffffe0, 26};
        table[193] = new int[]{0x3ffffe1, 26};
        table[194] = new int[]{0xfffeb, 20};
        table[195] = new int[]{0x7fff1, 19};
        table[196] = new int[]{0x3fffe7, 22};
        table[197] = new int[]{0x7ffff2, 23};
        table[198] = new int[]{0x3fffe8, 22};
        table[199] = new int[]{0x1ffffec, 25};
        table[200] = new int[]{0x3ffffe2, 26};
        table[201] = new int[]{0x3ffffe3, 26};
        table[202] = new int[]{0x3ffffe4, 26};
        table[203] = new int[]{0x7ffffde, 27};
        table[204] = new int[]{0x7ffffdf, 27};
        table[205] = new int[]{0x3ffffe5, 26};
        table[206] = new int[]{0xfffff1, 24};
        table[207] = new int[]{0x1ffffed, 25};
        table[208] = new int[]{0x7fff2, 19};
        table[209] = new int[]{0x1fffe3, 21};
        table[210] = new int[]{0x3ffffe6, 26};
        table[211] = new int[]{0x7ffffe0, 27};
        table[212] = new int[]{0x7ffffe1, 27};
        table[213] = new int[]{0x3ffffe7, 26};
        table[214] = new int[]{0x7ffffe2, 27};
        table[215] = new int[]{0xfffff2, 24};
        table[216] = new int[]{0x1fffe4, 21};
        table[217] = new int[]{0x1fffe5, 21};
        table[218] = new int[]{0x3ffffe8, 26};
        table[219] = new int[]{0x3ffffe9, 26};
        table[220] = new int[]{0xffffffd, 28};
        table[221] = new int[]{0x7ffffe3, 27};
        table[222] = new int[]{0x7ffffe4, 27};
        table[223] = new int[]{0x7ffffe5, 27};
        table[224] = new int[]{0xfffec, 20};
        table[225] = new int[]{0xfffff3, 24};
        table[226] = new int[]{0xfffed, 20};
        table[227] = new int[]{0x1fffe6, 21};
        table[228] = new int[]{0x3fffe9, 22};
        table[229] = new int[]{0x1fffe7, 21};
        table[230] = new int[]{0x1fffe8, 21};
        table[231] = new int[]{0x7ffff3, 23};
        table[232] = new int[]{0x3fffea, 22};
        table[233] = new int[]{0x3fffeb, 22};
        table[234] = new int[]{0x1ffffee, 25};
        table[235] = new int[]{0x1ffffef, 25};
        table[236] = new int[]{0xfffff4, 24};
        table[237] = new int[]{0xfffff5, 24};
        table[238] = new int[]{0x3ffffea, 26};
        table[239] = new int[]{0x7ffff4, 23};
        table[240] = new int[]{0x3ffffeb, 26};
        table[241] = new int[]{0x7ffffe6, 27};
        table[242] = new int[]{0x3ffffec, 26};
        table[243] = new int[]{0x3ffffed, 26};
        table[244] = new int[]{0x7ffffe7, 27};
        table[245] = new int[]{0x7ffffe8, 27};
        table[246] = new int[]{0x7ffffe9, 27};
        table[247] = new int[]{0x7ffffea, 27};
        table[248] = new int[]{0x7ffffeb, 27};
        table[249] = new int[]{0xffffffe, 28};
        table[250] = new int[]{0x7ffffec, 27};
        table[251] = new int[]{0x7ffffed, 27};
        table[252] = new int[]{0x7ffffee, 27};
        table[253] = new int[]{0x7ffffef, 27};
        table[254] = new int[]{0x7fffff0, 27};
        table[255] = new int[]{0x3ffffee, 26};
        table[256] = new int[]{0x3fffffff, 30};
    }

    private Huffman() {}

    /**
     * Decodes a Huffman-encoded byte array into a string.
     *
     * @param data  the encoded data
     * @return the decoded string
     */
    public static String decode(byte[] data) throws IOException {
        return decode(data, 0, data.length);
    }

    /**
     * Decodes a portion of a Huffman-encoded byte array.
     *
     * @param data   the encoded data
     * @param offset starting offset within the data
     * @param length number of bytes to decode
     * @return the decoded string
     */
    public static String decode(byte[] data, int offset, int length) throws IOException {
        var output = new StringBuilder(length);
        long buffer = 0;
        int bitsInBuffer = 0;

        for (int i = offset; i < offset + length; i++) {
            buffer = (buffer << 8) | (data[i] & 0xFF);
            bitsInBuffer += 8;

            while (bitsInBuffer >= 5) {
                int matchedSymbol = -1;

                // Find the longest matching code entry
                for (int candidate = 0; candidate < 257; candidate++) {
                    int[] codeEntry = CODE_TABLE[candidate];
                    if (codeEntry == null || codeEntry[1] > bitsInBuffer) continue;

                    if ((buffer >> (bitsInBuffer - codeEntry[1])) == codeEntry[0]) {
                        // Verify there isn't a longer (more specific) match
                        boolean hasLongerMatch = false;
                        for (int otherCandidate = 0; otherCandidate < 257; otherCandidate++) {
                            int[] otherEntry = CODE_TABLE[otherCandidate];
                            if (otherEntry == null || otherEntry[1] <= codeEntry[1] || otherEntry[1] > bitsInBuffer)
                                continue;
                            if ((buffer >> (bitsInBuffer - otherEntry[1])) == otherEntry[0]) {
                                hasLongerMatch = true;
                                break;
                            }
                        }

                        if (!hasLongerMatch) {
                            matchedSymbol = candidate;
                            break;
                        }
                    }
                }

                if (matchedSymbol == -1) break;
                if (matchedSymbol == 256) throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR);

                output.append((char) matchedSymbol);
                int codeLength = CODE_TABLE[matchedSymbol][1];
                bitsInBuffer -= codeLength;
                buffer &= (1L << bitsInBuffer) - 1;
            }
        }

        if (bitsInBuffer > 7) throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR);
        if (bitsInBuffer > 0 && buffer != (1L << bitsInBuffer) - 1)
            throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR);
        return output.toString();
    }
}
