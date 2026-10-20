// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

/**
 * Software echo canceller for the doorbell's copy of your own voice.
 *
 * <p>While you talk, the doorbell mixes the audio it receives into the audio it sends, so your voice comes back
 * a few hundred milliseconds later. Android's canceller cannot help: it only removes what the tablet's own microphone
 * hears from its speaker. Here the plugin knows exactly what it sent, so it can subtract it:
 *
 * <ol>
 *   <li>Every sent chunk goes into a reference ring, resampled to the doorbell's audio rate, on a wall-clock timeline.</li>
 *   <li>The bulk delay is found by correlating the loudness envelopes of what was sent and what comes back.</li>
 *   <li>An adaptive filter spanning {@link #SPAN_MS} around that delay models the echo path (gain, the doorbell's
 *       codecs and resampling) and subtracts its estimate from the incoming audio, sample by sample.</li>
 *   <li>Two paths keep the visitor's voice safe when you both talk at once: a background filter always learns (NLMS),
 *       and the foreground filter that does the cancelling only takes its place after it has cancelled clearly
 *       better for a while. A visitor talking over you makes the background worse, so it is never copied then.</li>
 *   <li>A light residual suppressor takes the edge off what the linear filter leaves while only you are talking.</li>
 * </ol>
 *
 * <p>Thread use: {@link #reference} from the talk thread, {@link #process} from the stream thread.
 */
final class EchoCanceller {
    /** Filter span, centred on the estimated delay to absorb its error and jitter: 1024 taps at 16 kHz. */
    static final int SPAN_MS = 64;
    static final int ENVELOPE_MS = 10, HISTORY_MS = 4000, MAX_DELAY_MS = 1500;
    static final double MIN_CORRELATION = 0.5, STEP = 0.4, REGULARIZATION = 1e3;
    /** Below this reference level (RMS, 16-bit) there is nothing to cancel and nothing to learn from. */
    static final double SILENCE = 100;

    final int rate, taps, block;
    // Reference ring: resampled sent audio, with the wall time (µs) of ring index 0's sample tracked as refStart.
    private final float[] ring;
    private long written; // Samples ever written; ring index = sample % ring.length.
    private double refStartMicros = Double.NaN;
    private final double[] sentEnvelope, receivedEnvelope;
    private final long[] sentBins, receivedBins; // Which bin each slot holds, so stale slots read as silence.
    private long envelopeOrigin = Long.MIN_VALUE; // Wall ms of envelope bin 0 in the circular envelopes.
    // Incoming timeline: wall time of incoming sample 0, min-filtered against decode jitter.
    private double inStartMicros = Double.NaN;
    private long received;
    private final double[] foreground, background;
    private long delayMicros = -1;
    private long lastEstimateMillis = Long.MIN_VALUE / 2;
    private long alignment = Long.MIN_VALUE; // Reference index minus incoming index the weights were learned at.
    // Per-block energies of the input and of both paths' leftovers, and how many blocks in a row each test held.
    private double blockInput, blockForeground, blockBackground;
    private int blockSamples, blockActive, better, worse, diverged;
    private boolean suppress;
    private volatile double cancelling; // Smoothed echo return loss enhancement of the foreground, in power ratio.

    EchoCanceller(int rate) {
        this.rate = rate; taps = rate * SPAN_MS / 1000; block = rate / 50;
        foreground = new double[taps]; background = new double[taps];
        ring = new float[rate * (HISTORY_MS / 1000 + 1)];
        sentEnvelope = new double[HISTORY_MS / ENVELOPE_MS];
        receivedEnvelope = new double[HISTORY_MS / ENVELOPE_MS];
        sentBins = new long[sentEnvelope.length]; receivedBins = new long[sentEnvelope.length];
        java.util.Arrays.fill(sentBins, -1); java.util.Arrays.fill(receivedBins, -1);
    }

    /** Audio as it was sent to the doorbell: {@code n} samples at {@code sourceRate}, the last captured at {@code endMicros}. */
    synchronized void reference(short[] pcm, int n, int sourceRate, long endMicros) {
        if (n <= 0) return;
        double startMicros = endMicros - n * 1e6 / sourceRate;
        // The first sample of this chunk, at our rate, belongs at this ring position on the wall-clock line.
        if (Double.isNaN(refStartMicros)) refStartMicros = startMicros;
        long position = Math.round((startMicros - refStartMicros) * rate / 1e6);
        if (position < written - rate / 10 || position > written + rate / 10) {
            // Clock jump or a long gap: restart the timeline here rather than smear.
            refStartMicros = startMicros - written * 1e6 / rate; position = written;
        }
        while (written < position) ring[(int) (written++ % ring.length)] = 0;
        int out = (int) Math.round(n * (double) rate / sourceRate);
        double sum = 0;
        for (int i = 0; i < out; i++) {
            double at = i * (double) sourceRate / rate; int a = (int) at; double f = at - a;
            double v = a + 1 < n ? pcm[a] * (1 - f) + pcm[a + 1] * f : pcm[Math.min(a, n - 1)];
            ring[(int) (written++ % ring.length)] = (float) v; sum += v * v;
        }
        envelope(sentEnvelope, sentBins, startMicros / 1000, endMicros / 1000.0, Math.sqrt(sum / Math.max(1, out)));
    }

    /** Removes the echo from {@code n} incoming samples in place; the first was decoded at {@code startMicros}. */
    void process(short[] d, int n, long startMicros) {
        if (n <= 0) return;
        float[] x; long firstRef; long delay;
        synchronized (this) {
            // Incoming timeline: the earliest (least delayed) mapping seen is the true one.
            double candidate = startMicros - received * 1e6 / rate;
            if (Double.isNaN(inStartMicros) || candidate < inStartMicros || candidate - inStartMicros > 500_000) inStartMicros = candidate;
            // A doorbell clock slightly slower than ours makes the true mapping creep later; follow it at clock-drift pace.
            else inStartMicros += Math.min(candidate - inStartMicros, n * 1e6 / rate * 2e-4);
            double wall = inStartMicros + received * 1e6 / rate;
            double sum = 0; for (int i = 0; i < n; i++) sum += (double) d[i] * d[i];
            envelope(receivedEnvelope, receivedBins, wall / 1000, wall / 1000 + n * 1000.0 / rate, Math.sqrt(sum / n));
            received += n;
            long nowMillis = (long) (wall / 1000);
            if (nowMillis - lastEstimateMillis >= 500) { lastEstimateMillis = nowMillis; estimateDelay(nowMillis); }
            delay = delayMicros;
            if (delay < 0 || Double.isNaN(refStartMicros)) return;
            // Reference sample lining up with incoming sample 0, then the filter window around it. When the timelines
            // move by a few samples, the learned echo path moves with them instead of being learned again.
            long offset = Math.round((inStartMicros - delay - refStartMicros) * rate / 1e6);
            if (alignment != Long.MIN_VALUE && offset != alignment) shift((int) Math.max(-taps, Math.min(taps, offset - alignment)));
            alignment = offset;
            long centre = received - n + offset;
            firstRef = centre + taps / 2 - (taps - 1);
            x = new float[n + taps - 1];
            for (int i = 0; i < x.length; i++) {
                long k = firstRef + i;
                x[i] = k >= 0 && k < written && k >= written - ring.length ? ring[(int) (k % ring.length)] : 0;
            }
        }
        filter(d, n, x);
    }

    /** Both filters over the aligned reference window {@code x}: sample j uses x[j .. j+taps-1], newest last. */
    private void filter(short[] d, int n, float[] x) {
        double power = 0; for (int i = 0; i < taps - 1; i++) power += x[i] * x[i];
        for (int j = 0; j < n; j++) {
            float newest = x[j + taps - 1]; power += newest * newest;
            double yf = 0, yb = 0;
            for (int i = 0, k = j + taps - 1; i < taps; i++, k--) { yf += foreground[i] * x[k]; yb += background[i] * x[k]; }
            double input = d[j], ef = input - yf, eb = input - yb;
            boolean active = power > SILENCE * SILENCE * taps;
            if (active) {
                double mu = STEP * eb / (power + REGULARIZATION);
                for (int i = 0, k = j + taps - 1; i < taps; i++, k--) background[i] += mu * x[k];
                blockActive++;
            }
            blockInput += input * input; blockForeground += ef * ef; blockBackground += eb * eb;
            if (++blockSamples == block) compare();
            double out = suppress ? ef * 0.5 : ef;
            d[j] = (short) Math.max(-32768, Math.min(32767, Math.round(out)));
            float oldest = x[j]; power -= oldest * oldest; if (power < 0) power = 0;
        }
    }

    /** Once per 20 ms block: decides which filter to trust and whether to suppress the leftover. */
    private void compare() {
        double in = blockInput, fg = blockForeground, bg = blockBackground;
        boolean active = blockActive > block / 2 && in > SILENCE * SILENCE * block;
        blockInput = blockForeground = blockBackground = 0; blockSamples = blockActive = 0;
        if (!active) { better = worse = diverged = 0; suppress = false; return; }
        // The background cancelled clearly better for 60 ms: it learned something real, adopt it.
        better = bg < 0.4 * fg && bg < in ? better + 1 : 0;
        if (better >= 3) { System.arraycopy(background, 0, foreground, 0, taps); better = 0; fg = bg; }
        // The background got much worse than the foreground: it learned the visitor's voice. Start it over from the
        // foreground rather than wait for it to unlearn.
        worse = bg > 4 * fg && fg < in ? worse + 1 : 0;
        if (worse >= 3) { System.arraycopy(foreground, 0, background, 0, taps); worse = 0; }
        // A foreground that makes things louder is worse than none.
        diverged = fg > 2 * in ? diverged + 1 : 0;
        if (diverged >= 5) { java.util.Arrays.fill(foreground, 0); diverged = 0; fg = in; }
        double ratio = in / Math.max(fg, 1);
        cancelling = 0.9 * cancelling + 0.1 * ratio;
        // Only you talking and the filter cancelling 9 dB or more: what is left is echo too, take the edge off it.
        // With the visitor talking the ratio falls, and their voice passes untouched.
        suppress = ratio > 8;
    }

    /** Records a chunk's loudness in every envelope bin it spans, from {@code startMillis} to {@code endMillis}. */
    private void envelope(double[] env, long[] stamps, double startMillis, double endMillis, double level) {
        if (envelopeOrigin == Long.MIN_VALUE) envelopeOrigin = (long) startMillis - HISTORY_MS;
        long first = (long) Math.floor((startMillis - envelopeOrigin) / ENVELOPE_MS);
        long last = Math.max(first, (long) Math.ceil((endMillis - envelopeOrigin) / ENVELOPE_MS) - 1);
        for (long bin = Math.max(0, first); bin <= last; bin++) {
            int slot = (int) (bin % env.length);
            if (stamps[slot] != bin) { stamps[slot] = bin; env[slot] = level; } else env[slot] = Math.max(env[slot], level);
        }
    }

    /** Finds the lag where the received loudness best follows the sent loudness. Must hold the lock. */
    private void estimateDelay(long nowMillis) {
        int bins = sentEnvelope.length, maxLag = MAX_DELAY_MS / ENVELOPE_MS;
        long last = Math.floorDiv(nowMillis - envelopeOrigin, ENVELOPE_MS);
        if (last < bins) return;
        double[] out = new double[bins], in = new double[bins];
        for (int i = 0; i < bins; i++) {
            long bin = last - bins + 1 + i; int at = (int) (bin % bins);
            out[i] = sentBins[at] == bin ? sentEnvelope[at] : 0; in[i] = receivedBins[at] == bin ? receivedEnvelope[at] : 0;
        }
        // Half a second of you talking is enough loudness pattern to match without false hits.
        int active = 0; for (int i = 0; i < bins - maxLag; i++) if (out[i] > 4 * SILENCE) active++;
        if (active < 500 / ENVELOPE_MS) return;
        double best = MIN_CORRELATION; int lag = -1;
        int n = bins - maxLag;
        for (int l = 0; l <= maxLag; l++) {
            double r = correlation(out, 0, in, l, n);
            if (r > best) { best = r; lag = l; }
        }
        if (lag < 0) return;
        long next = (long) lag * ENVELOPE_MS * 1000;
        // Small moves are envelope jitter the span absorbs. Moderate ones keep the learned path, shifted along with the
        // window by process(); a move beyond half the span is a different path, learned from scratch.
        long span = (long) (taps * 1e6 / rate);
        if (delayMicros >= 0 && Math.abs(next - delayMicros) <= span / 4) return;
        if (delayMicros < 0 || Math.abs(next - delayMicros) >= span / 2) reset();
        delayMicros = next;
    }
    /** The window moved {@code k} samples later in the reference: each learned tap moves {@code k} places along. */
    private void shift(int k) { shift(foreground, k); shift(background, k); }
    private void shift(double[] w, int k) {
        if (k > 0) { System.arraycopy(w, 0, w, k, taps - k); java.util.Arrays.fill(w, 0, k, 0); }
        else if (k < 0) { System.arraycopy(w, -k, w, 0, taps + k); java.util.Arrays.fill(w, taps + k, taps, 0); }
    }
    private void reset() {
        java.util.Arrays.fill(foreground, 0); java.util.Arrays.fill(background, 0); better = worse = diverged = 0; suppress = false;
    }
    static double correlation(double[] a, int ai, double[] b, int bi, int n) {
        double ma = 0, mb = 0; for (int i = 0; i < n; i++) { ma += a[ai + i]; mb += b[bi + i]; } ma /= n; mb /= n;
        double ab = 0, aa = 0, bb = 0;
        for (int i = 0; i < n; i++) { double p = a[ai + i] - ma, q = b[bi + i] - mb; ab += p * q; aa += p * p; bb += q * q; }
        return aa == 0 || bb == 0 ? 0 : ab / Math.sqrt(aa * bb);
    }
    synchronized long delayMillis() { return delayMicros < 0 ? -1 : delayMicros / 1000; }
    /** How much the foreground filter is cancelling lately, in dB; 0 before it has learned anything. */
    double cancellingDb() { return 10 * Math.log10(Math.max(1, cancelling)); }
}
