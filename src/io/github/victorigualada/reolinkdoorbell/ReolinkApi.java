// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Reolink HTTP API (/cgi-bin/api.cgi): token login, device info and logout. Used only to validate settings. */
final class ReolinkApi implements AutoCloseable {
    private static final int MAX_RESPONSE = 256 * 1024;
    static final class DeviceInfo {
        final String model, name, firmware, hardware; final int channels;
        DeviceInfo(String model, String name, String firmware, String hardware, int channels) {
            this.model = model; this.name = name; this.firmware = firmware; this.hardware = hardware; this.channels = channels;
        }
        String summary() {
            String label = name.isEmpty() || name.equals(model) ? model : name + " (" + model + ")";
            return DisplayText.singleLine("Connected to " + label + ", firmware " + (firmware.isEmpty() ? "unknown" : firmware), 300);
        }
    }
    private final Settings settings;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile NetworkCleanup.Task active;
    private String token = "";

    ReolinkApi(Settings settings) { settings.requireConnection(); this.settings = settings; }

    DeviceInfo deviceInfo() throws Exception {
        login();
        try {
            JSONObject value = value(call("GetDevInfo", new JSONObject()), "GetDevInfo");
            return parseDeviceInfo(value);
        } finally { logout(); }
    }
    /** Logs in and returns the session token for a stream URL; pair with logout(). */
    String token() throws Exception { login(); return token; }
    void login() throws Exception {
        JSONObject user = new JSONObject().put("Version", "0").put("userName", settings.username).put("password", settings.password);
        JSONObject value = value(call("Login", new JSONObject().put("User", user)), "Login");
        JSONObject t = value.optJSONObject("Token");
        String name = t == null ? "" : t.optString("name", "");
        if (!name.matches("[A-Za-z0-9]{1,64}")) throw new IOException("Doorbell login returned no session token");
        token = name;
    }
    void logout() {
        if (token.isEmpty() || closed.get()) return;
        try { call("Logout", new JSONObject()); } catch (Exception ignored) { /* The token expires on its own. */ }
        token = "";
    }
    private synchronized JSONArray call(String command, JSONObject param) throws Exception {
        if (closed.get()) throw new IOException("Doorbell check cancelled");
        URI uri = settings.apiUrl("cmd=" + command + (token.isEmpty() ? "" : "&token=" + token));
        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
        NetworkCleanup.Task cleanup = new NetworkCleanup.Task(c::disconnect);
        active = cleanup;
        try {
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Accept", "application/json");
            byte[] body = new JSONArray().put(new JSONObject().put("cmd", command).put("action", 0).put("param", param)).toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("Doorbell HTTP API returned HTTP " + code + "; check the HTTP port and that HTTP is enabled");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[4096]; int n;
                while ((n = in.read(b)) != -1) {
                    if (closed.get()) throw new IOException("Doorbell check cancelled");
                    if (out.size() + n > MAX_RESPONSE) throw new IOException("Doorbell response exceeds safety limit");
                    out.write(b, 0, n);
                }
                Object parsed;
                try { parsed = new JSONTokener(out.toString("UTF-8")).nextValue(); }
                catch (JSONException e) { throw new IOException("Doorbell returned an unsupported response"); }
                if (!(parsed instanceof JSONArray)) throw new IOException("Doorbell returned an unsupported response");
                return (JSONArray) parsed;
            }
        } finally { cleanup.closeOnOwnerThread(); active = null; }
    }
    /** Every reply is [{cmd, code, value}] or [{cmd, code, error: {rspCode, detail}}]. */
    static JSONObject value(JSONArray reply, String command) throws IOException {
        JSONObject first = reply.optJSONObject(0);
        if (first == null) throw new IOException("Doorbell returned an empty " + command + " response");
        if (first.optInt("code", -1) != 0 || first.has("error")) {
            JSONObject error = first.optJSONObject("error");
            int rsp = error == null ? 0 : error.optInt("rspCode", 0);
            if ("Login".equals(command) && (rsp == -6 || rsp == -7 || rsp == -502))
                throw new IOException("Doorbell rejected the username or password");
            if (rsp == -5 || rsp == -501) throw new IOException("Doorbell is temporarily locked after failed logins; wait a few minutes");
            String detail = error == null ? "" : DisplayText.singleLine(error.optString("detail", ""), 120).replaceAll("[^A-Za-z0-9 .,:_-]", "");
            throw new IOException("Doorbell refused " + command + (detail.isEmpty() ? "" : ": " + detail) + (rsp != 0 ? " (" + rsp + ")" : ""));
        }
        JSONObject value = first.optJSONObject("value");
        if (value == null) throw new IOException("Doorbell returned no " + command + " data");
        return value;
    }
    static DeviceInfo parseDeviceInfo(JSONObject value) throws IOException {
        JSONObject info = value.optJSONObject("DevInfo");
        if (info == null) throw new IOException("Doorbell returned no device information");
        return new DeviceInfo(clean(info.optString("model", "Reolink")), clean(info.optString("name", "")), clean(info.optString("firmVer", "")),
            clean(info.optString("hardVer", "")), info.optInt("channelNum", 1));
    }
    private static String clean(String value) { return DisplayText.singleLine(value.trim(), 80); }
    public void close() { closed.set(true); NetworkCleanup.Task c = active; if (c != null) c.close(); }
}
