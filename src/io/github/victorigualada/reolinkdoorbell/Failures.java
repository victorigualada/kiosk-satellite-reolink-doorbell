// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

final class Failures {
    private Failures() {}
    // Network/decoder exceptions may embed secret stream URLs. Never display raw messages.
    static String describe(Exception e, String fallback) {
        if (e instanceof java.net.ConnectException) return "Doorbell refused the connection; check the address and that RTSP/HTTP are enabled";
        if (e instanceof java.net.SocketTimeoutException) return "Connection timed out; stopped audio and video";
        if (e instanceof java.net.UnknownHostException) return "Configured hostname could not be resolved";
        if (e instanceof SecurityException) return "Microphone access denied. Grant Kiosk Satellite microphone access locally";
        if (e instanceof IllegalArgumentException) {
            String m = e.getMessage();
            if (m != null && (m.startsWith("Doorbell address") || m.startsWith("Set the doorbell") || m.startsWith("Choose ") || m.startsWith("Out-of-range setting") || m.startsWith("Unsupported setting") || m.startsWith("Invalid username"))) return m;
        }
        // Only explicit application-owned IO errors, never messages from network/library code.
        StackTraceElement[] trace = e.getStackTrace();
        if (e.getClass() == java.io.IOException.class && trace.length > 0
                && trace[0].getClassName().matches("io\\.github\\.victorigualada\\.reolinkdoorbell\\.(ReolinkApi|RtspAuth|RtspClient|Talkback|StartupProbe|Protocol(?:\\$(?:H264|H265))?|Sdp|StreamPlayer|StreamPlayer\\$Decoder|VideoParameters|VideoParameters\\$Bits|VideoDecoder)")) return e.getMessage();
        return fallback;
    }
}
