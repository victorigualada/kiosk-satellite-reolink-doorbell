// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

/**
 * Tells when the person at the kiosk is speaking, from the microphone's level, so the doorbell's audio can be turned
 * down meanwhile. That keeps the doorbell's own microphone from sending their voice straight back as an echo.
 */
final class VoiceActivity {
    /** About -32 dBFS: above room noise after Android's noise suppression, below normal speech close to a tablet. */
    static final double THRESHOLD = 800;
    private final long hangoverMillis;
    private long lastVoice = Long.MIN_VALUE / 2;
    private boolean speaking;
    VoiceActivity(long hangoverMillis) { this.hangoverMillis = hangoverMillis; }

    /** Root mean square of 16-bit little-endian PCM. */
    static double level(byte[] pcm, int length) {
        int samples = length / 2; if (samples == 0) return 0;
        double sum = 0;
        for (int i = 0; i < samples; i++) { int s = (short) ((pcm[2 * i] & 255) | (pcm[2 * i + 1] << 8)); sum += (double) s * s; }
        return Math.sqrt(sum / samples);
    }
    /** Feeds one chunk; returns whether the speaking state changed. Speech holds for the hangover after the last loud chunk. */
    boolean update(byte[] pcm, int length, long nowMillis) {
        if (level(pcm, length) >= THRESHOLD) lastVoice = nowMillis;
        boolean next = nowMillis - lastVoice <= hangoverMillis;
        if (next == speaking) return false;
        speaking = next; return true;
    }
    boolean speaking() { return speaking; }
}
