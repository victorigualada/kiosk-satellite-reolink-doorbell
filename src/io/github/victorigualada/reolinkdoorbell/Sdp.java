// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

import java.io.IOException;
import java.net.URI;
import java.util.*;

final class Sdp {
    static String videoMime(Track track) throws IOException {
        if ("H264".equals(track.codec)) return "video/avc";
        if (!"H265".equals(track.codec)) throw new IOException("Camera video codec is not supported; expected H264 or H265");
        String don = track.fmtp.getOrDefault("sprop-max-don-diff", "0");
        try {
            if (Integer.parseInt(don) != 0) throw new NumberFormatException();
        } catch (NumberFormatException e) { throw new IOException("H265 interleaved/DON mode is not supported; requires sprop-max-don-diff=0"); }
        return "video/hevc";
    }
    static final class Track {
        String kind, codec = "", control = "", direction = "sendrecv";
        int payload, rate, channels = 1;
        final Map<String, String> fmtp = new HashMap<>();
    }
    static List<Track> parse(String text) throws IOException {
        List<Track> tracks = new ArrayList<>(); Track t = null; String direction = "sendrecv";
        try {
            for (String raw : text.split("\r?\n")) {
                String l = raw.trim();
                if (l.equals("a=sendonly") || l.equals("a=recvonly") || l.equals("a=sendrecv") || l.equals("a=inactive")) {
                    if (t == null) direction = l.substring(2); else t.direction = l.substring(2);
                } else if (l.startsWith("m=")) {
                    String[] p = l.substring(2).split(" +");
                    if (p.length < 4 || !"RTP/AVP".equals(p[2])) throw new IOException("Unsupported secure/multipayload SDP transport");
                    t = new Track(); t.kind = p[0]; t.payload = Integer.parseInt(p[3]); t.direction = direction; tracks.add(t);
                    // Static G.711 payload types may come without an rtpmap line.
                    if (t.payload == 0 || t.payload == 8) { t.codec = t.payload == 0 ? "PCMU" : "PCMA"; t.rate = 8000; }
                } else if (t != null && l.startsWith("a=control:")) t.control = l.substring(10);
                else if (t != null && l.startsWith("a=rtpmap:" + t.payload + " ")) {
                    String[] p = l.substring(l.indexOf(' ') + 1).split("/");
                    t.codec = p[0].toUpperCase(Locale.ROOT); t.rate = Integer.parseInt(p[1]);
                    if (p.length > 2) t.channels = Integer.parseInt(p[2]);
                } else if (t != null && l.startsWith("a=fmtp:" + t.payload + " ")) {
                    for (String pair : l.substring(l.indexOf(' ') + 1).split(";")) {
                        int at = pair.indexOf('='); if (at > 0) t.fmtp.put(pair.substring(0, at).trim().toLowerCase(Locale.ROOT), pair.substring(at + 1).trim());
                    }
                }
            }
        } catch (RuntimeException e) { throw new IOException("Malformed stream SDP"); }
        for (Track track : tracks) if (track.rate <= 0 || track.payload < 0 || track.payload > 127 || track.control.isEmpty())
            throw new IOException("Incomplete stream track description");
        return tracks;
    }
    /** Codecs the talk channel can encode, best first. */
    static final List<String> TALK_CODECS = Arrays.asList("PCMU", "PCMA", "MPEG4-GENERIC");
    /** The ONVIF backchannel: an audio track the client sends on, marked a=sendonly by the doorbell. Null when there is none. */
    static Track backchannel(List<Track> tracks) {
        Track best = null;
        for (Track t : tracks) {
            if (!"audio".equals(t.kind) || !"sendonly".equals(t.direction) || !TALK_CODECS.contains(t.codec)) continue;
            if (best == null || TALK_CODECS.indexOf(t.codec) < TALK_CODECS.indexOf(best.codec)) best = t;
        }
        return best;
    }
    static boolean hasBackchannel(List<Track> tracks) {
        for (Track t : tracks) if ("audio".equals(t.kind) && "sendonly".equals(t.direction)) return true;
        return false;
    }
    /**
     * Resolves a track's control URL. It is only ever the request target on the RTSP connection already open to the
     * configured doorbell, never a new connection, so a different host or a missing port in it is harmless.
     * Reolink firmware announces its own address, often without the port.
     */
    static URI control(URI base, String value) throws IOException {
        try {
            String v = value.trim().replace(" ", "%20");
            if (v.isEmpty() || v.equals("*")) return base;
            URI u = v.regionMatches(true, 0, "rtsp:", 0, 5) || v.regionMatches(true, 0, "rtsps:", 0, 6) ? URI.create(v)
                : URI.create(base.toString().endsWith("/") ? base.toString() : base.toString() + "/").resolve(v);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!("rtsp".equals(scheme) || "rtsps".equals(scheme)) || u.getRawUserInfo() != null || u.getRawFragment() != null)
                throw new IllegalArgumentException();
            return u;
        } catch (RuntimeException e) { throw new IOException("Unsupported RTSP control URL from the doorbell"); }
    }
    static byte[] hex(String s) throws IOException {
        if (s == null || s.length() == 0 || s.length() % 2 != 0 || s.length() > 4096) throw new IOException("Missing or invalid codec configuration");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IOException("Invalid codec configuration"); b[i] = (byte) (hi * 16 + lo);
        } return b;
    }
}
