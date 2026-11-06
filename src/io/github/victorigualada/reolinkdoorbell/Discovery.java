// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds ONVIF cameras on the local network with WS-Discovery: one multicast Probe, unicast ProbeMatch replies.
 * Reolink doorbells answer with a stable endpoint ID (it survives an address change), their address and scopes
 * naming the model. Replies come back to the sending socket, so no multicast lock is needed on Android.
 */
final class Discovery {
    static final String GROUP = "239.255.255.250";
    static final int PORT = 3702;

    /** One camera that answered. {@code id} is its WS-Discovery endpoint address, such as urn:uuid:…. */
    static final class Device {
        final String id, host, name, hardware;
        Device(String id, String host, String name, String hardware) { this.id = id; this.host = host; this.name = name; this.hardware = hardware; }
        boolean reolink() { return (name + " " + hardware).toLowerCase(Locale.ROOT).contains("reolink"); }
        boolean doorbell() { return (name + " " + hardware).toLowerCase(Locale.ROOT).contains("doorbell"); }
        /** For the settings picker: model, then address. */
        String label() {
            String model = !hardware.isEmpty() ? hardware : !name.isEmpty() ? name : "ONVIF camera";
            if (!name.isEmpty() && !name.equals(hardware) && !hardware.isEmpty()) model = name + " (" + hardware + ")";
            return DisplayText.limit(model, 60) + " — " + host;
        }
    }

    private Discovery() {}

    static String probe(String messageId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\""
            + " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">"
            + "<e:Header><w:MessageID>uuid:" + messageId + "</w:MessageID>"
            + "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>"
            + "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>"
            + "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>";
    }

    private static final Pattern MATCH = Pattern.compile("<(?:[\\w-]+:)?ProbeMatch\\b[^>]*>(.*?)</(?:[\\w-]+:)?ProbeMatch>", Pattern.DOTALL);
    private static final Pattern ADDRESS = Pattern.compile("<(?:[\\w-]+:)?Address\\b[^>]*>\\s*([^<]+?)\\s*</", Pattern.DOTALL);
    private static final Pattern XADDRS = Pattern.compile("<(?:[\\w-]+:)?XAddrs\\b[^>]*>\\s*([^<]+?)\\s*</", Pattern.DOTALL);
    private static final Pattern SCOPES = Pattern.compile("<(?:[\\w-]+:)?Scopes\\b[^>]*>\\s*([^<]*?)\\s*</", Pattern.DOTALL);

    /** The devices in one ProbeMatches reply. Replies without a usable IPv4 address or endpoint ID are skipped. */
    static List<Device> parse(String xml) {
        List<Device> found = new ArrayList<>();
        Matcher match = MATCH.matcher(xml);
        while (match.find()) {
            String body = match.group(1);
            String id = first(ADDRESS, body), addrs = first(XADDRS, body), scopes = first(SCOPES, body);
            String host = null;
            for (String url : addrs.split("\\s+")) {
                try {
                    String h = URI.create(url).getHost();
                    if (h != null && h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) { host = h; break; }
                } catch (IllegalArgumentException ignored) { /* Skip malformed addresses. */ }
            }
            if (host == null || id.isEmpty() || id.length() > 512) continue;
            found.add(new Device(id, host, scope(scopes, "name"), scope(scopes, "hardware")));
        }
        return found;
    }
    private static String first(Pattern p, String s) { Matcher m = p.matcher(s); return m.find() ? m.group(1).trim() : ""; }
    /** The value of an onvif://www.onvif.org/<kind>/<value> scope, URL-decoded, or "". */
    static String scope(String scopes, String kind) {
        for (String s : scopes.split("\\s+")) {
            String prefix = "onvif://www.onvif.org/" + kind + "/";
            if (!s.startsWith(prefix)) continue;
            try { return URLDecoder.decode(s.substring(prefix.length()).replace("+", "%2B"), "UTF-8").trim(); }
            catch (Exception e) { return s.substring(prefix.length()); }
        }
        return "";
    }

    /** Doorbells first, then other Reolink cameras, then anything else; each by address. */
    static List<Device> sorted(Collection<Device> devices) {
        List<Device> list = new ArrayList<>(devices);
        list.sort(Comparator.comparingInt((Device d) -> d.doorbell() ? 0 : d.reolink() ? 1 : 2).thenComparing(d -> d.host));
        return list;
    }
    /** The device to pick on its own: the only doorbell, else the only Reolink camera, else none. */
    static Device obvious(Collection<Device> devices) {
        Device only = null; int doorbells = 0, reolinks = 0; Device onlyReolink = null;
        for (Device d : devices) { if (d.doorbell()) { doorbells++; only = d; } if (d.reolink()) { reolinks++; onlyReolink = d; } }
        return doorbells == 1 ? only : doorbells == 0 && reolinks == 1 ? onlyReolink : null;
    }

    /** Sends the probe twice and gathers replies for {@code millis}. Keyed by endpoint ID, latest address wins. */
    static List<Device> search(int millis) throws IOException {
        Map<String, Device> found = new LinkedHashMap<>();
        byte[] message = probe(UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8);
        try (MulticastSocket socket = new MulticastSocket()) {
            socket.setTimeToLive(2);
            InetAddress group = InetAddress.getByName(GROUP);
            long end = System.nanoTime() + millis * 1_000_000L;
            byte[] buffer = new byte[65535];
            for (int sent = 0; ; ) {
                if (sent < 2) { socket.send(new DatagramPacket(message, message.length, group, PORT)); sent++; } // UDP may drop one.
                long left = (end - System.nanoTime()) / 1_000_000L;
                if (left <= 0) break;
                socket.setSoTimeout((int) Math.min(left, sent < 2 ? 300 : left));
                try {
                    DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                    socket.receive(reply);
                    for (Device d : parse(new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8))) found.put(d.id, d);
                } catch (SocketTimeoutException ignored) { /* Next send, or done. */ }
            }
        }
        return sorted(found.values());
    }
}
