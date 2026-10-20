// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Measures how soon the doorbell starts sending video over RTSP and over its HTTP-FLV stream, from a cold request,
 * to choose the faster one. Nothing is decoded or shown.
 */
final class StartupProbe implements AutoCloseable {
    static final long LIMIT_MS = 10_000;
    /** Milliseconds from the request to connected, first video and first keyframe; -1 when not reached. */
    static final class Result {
        long connected = -1, video = -1, key = -1; String error = "";
        String describe(String name) {
            if (!error.isEmpty()) return name + ": " + error;
            return name + ": connected " + ms(connected) + ", first video " + ms(video) + ", keyframe " + ms(key);
        }
        private static String ms(long v) { return v < 0 ? "not within " + LIMIT_MS / 1000 + " s" : String.format(Locale.ROOT, "%.1f s", v / 1000.0); }
    }
    private final Settings settings;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile AutoCloseable active;
    StartupProbe(Settings settings) { settings.requireConnection(); this.settings = settings; }

    boolean cancelled() { return closed.get(); }
    Result rtsp() {
        Result r = new Result(); long start = System.nanoTime();
        if (closed.get()) { r.error = "cancelled"; return r; }
        RtspClient client = new RtspClient(settings); active = client;
        try {
            URI url = settings.streamUrl();
            client.connect(url);
            Protocol.Message description = client.request("DESCRIBE", url, "Accept: application/sdp\r\n");
            URI base = url;
            if (description.headers.containsKey("content-base")) base = Sdp.control(url, description.headers.get("content-base"));
            Sdp.Track video = null;
            for (Sdp.Track t : Sdp.parse(new String(description.body, "UTF-8"))) if ("video".equals(t.kind)) { video = t; break; }
            if (video == null) throw new IOException("No video track");
            boolean hevc = "H265".equals(video.codec);
            client.session(client.request("SETUP", Sdp.control(base, video.control), "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n"));
            client.request("PLAY", base, "Range: npt=0.000-\r\n");
            r.connected = since(start);
            Protocol.H264 h264 = new Protocol.H264(); Protocol.H265 h265 = new Protocol.H265();
            while (!closed.get() && since(start) < LIMIT_MS) {
                Protocol.Message m = client.read();
                if (m.channel != 0) continue;
                if (r.video < 0) r.video = since(start);
                Protocol.Rtp p = new Protocol.Rtp(m.body);
                byte[] frame = hevc ? h265.accept(p) : h264.accept(p);
                if (frame != null && VideoParameters.keyframe(frame, hevc)) { r.key = since(start); break; }
            }
        } catch (Exception e) { r.error = Failures.describe(e, "RTSP failed"); }
        finally { client.close(); }
        return r;
    }

    Result flv() {
        Result r = new Result(); long start = System.nanoTime();
        if (closed.get()) { r.error = "cancelled"; return r; }
        ReolinkApi api = new ReolinkApi(settings); active = api;
        HttpURLConnection c = null;
        try {
            String token = api.token();
            String stream = "channel" + settings.channel + "_" + settings.stream + ".bcs";
            URI uri = settings.flvUrl("port=1935&app=bcs&stream=" + stream + "&token=" + token);
            c = (HttpURLConnection) uri.toURL().openConnection();
            HttpURLConnection connection = c; active = () -> { connection.disconnect(); api.close(); };
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(8000); c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) throw new IOException("Doorbell has no HTTP-FLV stream (HTTP " + c.getResponseCode() + ")");
            r.connected = since(start);
            DataInputStream in = new DataInputStream(new BufferedInputStream(c.getInputStream()));
            byte[] header = new byte[13]; in.readFully(header);
            if (header[0] != 'F' || header[1] != 'L' || header[2] != 'V') throw new IOException("Doorbell did not answer with FLV");
            while (!closed.get() && since(start) < LIMIT_MS) {
                int type = in.readUnsignedByte(), size = in.readUnsignedByte() << 16 | in.readUnsignedShort();
                in.skipBytes(7);
                if (size > Protocol.MAX_FRAME) throw new IOException("FLV tag exceeds safety limit");
                byte[] data = new byte[size]; in.readFully(data); in.skipBytes(4);
                if ((type & 0x1f) != 9 || size < 2) continue;
                if (r.video < 0) r.video = since(start);
                if (keyframe(data)) { r.key = since(start); break; }
            }
        } catch (Exception e) { r.error = Failures.describe(e, "HTTP-FLV failed"); }
        finally {
            if (c != null) c.disconnect();
            api.logout(); api.close();
        }
        return r;
    }
    /** A video tag holding a keyframe picture: frame type 1, and for AVC/HEVC a coded-frames packet, not the sequence header. */
    static boolean keyframe(byte[] data) {
        if (data.length < 2 || (data[0] & 0xf0) >> 4 != 1) return false;
        int codec = data[0] & 0x0f;
        return (codec != 7 && codec != 12) || data[1] == 1;
    }
    private static long since(long start) { return (System.nanoTime() - start) / 1_000_000; }
    public void close() {
        closed.set(true);
        AutoCloseable a = active; if (a != null) try { a.close(); } catch (Exception ignored) {}
    }
}
