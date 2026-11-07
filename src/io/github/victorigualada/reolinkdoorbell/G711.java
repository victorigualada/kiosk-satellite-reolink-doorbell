// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

/** ITU-T G.711 encoders for the talk channel. Input is 16-bit little-endian mono PCM. */
final class G711 {
    private G711() {}
    static byte ulaw(int sample) {
        int sign = (sample >> 8) & 0x80;
        if (sign != 0) sample = -sample;
        if (sample > 32635) sample = 32635;
        sample += 0x84;
        int exponent = 7;
        for (int mask = 0x4000; (sample & mask) == 0 && exponent > 0; exponent--, mask >>= 1) {}
        int mantissa = (sample >> (exponent + 3)) & 0x0F;
        return (byte) ~(sign | (exponent << 4) | mantissa);
    }
    static byte alaw(int sample) {
        int sign = (~sample >> 8) & 0x80;
        if (sign == 0) sample = -sample;
        if (sample > 32635) sample = 32635;
        int code;
        if (sample >= 256) {
            int exponent = 32 - Integer.numberOfLeadingZeros((sample >> 8) & 0x7F);
            code = (exponent << 4) | ((sample >> (exponent + 3)) & 0x0F);
        } else code = sample >> 4;
        return (byte) (code ^ (sign ^ 0x55));
    }
    static byte[] encode(byte[] pcm, int length, boolean alaw) {
        byte[] out = new byte[length / 2];
        for (int i = 0; i < out.length; i++) {
            int sample = (short) ((pcm[2 * i] & 255) | (pcm[2 * i + 1] << 8));
            out[i] = alaw ? alaw(sample) : ulaw(sample);
        }
        return out;
    }
}
