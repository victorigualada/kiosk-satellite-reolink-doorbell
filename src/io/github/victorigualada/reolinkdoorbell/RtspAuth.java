// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** RFC 2617 Digest (MD5, optional qop=auth) with Basic fallback for RTSP. Reolink answers 401 with Digest. */
final class RtspAuth {
    private final String username, password;
    private String realm, nonce, opaque, qop;
    private boolean digest, basic;
    private int count;
    private final String cnonce;

    RtspAuth(String username, String password) { this(username, password, randomHex()); }
    RtspAuth(String username, String password, String cnonce) { this.username = username; this.password = password; this.cnonce = cnonce; }

    boolean ready() { return digest || basic; }

    /** Each WWW-Authenticate challenge is on its own line; Digest wins over Basic. */
    void challenge(String headers) throws IOException {
        if (headers == null || headers.isEmpty()) throw new IOException("Doorbell asked for authentication without a challenge");
        boolean sawDigest = false, sawBasic = false;
        for (String line : headers.split("\n")) {
            String value = line.trim();
            if (value.regionMatches(true, 0, "Digest ", 0, 7)) {
                Map<String, String> p = params(value.substring(7));
                String algorithm = p.getOrDefault("algorithm", "MD5");
                if (!"MD5".equalsIgnoreCase(algorithm)) continue;
                String q = p.get("qop");
                if (q != null) {
                    boolean auth = false;
                    for (String option : q.split(",")) auth |= "auth".equalsIgnoreCase(option.trim());
                    if (!auth) continue;
                }
                if (p.get("nonce") == null || p.get("realm") == null) throw new IOException("Malformed Digest challenge from doorbell");
                boolean stale = sawDigest || "true".equalsIgnoreCase(p.get("stale"));
                realm = p.get("realm"); nonce = p.get("nonce"); opaque = p.get("opaque"); qop = q == null ? null : "auth";
                sawDigest = true; if (!stale) count = 0;
            } else if (value.regionMatches(true, 0, "Basic", 0, 5)) sawBasic = true;
        }
        if (!sawDigest && !sawBasic) throw new IOException("Doorbell offered no supported RTSP authentication");
        digest = sawDigest; basic = !sawDigest;
    }

    /** Authorization header line including CRLF, or empty when no challenge was seen yet. */
    String header(String method, String uri) {
        if (basic) return "Authorization: Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)) + "\r\n";
        if (!digest) return "";
        String ha1 = md5(username + ":" + realm + ":" + password), ha2 = md5(method + ":" + uri);
        StringBuilder b = new StringBuilder("Authorization: Digest username=\"").append(quote(username))
            .append("\", realm=\"").append(quote(realm)).append("\", nonce=\"").append(quote(nonce))
            .append("\", uri=\"").append(quote(uri)).append('"');
        if (qop != null) {
            String nc = String.format(Locale.ROOT, "%08x", ++count);
            b.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"')
                .append(", response=\"").append(md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2)).append('"');
        } else b.append(", response=\"").append(md5(ha1 + ":" + nonce + ":" + ha2)).append('"');
        if (opaque != null) b.append(", opaque=\"").append(quote(opaque)).append('"');
        return b.append("\r\n").toString();
    }

    static Map<String, String> params(String text) {
        Map<String, String> out = new HashMap<>();
        int i = 0, n = text.length();
        while (i < n) {
            while (i < n && (text.charAt(i) == ',' || text.charAt(i) == ' ')) i++;
            int eq = text.indexOf('=', i); if (eq < 0) break;
            String key = text.substring(i, eq).trim().toLowerCase(Locale.ROOT); i = eq + 1;
            StringBuilder value = new StringBuilder();
            if (i < n && text.charAt(i) == '"') {
                i++;
                while (i < n && text.charAt(i) != '"') { if (text.charAt(i) == '\\' && i + 1 < n) i++; value.append(text.charAt(i++)); }
                i++;
            } else while (i < n && text.charAt(i) != ',') value.append(text.charAt(i++));
            out.put(key, value.toString().trim());
        }
        return out;
    }
    private static String quote(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    static String md5(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(); for (byte x : hash) b.append(String.format(Locale.ROOT, "%02x", x & 255)); return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String randomHex() { byte[] b = new byte[8]; new java.security.SecureRandom().nextBytes(b); StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 255)); return s.toString(); }
}
