// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

import java.net.URI;
import java.util.Map;

final class Settings {
    final String host, username, password, stream, sizing, viewport, controls, lockEntity, visitorEntity, autoClose;
    final float horizontal, vertical;
    final int rtspPort, httpPort, channel, controlSize, autoCloseSeconds;
    final boolean https, startMuted, autoOpen, wakeScreen;

    Settings(Map<String, Object> m) {
        host = text(m, "host").trim();
        if (!host.isEmpty() && !host.matches("[A-Za-z0-9.-]{1,253}|\\[?[0-9A-Fa-f:.]{2,45}\\]?"))
            throw new IllegalArgumentException("Doorbell address must be a hostname or IP, without scheme, port or path");
        username = text(m, "username").trim();
        password = text(m, "password");
        for (String value : new String[] {username, password})
            if (value.contains("\r") || value.contains("\n")) throw new IllegalArgumentException("Invalid username or password format");
        rtspPort = number(m, "rtspPort", 554, 1, 65535);
        httpPort = number(m, "httpPort", 80, 1, 65535);
        https = flag(m, "https", false);
        channel = number(m, "channel", 0, 0, 15);
        stream = choice(m, "stream", "sub", "sub", "main");
        sizing = choice(m, "sizing", "Fit", "Fit", "Fill", "Stretch");
        viewport = choice(m, "viewport", "Auto", "Auto", "4:3", "16:9", "16:10");
        controls = choice(m, "controls", "Top", "Top", "Bottom", "Left", "Right");
        autoClose = choice(m, "autoClose", "1 minute", "Off", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes");
        autoCloseSeconds = "Off".equals(autoClose) ? 0 : "30 seconds".equals(autoClose) ? 30 : "1 minute".equals(autoClose) ? 60
            : "2 minutes".equals(autoClose) ? 120 : "5 minutes".equals(autoClose) ? 300 : 600;
        visitorEntity = entity(m, "visitorEntity", "binary_sensor", "Choose the doorbell's visitor binary sensor or leave it empty");
        lockEntity = entity(m, "lockEntity", "lock", "Choose a lock entity or leave Door lock empty");
        horizontal = number(m, "horizontal", 50, 0, 100) / 100f;
        vertical = number(m, "vertical", 50, 0, 100) / 100f;
        controlSize = number(m, "controlSize", 56, 48, 96);
        startMuted = flag(m, "startMuted", false);
        autoOpen = flag(m, "autoOpen", true);
        wakeScreen = flag(m, "wakeScreen", true);
    }
    void requireConnection() {
        if (host.isEmpty() || username.isEmpty())
            throw new IllegalArgumentException("Set the doorbell address and username in plugin settings");
    }
    /** Reolink names streams by 1-based channel: Preview_01_main, Preview_01_sub. */
    URI streamUrl() {
        requireConnection();
        String path = String.format(java.util.Locale.ROOT, "/Preview_%02d_%s", channel + 1, stream);
        return URI.create("rtsp://" + bracketed() + ":" + rtspPort + path);
    }
    URI apiUrl(String query) {
        requireConnection();
        return URI.create((https ? "https://" : "http://") + bracketed() + ":" + httpPort + "/cgi-bin/api.cgi?" + query);
    }
    private String bracketed() {
        String h = host.replace("[", "").replace("]", "");
        return h.indexOf(':') >= 0 ? "[" + h + "]" : h;
    }
    private static String entity(Map<String, Object> m, String key, String domain, String error) {
        String id = text(m, key).trim();
        if (!id.isEmpty() && (id.length() > 255 || !id.matches(domain.replace(".", "\\.") + "\\.[a-z0-9_]+")))
            throw new IllegalArgumentException(error);
        return id;
    }
    private static String text(Map<String, Object> m, String key) { Object v = m.get(key); return v instanceof String ? (String) v : ""; }
    private static boolean flag(Map<String, Object> m, String key, boolean d) { return m.get(key) instanceof Boolean ? (Boolean) m.get(key) : d; }
    private static int number(Map<String, Object> m, String key, int d, int min, int max) {
        Object v = m.get(key); double n = v instanceof Number ? ((Number) v).doubleValue() : d;
        if (!Double.isFinite(n) || n < min || n > max) throw new IllegalArgumentException("Out-of-range setting: " + key);
        return (int) Math.round(n);
    }
    private static String choice(Map<String, Object> m, String key, String d, String... options) {
        String v = text(m, key); if (v.isEmpty()) return d;
        for (String o : options) if (o.equals(v)) return v;
        throw new IllegalArgumentException("Unsupported setting: " + key);
    }
}
