// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

/**
 * Spectral echo suppressor, after the linear canceller. The doorbell's copy of your voice is not always a clean,
 * steady echo path: its codecs, processing and a delay that wanders by a few milliseconds leave much of it for the
 * linear filter to miss. This works per frequency band instead: from the aligned reference (what you sent, delayed
 * by the measured echo delay) it learns how strongly each band comes back, and turns down only the bands where the
 * estimated echo dominates. Where the visitor's voice dominates a band it passes untouched, so you still hear them
 * when you both talk.
 *
 * <p>Short-time Fourier transform with square-root Hann windows at 50% overlap; adds one frame of latency (32 ms).
 */
final class EchoSuppressor {
    /** Deepest cut per band: -30 dB, so your voice is gone while the room sound does not gate on and off. */
    static final double FLOOR = 0.03;
    /** How much of the estimated echo to remove: over-subtract, since the estimate is rough. */
    static final double OVER = 3;
    /** Per-frame hold of each band's reference level: covers about 100 ms of wandering delay and reverb. */
    static final double HOLD = 0.8;
    /** Learned coupling never exceeds this: the doorbell does not send your voice back much louder than you spoke. */
    static final double MAX_COUPLING = 4;

    final int size, hop;
    private final double[] window, cos, sin;
    private final double[] input, reference, output; // Last frame of input and reference; overlap-add tail.
    private final double[] re = new double[0x1000], im = new double[0x1000];
    private final double[] echoSpread, coupling, gain;
    private final double[] pending; // Processed samples ready to hand out.
    private int filled, ready, readAt;

    EchoSuppressor(int rate) {
        int n = 64; while (n < rate * 32 / 1000) n <<= 1;
        size = n; hop = n / 2;
        window = new double[n]; cos = new double[n / 2]; sin = new double[n / 2];
        for (int i = 0; i < n; i++) window[i] = Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / n)); // Periodic, so overlap-adds to 1.
        for (int i = 0; i < n / 2; i++) { cos[i] = Math.cos(-2 * Math.PI * i / n); sin[i] = Math.sin(-2 * Math.PI * i / n); }
        input = new double[n]; reference = new double[n]; output = new double[n];
        int bins = n / 2 + 1;
        echoSpread = new double[bins]; coupling = new double[bins]; gain = new double[bins];
        java.util.Arrays.fill(coupling, 0.5); java.util.Arrays.fill(gain, 1);
        pending = new double[n * 4];
    }

    /**
     * Suppresses echo in {@code d} in place. {@code r} is the reference aligned with it: r[j] is what you sent that
     * should come back as d[j]. Output lags input by {@link #size} samples.
     */
    void process(short[] d, double[] r, int n) {
        for (int j = 0; j < n; j++) {
            input[hop + filled] = d[j]; reference[hop + filled] = r[j];
            if (++filled == hop) { frame(); filled = 0; }
            double out = 0;
            if (ready > 0) { out = pending[readAt]; readAt = (readAt + 1) % pending.length; ready--; }
            d[j] = (short) Math.max(-32768, Math.min(32767, Math.round(out)));
        }
    }

    private void frame() {
        int bins = size / 2 + 1;
        double[] refPower = spectrum(reference);
        double[] inRe = new double[bins], inIm = new double[bins];
        spectrumInto(input, inRe, inIm);
        for (int k = 0; k < bins; k++) {
            double y = inRe[k] * inRe[k] + inIm[k] * inIm[k];
            // Echo spreads over the next frames (reverb, codec frames, a wandering delay): hold the reference's power
            // with a short decay rather than trusting one frame's alignment.
            echoSpread[k] = Math.max(refPower[k], echoSpread[k] * HOLD);
            double x = echoSpread[k];
            if (x > 300.0 * size) { // Roughly RMS 25: you are actually talking.
                // Learn how strongly each band comes back against the held level the estimate uses. The held level
                // outlasts the echo and your pauses, so low returns say little: follow the strong ones quickly and
                // let it sink slowly. A return far above what was learned is the visitor talking, not echo.
                double ratio = Math.min(MAX_COUPLING, y / x);
                if (ratio > coupling[k]) { if (ratio < 4 * coupling[k]) coupling[k] += 0.1 * (ratio - coupling[k]); }
                else coupling[k] += 0.01 * (ratio - coupling[k]);
            }
            double echo = coupling[k] * x;
            double target = y <= 0 ? FLOOR : Math.max(FLOOR, 1 - OVER * echo / y);
            // Close fast when echo arrives, open a little slower so the residue does not flutter.
            gain[k] += (target < gain[k] ? 0.7 : 0.3) * (target - gain[k]);
            inRe[k] *= gain[k]; inIm[k] *= gain[k];
        }
        // Inverse transform by conjugate symmetry, windowed overlap-add.
        java.util.Arrays.fill(re, 0, size, 0); java.util.Arrays.fill(im, 0, size, 0);
        for (int k = 0; k < bins; k++) { re[k] = inRe[k]; im[k] = -inIm[k]; }
        for (int k = 1; k < size / 2; k++) { re[size - k] = inRe[k]; im[size - k] = inIm[k]; }
        fft(re, im);
        for (int i = 0; i < size; i++) output[i] += re[i] / size * window[i];
        for (int i = 0; i < hop; i++) {
            pending[(readAt + ready) % pending.length] = output[i]; ready++;
        }
        System.arraycopy(output, hop, output, 0, hop); java.util.Arrays.fill(output, hop, size, 0);
        System.arraycopy(input, hop, input, 0, hop); System.arraycopy(reference, hop, reference, 0, hop);
    }

    private double[] spectrum(double[] signal) {
        int bins = size / 2 + 1; double[] power = new double[bins], r = new double[bins], i = new double[bins];
        spectrumInto(signal, r, i);
        for (int k = 0; k < bins; k++) power[k] = r[k] * r[k] + i[k] * i[k];
        return power;
    }
    private void spectrumInto(double[] signal, double[] outRe, double[] outIm) {
        for (int i = 0; i < size; i++) { re[i] = signal[i] * window[i]; im[i] = 0; }
        fft(re, im);
        for (int k = 0; k <= size / 2; k++) { outRe[k] = re[k]; outIm[k] = im[k]; }
    }

    /** In-place radix-2 forward FFT over the first {@link #size} entries. */
    private void fft(double[] re, double[] im) {
        int n = size;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1; for (; (j & bit) != 0; bit >>= 1) j ^= bit; j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int length = 2; length <= n; length <<= 1) {
            int step = n / length;
            for (int start = 0; start < n; start += length) {
                for (int k = 0; k < length / 2; k++) {
                    double wr = cos[k * step], wi = sin[k * step];
                    int a = start + k, b = a + length / 2;
                    double tr = re[b] * wr - im[b] * wi, ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
                }
            }
        }
    }
}
