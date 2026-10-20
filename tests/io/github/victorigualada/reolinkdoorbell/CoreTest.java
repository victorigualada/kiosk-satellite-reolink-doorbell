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
        settings(); digest(); headers(); sdp(); h264(); visitor(); api();
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
        boolean refused = false;
        try { Sdp.control(base, "rtsp://evil.example/x"); } catch (java.io.IOException e) { refused = true; }
        check(refused, "Track control cannot point at another host");
    }
    static void h264() throws Exception {
        Protocol.H264 depacketizer = new Protocol.H264();
        byte[] single = Protocol.rtp(new byte[] {0x65, 1, 2, 3}, 1, 1000, 7, 96); single[1] |= (byte) 0x80;
        byte[] frame = depacketizer.accept(new Protocol.Rtp(single));
        check(frame != null && frame.length == 8 && frame[3] == 1 && frame[4] == 0x65, "Single NAL becomes an Annex B access unit");
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
}
