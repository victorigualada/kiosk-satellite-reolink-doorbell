// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** JVM tests for the parts that do not need an Android device. */
public final class CoreTest {
    private static int passed;
    static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError("FAIL: " + name);
        passed++;
    }
    static void fails(Runnable action, String expected, String name) {
        try { action.run(); } catch (IllegalArgumentException e) { check(e.getMessage().startsWith(expected), name + " (" + e.getMessage() + ")"); return; }
        throw new AssertionError("FAIL: " + name + " did not throw");
    }

    public static void main(String[] args) throws Exception {
        settings(); digest(); headers(); sdp(); h264(); visitor(); api(); talk(); rtsp(); sps(); echo();
        System.out.println("PASS: " + passed + " checks");
    }

    static Map<String, Object> base() {
        Map<String, Object> m = new HashMap<>();
        m.put("host", "192.168.1.50"); m.put("username", "kiosk"); m.put("password", "s3cret");
        return m;
    }
    static void settings() {
        Settings s = new Settings(base());
        check(s.streamUrl().toString().equals("rtsp://192.168.1.50:554/Preview_01_sub"), "Default stream is the sub stream on channel 1");
        Map<String, Object> m = base(); m.put("stream", "main"); m.put("channel", 2); m.put("rtspPort", "8554");
        check(new Settings(m).streamUrl().toString().equals("rtsp://192.168.1.50:8554/Preview_03_main"), "Channel and port shape the RTSP URL");
        check(s.apiUrl("cmd=Login").toString().equals("http://192.168.1.50:80/cgi-bin/api.cgi?cmd=Login"), "HTTP API URL");
        m = base(); m.put("host", "fe80::1"); check(new Settings(m).streamUrl().getHost().equals("[fe80::1]"), "IPv6 hosts are bracketed");
        m = base(); m.put("host", "http://192.168.1.50"); final Map<String, Object> bad = m;
        fails(() -> new Settings(bad), "Doorbell address", "Scheme in address is rejected");
        Map<String, Object> entity = base(); entity.put("visitorEntity", "sensor.doorbell");
        fails(() -> new Settings(entity), "Choose the doorbell", "Visitor entity must be a binary_sensor");
        Map<String, Object> lock = base(); lock.put("lockEntity", "switch.door");
        fails(() -> new Settings(lock), "Choose a lock", "Lock entity must be a lock");
        Map<String, Object> range = base(); range.put("channel", 16);
        fails(() -> new Settings(range), "Out-of-range", "Channel is bounded");
        Map<String, Object> port = base(); port.put("rtspPort", "70000");
        fails(() -> new Settings(port), "Out-of-range", "Port text is bounded");
        Map<String, Object> blank = base(); blank.put("httpPort", " ");
        check(new Settings(blank).httpPort == 80, "Blank port falls back to the default");
        fails(() -> new Settings(Collections.emptyMap()).streamUrl(), "Set the doorbell", "Unconfigured settings cannot build a URL");
        check(new Settings(Collections.emptyMap()).autoOpen && new Settings(Collections.emptyMap()).wakeScreen, "Ring opens and wakes by default");
        Settings defaults = new Settings(Collections.emptyMap());
        check(!defaults.diagnostics && defaults.talkEnabled && defaults.talkSeconds == 120 && defaults.controls.equals("Bottom"), "Talk on, 2 minute limit, call bar at the bottom by default");
        Map<String, Object> limit = base(); limit.put("talkSeconds", 5);
        fails(() -> new Settings(limit), "Out-of-range", "Talk limit is bounded");
    }
    static void digest() throws Exception {
        // RFC 2617 section 3.5 example.
        RtspAuth rfc = new RtspAuth("Mufasa", "Circle Of Life", "0a4f113b");
        rfc.challenge("Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"");
        String h = rfc.header("GET", "/dir/index.html");
        check(h.contains("response=\"6629fae49393a05397450978507c4ef1\""), "RFC 2617 digest response");
        check(h.contains("nc=00000001") && h.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"") && h.endsWith("\r\n"), "Digest carries nc and opaque");
        check(rfc.header("GET", "/dir/index.html").contains("nc=00000002"), "Nonce count increments");
        RtspAuth plain = new RtspAuth("admin", "pw", "x");
        check(plain.header("DESCRIBE", "rtsp://h/").isEmpty() && !plain.ready(), "No header before a challenge");
        plain.challenge("Basic realm=\"x\"\nDigest realm=\"BC Streaming Media\", nonce=\"abc\"");
        String expected = RtspAuth.md5(RtspAuth.md5("admin:BC Streaming Media:pw") + ":abc:" + RtspAuth.md5("DESCRIBE:rtsp://h/"));
        check(plain.header("DESCRIBE", "rtsp://h/").contains("response=\"" + expected + "\"") && !plain.header("DESCRIBE", "rtsp://h/").contains("qop"), "Digest wins over Basic, no-qop form");
        RtspAuth basic = new RtspAuth("admin", "pw", "x"); basic.challenge("Basic realm=\"cam\"");
        check(basic.header("OPTIONS", "rtsp://h/").equals("Authorization: Basic YWRtaW46cHc=\r\n"), "Basic fallback");
        try { new RtspAuth("a", "b").challenge("Bearer x"); throw new AssertionError("unsupported scheme accepted"); }
        catch (java.io.IOException e) { check(e.getMessage().contains("no supported"), "Unsupported challenge is refused"); }
        check(RtspAuth.params("realm=\"a, b\", nonce=n1, qop=\"auth\"").get("realm").equals("a, b"), "Quoted commas stay inside values");
    }
    static void headers() throws Exception {
        String reply = "RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\nWWW-Authenticate: Digest realm=\"r\", nonce=\"n\"\r\nWWW-Authenticate: Basic realm=\"r\"\r\n\r\n";
        Protocol.Message m = Protocol.read(new ByteArrayInputStream(reply.getBytes(StandardCharsets.UTF_8)));
        check(m.status == 401 && m.headers.get("www-authenticate").split("\n").length == 2, "Repeated challenges are all kept");
        check(m.headers.get("cseq").equals("1"), "Other headers still parse");
    }
    static void sdp() throws Exception {
        String text = "v=0\r\no=- 1 1 IN IP4 192.168.1.50\r\ns=Session streamed by \"preview\"\r\nt=0 0\r\na=control:*\r\n"
            + "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=Z2QAM6wVFKAoAPGQ,aO48sA==\r\na=control:trackID=1\r\n"
            + "m=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\na=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1408\r\na=control:trackID=2\r\n";
        List<Sdp.Track> tracks = Sdp.parse(text);
        check(tracks.size() == 2 && tracks.get(0).codec.equals("H264") && Sdp.videoMime(tracks.get(0)).equals("video/avc"), "Reolink H264 video track");
        check(tracks.get(1).codec.equals("MPEG4-GENERIC") && tracks.get(1).rate == 16000 && "13".equals(tracks.get(1).fmtp.get("sizelength")), "Reolink AAC audio track");
        java.net.URI base = java.net.URI.create("rtsp://192.168.1.50:554/Preview_01_sub/");
        check(Sdp.control(base, "trackID=1").toString().equals("rtsp://192.168.1.50:554/Preview_01_sub/trackID=1"), "Relative track control");
        check(Sdp.control(base, "rtsp://192.168.1.50/Preview_01_sub/trackID=1").toString().equals("rtsp://192.168.1.50/Preview_01_sub/trackID=1"), "Absolute control without the port is accepted");
        check(Sdp.control(base, "*") == base && Sdp.control(base, "") == base, "Aggregate control means the base URL");
        check(Sdp.control(java.net.URI.create("rtsp://192.168.1.50:554/Preview_01_sub"), "trackID=2").toString().endsWith(":554/Preview_01_sub/trackID=2"), "Relative control without a trailing slash");
        for (String bad : new String[] {"http://192.168.1.50/x", "rtsp://user:pw@192.168.1.50/x", "rtsp://192.168.1.50/x#f"}) {
            boolean refused = false;
            try { Sdp.control(base, bad); } catch (java.io.IOException e) { refused = true; }
            check(refused, "Control URL refused: " + bad);
        }
    }
    static void h264() throws Exception {
        Protocol.H264 depacketizer = new Protocol.H264();
        byte[] single = Protocol.rtp(new byte[] {0x65, 1, 2, 3}, 1, 1000, 7, 96); single[1] |= (byte) 0x80;
        byte[] frame = depacketizer.accept(new Protocol.Rtp(single));
        check(frame != null && frame.length == 8 && frame[3] == 1 && frame[4] == 0x65, "Single NAL becomes an Annex B access unit");
        check(VideoParameters.keyframe(frame, false) && !VideoParameters.keyframe(new byte[] {0, 0, 0, 1, 0x41, 1}, false), "IDR is told apart from a P slice");
        byte[] start = Protocol.rtp(new byte[] {0x7c, (byte) 0x85, 9, 9}, 2, 2000, 7, 96);
        byte[] end = Protocol.rtp(new byte[] {0x7c, 0x45, 8}, 3, 2000, 7, 96); end[1] |= (byte) 0x80;
        check(depacketizer.accept(new Protocol.Rtp(start)) == null, "FU-A start waits for the end");
        byte[] joined = depacketizer.accept(new Protocol.Rtp(end));
        check(joined != null && joined[4] == 0x65 && joined.length == 4 + 1 + 2 + 1, "FU-A fragments are joined");
    }
    static void visitor() {
        VisitorWatch watch = new VisitorWatch(5000);
        check(!watch.observe(snapshot("available", "on"), 0), "Initial on snapshot does not ring");
        check(!watch.observe(snapshot("available", "off"), 1000), "Off does not ring");
        check(watch.observe(snapshot("available", "on"), 2000), "Off to on rings");
        check(!watch.observe(snapshot("available", "off"), 3000) && !watch.observe(snapshot("available", "on"), 4000), "Second press inside cooldown is ignored");
        check(!watch.observe(snapshot("available", "off"), 9000) && watch.observe(snapshot("available", "on"), 10000), "Press after cooldown rings");
        check(!watch.observe(snapshot("disconnected", null), 20000) && !watch.observe(snapshot("available", "on"), 21000), "Reconnect snapshot does not ring");
        check(!watch.observe(snapshot("available", "unavailable"), 30000) && !watch.observe(snapshot("available", "on"), 31000), "Unavailable resets the baseline");
        watch.reset(); check(!watch.observe(snapshot("available", "off"), 40000) && watch.observe(snapshot("available", "on"), 50000), "Reset then a real press rings");
    }
    static Map<String, Object> snapshot(String status, String state) {
        Map<String, Object> m = new HashMap<>(); m.put("status", status); m.put("state", state); return m;
    }
    static void api() throws Exception {
        JSONArray ok = new JSONArray("[{\"cmd\":\"GetDevInfo\",\"code\":0,\"value\":{\"DevInfo\":{\"model\":\"Reolink Video Doorbell PoE\",\"name\":\"Front door\",\"firmVer\":\"v3.0.0.4410_2410261\",\"hardVer\":\"DB_566128M5MP_P\",\"channelNum\":1}}}]");
        ReolinkApi.DeviceInfo info = ReolinkApi.parseDeviceInfo(ReolinkApi.value(ok, "GetDevInfo"));
        check(info.model.equals("Reolink Video Doorbell PoE") && info.firmware.startsWith("v3.0.0") && info.channels == 1, "Device info parses");
        check(info.summary().equals("Connected to Front door (Reolink Video Doorbell PoE), firmware v3.0.0.4410_2410261"), "Device summary");
        JSONArray denied = new JSONArray("[{\"cmd\":\"Login\",\"code\":1,\"error\":{\"detail\":\"login failed\",\"rspCode\":-7}}]");
        try { ReolinkApi.value(denied, "Login"); throw new AssertionError("login error accepted"); }
        catch (java.io.IOException e) { check(e.getMessage().equals("Doorbell rejected the username or password"), "Wrong password is explained"); }
        JSONArray other = new JSONArray("[{\"cmd\":\"GetDevInfo\",\"code\":1,\"error\":{\"detail\":\"not support <x>\",\"rspCode\":-9}}]");
        try { ReolinkApi.value(other, "GetDevInfo"); throw new AssertionError("error accepted"); }
        catch (java.io.IOException e) { check(e.getMessage().equals("Doorbell refused GetDevInfo: not support x (-9)"), "Other errors are sanitized"); }
    }
    static void talk() throws Exception {
        check((G711.ulaw(0) & 255) == 0xFF && (G711.ulaw(32767) & 255) == 0x80 && (G711.ulaw(-32768) & 255) == 0x00, "G.711 mu-law reference points");
        check((G711.alaw(0) & 255) == 0xD5 && (G711.alaw(32767) & 255) == 0xAA && (G711.alaw(-32768) & 255) == 0x2A, "G.711 A-law reference points");
        check((G711.ulaw(1000) & 255) == 0xCE && (G711.alaw(1000) & 255) == 0xFA && (G711.alaw(-1000) & 255) == 0x7A && (G711.alaw(100) & 255) == 0xD3, "G.711 mid-range samples");
        byte[] pcm = {(byte) 0xE8, 0x03, 0, 0, 0}; // 1000, 0, and an odd trailing byte.
        byte[] law = G711.encode(pcm, pcm.length, false);
        check(law.length == 2 && (law[0] & 255) == 0xCE && (law[1] & 255) == 0xFF, "Little-endian PCM encodes sample by sample");
        String text = "v=0\r\no=- 1 1 IN IP4 192.168.1.50\r\ns=x\r\nt=0 0\r\n"
            + "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:trackID=1\r\na=recvonly\r\n"
            + "m=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\na=control:trackID=2\r\na=recvonly\r\n"
            + "m=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\na=control:trackID=3\r\na=sendonly\r\n"
            + "m=audio 0 RTP/AVP 0\r\na=control:trackID=4\r\na=sendonly\r\n";
        List<Sdp.Track> tracks = Sdp.parse(text);
        Sdp.Track back = Sdp.backchannel(tracks);
        check(back != null && back.control.equals("trackID=4") && back.codec.equals("PCMU") && back.rate == 8000, "Backchannel prefers G.711, static payload without rtpmap");
        check(Sdp.backchannel(tracks.subList(0, 3)).control.equals("trackID=3"), "AAC backchannel when no G.711");
        check(Sdp.backchannel(tracks.subList(0, 2)) == null && !Sdp.hasBackchannel(tracks.subList(0, 2)), "No sendonly track means no talk");
        List<Sdp.Track> opus = Sdp.parse("v=0\r\nm=audio 0 RTP/AVP 111\r\na=rtpmap:111 OPUS/48000/2\r\na=control:t\r\na=sendonly\r\n");
        check(Sdp.backchannel(opus) == null && Sdp.hasBackchannel(opus), "Unsupported talk codec is told apart from no backchannel");
        List<Sdp.Track> session = Sdp.parse("v=0\r\na=sendonly\r\nm=audio 0 RTP/AVP 8\r\na=control:t\r\n");
        check(Sdp.backchannel(session).codec.equals("PCMA"), "Session-level direction applies to its tracks");
        byte[] au = Talkback.aacPayload(new byte[300]);
        check(au.length == 304 && au[0] == 0 && au[1] == 16 && (((au[2] & 255) << 5) | ((au[3] & 255) >>> 3)) == 300, "RFC 3640 AU header carries the frame size");
        check(Talkback.interleavedChannel("RTP/AVP/TCP;unicast;interleaved=2-3") == 2, "Doorbell may move the talk channel");
        try { Talkback.interleavedChannel("RTP/AVP;unicast;client_port=5000-5001"); throw new AssertionError("UDP accepted"); }
        catch (java.io.IOException e) { check(e.getMessage().contains("TCP"), "UDP-only talk answer is refused"); }
    }
    /** A fake doorbell: Digest challenge, then a backchannel DESCRIBE reply, then one interleaved RTP frame from the client. */
    static void rtsp() throws Exception {
        java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        StringBuilder seen = new StringBuilder(); byte[][] frame = new byte[1][];
        Thread fake = new Thread(() -> {
            try (java.net.Socket s = server.accept()) {
                java.io.InputStream in = new java.io.BufferedInputStream(s.getInputStream()); java.io.OutputStream out = s.getOutputStream();
                Map<String, String> first = request(in); seen.append(first.get("require")).append('|').append(first.containsKey("authorization"));
                out.write(("RTSP/1.0 401 Unauthorized\r\nCSeq: " + first.get("cseq") + "\r\nWWW-Authenticate: Digest realm=\"BC\", nonce=\"n1\"\r\n\r\n").getBytes("UTF-8"));
                Map<String, String> second = request(in); seen.append('|').append(second.get("authorization").startsWith("Digest username=\"kiosk\""));
                String sdp = "v=0\r\nm=audio 0 RTP/AVP 0\r\na=control:trackID=9\r\na=sendonly\r\n";
                out.write(("RTSP/1.0 200 OK\r\nCSeq: " + second.get("cseq") + "\r\nContent-Length: " + sdp.length() + "\r\n\r\n" + sdp).getBytes("UTF-8"));
                frame[0] = Protocol.read(in).body;
            } catch (Exception e) { seen.append("|error ").append(e); }
        });
        fake.start();
        Map<String, Object> m = base(); m.put("host", "127.0.0.1"); m.put("rtspPort", String.valueOf(server.getLocalPort()));
        Settings settings = new Settings(m);
        RtspClient client = new RtspClient(settings);
        client.connect(settings.streamUrl());
        Protocol.Message reply = client.exchange("DESCRIBE", settings.streamUrl(), "Accept: application/sdp\r\n" + Talkback.BACKCHANNEL);
        check(reply.status == 200 && Sdp.backchannel(Sdp.parse(new String(reply.body, "UTF-8"))).control.equals("trackID=9"), "Backchannel DESCRIBE through Digest auth");
        client.interleaved(0, Protocol.rtp(new byte[] {1, 2}, 1, 160, 7, 0));
        fake.join(5000); client.close(); server.close();
        check(seen.toString().equals("www.onvif.org/ver20/backchannel|false|true"), "Require header sent, credentials only after the challenge (" + seen + ")");
        check(frame[0] != null && frame[0].length == 14 && frame[0][12] == 1, "Talk RTP goes out interleaved on the RTSP socket");
    }
    /** Reads one client request's headers, lower-cased; the clients here never send a request body. */
    static Map<String, String> request(java.io.InputStream in) throws java.io.IOException {
        Map<String, String> headers = new HashMap<>(); StringBuilder line = new StringBuilder();
        for (int c; (c = in.read()) >= 0;) {
            if (c != '\n') { if (c != '\r') line.append((char) c); continue; }
            if (line.length() == 0) return headers;
            int colon = line.indexOf(":");
            if (colon > 0) headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            line.setLength(0);
        }
        throw new java.io.EOFException();
    }
    static void sps() throws Exception {
        // Reolink-style High profile SPS without VUI, from the SDP test (Base64 Z2QAM6wVFKAoAPGQ).
        byte[] plain = Base64.getDecoder().decode("Z2QAM6wVFKAoAPGQ");
        check(SpsPatch.restriction(plain) == null, "Plain SPS has no bitstream restriction");
        byte[] patched = SpsPatch.lowDelay(plain);
        int[] r = SpsPatch.restriction(patched);
        check(r != null && r[0] == 0 && r[1] >= 1, "Patched SPS promises no reordering");
        VideoParameters a = VideoParameters.sps(plain, false), b = VideoParameters.sps(patched, false);
        check(a.width == b.width && a.height == b.height && a.profile == b.profile && a.level == b.level, "Patch keeps size, profile and level");
        check(Arrays.equals(SpsPatch.lowDelay(patched), patched), "Patching is idempotent");
        // An SPS that already has VUI with timing and a reorder of 2 (x264 style).
        byte[] timed = Base64.getDecoder().decode("Z2QAH6zZQFAFuhAAAAMAEAAAAwPI8YMZYA==");
        int[] before = SpsPatch.restriction(timed);
        byte[] fixed = SpsPatch.lowDelay(timed);
        check(Arrays.equals(before, new int[] {2, 4}) && Arrays.equals(SpsPatch.restriction(fixed), new int[] {0, 4}) && VideoParameters.sps(fixed, false).width == VideoParameters.sps(timed, false).width,
            "Existing VUI keeps its fields and loses the reorder (" + Arrays.toString(before) + ")");
        byte[] au = new byte[4 + plain.length + 4 + 3];
        au[3] = 1; System.arraycopy(plain, 0, au, 4, plain.length); au[4 + plain.length + 3] = 1; au[au.length - 3] = 0x65; au[au.length - 2] = 1; au[au.length - 1] = 2;
        byte[] rewritten = SpsPatch.accessUnit(au);
        List<byte[]> units = VideoParameters.annexB(rewritten);
        check(units.size() == 2 && Arrays.equals(units.get(0), patched) && units.get(1)[0] == 0x65, "Access unit carries the patched SPS and the slice untouched");
        check(SpsPatch.accessUnit(new byte[] {0, 0, 0, 1, 0x41, 9}).length == 6, "Units without an SPS pass through");
    }
    /** Speech-like test signal: voiced, band-limited noise in syllables of 100-350 ms with pauses between. */
    static double[] speech(long seed, int n, int rate) {
        Random r = new Random(seed); double[] s = new double[n];
        double noise = 0, voice = 0, amplitude = 0; int left = 0; boolean on = false;
        for (int i = 0; i < n; i++) {
            if (left-- <= 0) { on = !on; left = (int) (rate * (on ? 0.1 + 0.25 * r.nextDouble() : 0.05 + 0.3 * r.nextDouble())); amplitude = 3000 * (0.4 + 0.6 * r.nextDouble()); }
            noise = 0.7 * noise + 0.3 * r.nextGaussian();
            voice = 0.9 * voice + 0.1 * Math.sin(i * 2 * Math.PI * (150 + 50 * Math.sin(i / 3000.0)) / rate);
            s[i] = on ? amplitude * (noise + 2 * voice) : 0;
        }
        return s;
    }
    /**
     * Runs a 12 s call: you talk throughout, the doorbell returns your voice {@code delayMs} later through a short
     * filter with {@code gain} and codec-like distortion, and the visitor talks over you from 8 to 11 s. Sent audio
     * is 20 ms microphone chunks; received audio is 1024-sample decoder chunks arriving with up to 30 ms jitter.
     * Returns {delay estimate ms, echo reduction dB over 4-8 s, visitor SNR dB before, after}.
     */
    static double[] call(int rate, int delayMs, double gain, double distortion, boolean visitor) {
        int n = rate * 12, delay = delayMs * rate / 1000;
        double[] you = speech(7, n, rate), them = speech(9, n, rate), in = new double[n], out = new double[n];
        double[] path = {0.5, 0.2, -0.1, 0.05, 0.02};
        Random r = new Random(3);
        for (int i = 0; i < n; i++) {
            double e = 0; for (int k = 0; k < path.length; k++) { int j = i - delay - k * 3; if (j >= 0) e += path[k] * you[j]; }
            e *= gain; e += distortion * Math.abs(e) * r.nextGaussian();
            in[i] = e + (visitor && i > 8 * rate && i < 11 * rate ? them[i] : 0) + 20 * r.nextGaussian();
        }
        EchoCanceller c = new EchoCanceller(rate);
        int chunkOut = rate / 50, chunkIn = 1024, sent = 0, got = 0; long t0 = 1_000_000_000L;
        while (got + chunkIn <= n) {
            long sendEnd = t0 + (long) ((sent + chunkOut) * 1e6 / rate);
            long arrive = t0 + (long) (got * 1e6 / rate) + r.nextInt(30000);
            if (sent + chunkOut <= n && sendEnd <= arrive) {
                short[] p = new short[chunkOut]; for (int i = 0; i < chunkOut; i++) p[i] = (short) you[sent + i];
                c.reference(p, chunkOut, rate, sendEnd); sent += chunkOut;
            } else {
                short[] d = new short[chunkIn]; for (int i = 0; i < chunkIn; i++) d[i] = (short) Math.max(-32768, Math.min(32767, in[got + i]));
                c.process(d, chunkIn, arrive);
                for (int i = 0; i < chunkIn; i++) out[got + i] = d[i];
                got += chunkIn;
            }
        }
        double before = 0, after = 0, voice = 0, mixed = 0, left = 0;
        for (int i = 4 * rate; i < 8 * rate; i++) { before += in[i] * in[i]; after += out[i] * out[i]; }
        for (int i = 8 * rate; i < 11 * rate; i++) {
            voice += them[i] * them[i]; mixed += (in[i] - them[i]) * (in[i] - them[i]); left += (out[i] - them[i]) * (out[i] - them[i]);
        }
        return new double[] {c.delayMillis(), 10 * Math.log10(before / after), 10 * Math.log10(voice / mixed), 10 * Math.log10(voice / left)};
    }
    static void echo() {
        check(new Settings(Collections.emptyMap()).echoCanceller, "Echo cancelling is on by default");
        double[] a = {1, 2, 3, 4, 5, 0, 0}, b = {0, 0, 2, 4, 6, 8, 10};
        check(Math.abs(EchoCanceller.correlation(a, 0, b, 2, 5) - 1) < 1e-9 && EchoCanceller.correlation(a, 0, b, 0, 5) < 1, "Envelope correlation finds the lag");
        double[] wide = call(16000, 480, 1, 0, false);
        check(Math.abs(wide[0] - 480) <= 30, "Echo delay found at 16 kHz (" + wide[0] + " ms)");
        check(wide[1] > 15, "Echo cut by more than 15 dB at 16 kHz (" + Math.round(wide[1]) + " dB)");
        double[] narrow = call(8000, 300, 1, 0, false);
        check(Math.abs(narrow[0] - 300) <= 30 && narrow[1] > 15, "Echo cut at 8 kHz G.711 rate (" + narrow[0] + " ms, " + Math.round(narrow[1]) + " dB)");
        double[] loud = call(16000, 900, 2, 0.1, true);
        check(loud[1] > 12, "Loud distorted echo still cut (" + Math.round(loud[1]) + " dB)");
        check(loud[3] > 12 && loud[3] > loud[2] + 10, "The visitor talking over you stays clear (" + Math.round(loud[2]) + " → " + Math.round(loud[3]) + " dB)");
        double[] both = call(16000, 480, 1, 0.05, true);
        check(both[3] > 15 && both[3] > both[2] + 8, "Double talk keeps the visitor (" + Math.round(both[2]) + " → " + Math.round(both[3]) + " dB)");
        EchoCanceller idle = new EchoCanceller(16000);
        short[] visitorOnly = new short[1024]; for (int i = 0; i < visitorOnly.length; i++) visitorOnly[i] = (short) (i * 37 % 2000 - 1000);
        short[] copy = visitorOnly.clone(); idle.process(copy, copy.length, 1_000_000);
        check(Arrays.equals(copy, visitorOnly) && idle.delayMillis() == -1, "Without your voice nothing is changed");
    }
}
