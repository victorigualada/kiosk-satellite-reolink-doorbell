// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

import java.io.*;
import java.util.List;

/**
 * Rewrites an H.264 SPS to promise no frame reordering (VUI bitstream_restriction with max_num_reorder_frames 0 and
 * max_dec_frame_buffering equal to the reference frames). Without it, many Android decoders hold back a full
 * picture buffer before showing the first image, which costs about a second on a live doorbell. Moonlight does the same.
 */
final class SpsPatch {
    private SpsPatch() {}
    int reorder = -1, buffering = -1;

    /** The patched SPS NAL, header byte included. Throws on anything it cannot walk; callers keep the original then. */
    static byte[] lowDelay(byte[] nal) throws IOException { return new SpsPatch().walk(nal, true); }
    /** {reorder, buffering} from the SPS's bitstream restriction, or null when it has none. For tests and diagnostics. */
    static int[] restriction(byte[] nal) throws IOException {
        SpsPatch p = new SpsPatch(); p.walk(nal, false);
        return p.reorder < 0 ? null : new int[] {p.reorder, p.buffering};
    }
    /** Replaces each SPS in an Annex B access unit with its patched form; returns the input when nothing changes. */
    static byte[] accessUnit(byte[] data) throws IOException {
        List<byte[]> units = VideoParameters.annexB(data);
        boolean changed = false;
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 16);
        for (byte[] nal : units) {
            byte[] next = nal;
            if (nal.length > 0 && (nal[0] & 31) == 7) {
                try { next = lowDelay(nal); changed |= !java.util.Arrays.equals(next, nal); }
                catch (IOException ignored) { /* Leave an SPS we cannot walk as it was. */ }
            }
            out.write(0); out.write(0); out.write(0); out.write(1); out.write(next, 0, next.length);
        }
        return changed ? out.toByteArray() : data;
    }

    private byte[] walk(byte[] nal, boolean patch) throws IOException {
        if (nal.length < 4 || nal.length > 16384 || (nal[0] & 31) != 7) throw new IOException("Invalid video SPS");
        Reader r = new Reader(unescape(nal)); Writer w = new Writer();
        int profile = r.copy(8, w); r.copy(16, w); r.copyUe(w);
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 || profile == 83 || profile == 86
                || profile == 118 || profile == 128 || profile == 138 || profile == 139 || profile == 134 || profile == 135) {
            int chroma = r.copyUe(w); if (chroma == 3) r.copy(1, w);
            r.copyUe(w); r.copyUe(w); r.copy(1, w);
            if (r.copy(1, w) == 1) for (int i = 0; i < (chroma == 3 ? 12 : 8); i++) if (r.copy(1, w) == 1) {
                int last = 8, next = 8;
                for (int j = 0; j < (i < 6 ? 16 : 64); j++) { if (next != 0) next = (last + r.copySe(w) + 256) & 255; last = next == 0 ? last : next; }
            }
        }
        r.copyUe(w);
        int order = r.copyUe(w);
        if (order == 0) r.copyUe(w);
        else if (order == 1) { r.copy(1, w); r.copySe(w); r.copySe(w); int n = r.copyUe(w); if (n > 255) throw new IOException("Invalid AVC picture order cycle"); for (int i = 0; i < n; i++) r.copySe(w); }
        else if (order != 2) throw new IOException("Invalid AVC picture order");
        int refs = r.copyUe(w); if (refs > 16) throw new IOException("Invalid AVC reference count");
        r.copy(1, w); r.copyUe(w); r.copyUe(w);
        if (r.copy(1, w) == 0) r.copy(1, w);
        r.copy(1, w);
        if (r.copy(1, w) == 1) { r.copyUe(w); r.copyUe(w); r.copyUe(w); r.copyUe(w); }
        int buffering = Math.max(1, refs);
        if (r.copy(1, w) == 0) {
            if (!patch) return null;
            // Back-patch vui_parameters_present_flag: the copy wrote 0.
            w.flipLast();
            for (int i = 0; i < 8; i++) w.bits(0, 1); // aspect, overscan, signal, chroma loc, timing, NAL HRD, VCL HRD, pic struct.
            w.bits(1, 1); restriction(w, 1, 2, 1, 16, 16, buffering);
        } else {
            if (r.copy(1, w) == 1 && r.copy(8, w) == 255) r.copy(32, w);
            if (r.copy(1, w) == 1) r.copy(1, w);
            if (r.copy(1, w) == 1) { r.copy(4, w); if (r.copy(1, w) == 1) r.copy(24, w); }
            if (r.copy(1, w) == 1) { r.copyUe(w); r.copyUe(w); }
            if (r.copy(1, w) == 1) { r.copy(32, w); r.copy(32, w); r.copy(1, w); }
            int nalHrd = r.copy(1, w); if (nalHrd == 1) hrd(r, w);
            int vclHrd = r.copy(1, w); if (vclHrd == 1) hrd(r, w);
            if (nalHrd == 1 || vclHrd == 1) r.copy(1, w);
            r.copy(1, w);
            if (r.read(1) == 1) {
                int mv = r.read(1), bytes = r.ue(), bits = r.ue(), horizontal = r.ue(), vertical = r.ue();
                reorder = r.ue(); this.buffering = r.ue();
                if (!patch) return null;
                w.bits(1, 1); restriction(w, mv, bytes, bits, horizontal, vertical, Math.max(buffering, Math.min(this.buffering, 16)));
            } else {
                if (!patch) return null;
                w.bits(1, 1); restriction(w, 1, 2, 1, 16, 16, buffering);
            }
        }
        w.bits(1, 1); w.align(); // rbsp_trailing_bits
        return escape(nal[0], w.bytes());
    }
    private static void restriction(Writer w, int mv, int bytes, int bits, int horizontal, int vertical, int buffering) {
        w.bits(mv, 1); w.ue(bytes); w.ue(bits); w.ue(horizontal); w.ue(vertical);
        w.ue(0); // max_num_reorder_frames
        w.ue(buffering);
    }
    private static void hrd(Reader r, Writer w) throws IOException {
        int count = r.copyUe(w) + 1; if (count > 32) throw new IOException("Invalid AVC HRD");
        r.copy(4, w); r.copy(4, w);
        for (int i = 0; i < count; i++) { r.copyUe(w); r.copyUe(w); r.copy(1, w); }
        r.copy(5, w); r.copy(5, w); r.copy(5, w); r.copy(5, w);
    }
    private static byte[] unescape(byte[] nal) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int zeros = 0;
        for (int i = 1; i < nal.length; i++) {
            int value = nal[i] & 255;
            if (zeros >= 2 && value == 3) { zeros = 0; continue; }
            out.write(value); zeros = value == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }
    private static byte[] escape(byte header, byte[] rbsp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(rbsp.length + 8); out.write(header); int zeros = 0;
        for (byte b : rbsp) {
            int value = b & 255;
            if (zeros >= 2 && value <= 3) { out.write(3); zeros = 0; }
            out.write(value); zeros = value == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }
    private static final class Reader {
        private final byte[] data; private int at;
        Reader(byte[] data) { this.data = data; }
        int read(int count) throws IOException {
            if (at + count > data.length * 8) throw new IOException("Truncated video SPS");
            int value = 0; for (int i = 0; i < count; i++, at++) value = value << 1 | (data[at / 8] >> (7 - at % 8) & 1); return value;
        }
        int ue() throws IOException { int zeros = 0; while (read(1) == 0) if (++zeros > 20) throw new IOException("Video SPS value exceeds safety limit"); return (1 << zeros) - 1 + read(zeros); }
        /** Reads and writes the same bits. Counts above 31 are copied in halves. */
        int copy(int count, Writer w) throws IOException {
            if (count > 16) { int high = copy(count - 16, w); return high << 16 | copy(16, w); }
            int v = read(count); w.bits(v, count); return v;
        }
        int copyUe(Writer w) throws IOException { int v = ue(); w.ue(v); return v; }
        int copySe(Writer w) throws IOException { int n = ue(); w.ue(n); return (n & 1) != 0 ? (n + 1) / 2 : -n / 2; }
    }
    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(); private int current, filled;
        void bits(int value, int count) { for (int i = count - 1; i >= 0; i--) bit(value >> i & 1); }
        private void bit(int b) { current = current << 1 | b; if (++filled == 8) { out.write(current); current = 0; filled = 0; } }
        void ue(int value) { int v = value + 1, length = 32 - Integer.numberOfLeadingZeros(v); bits(0, length - 1); bits(v, length); }
        void align() { while (filled != 0) bit(0); }
        /** Sets the last written bit to 1. */
        void flipLast() {
            if (filled > 0) { current |= 1; return; }
            byte[] b = out.toByteArray(); b[b.length - 1] |= 1; out.reset(); out.write(b, 0, b.length);
        }
        byte[] bytes() { return out.toByteArray(); }
    }
}
