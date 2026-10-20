// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One RTSP/TCP connection to the doorbell with Digest auth. Shared by the viewer and the talk channel. */
final class RtspClient implements AutoCloseable {
    private final Settings settings;
    private final RtspAuth auth;
    private final Socket socket = new Socket();
    private final NetworkCleanup.Task cleanup = new NetworkCleanup.Task(socket::close);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writer = new Object();
    private InputStream in; private OutputStream out; private int cseq;
    String session = "";

    RtspClient(Settings settings) { this.settings = settings; auth = new RtspAuth(settings.username, settings.password); }
    void connect(URI url) throws IOException {
        if (closed.get()) throw new IOException("Stream closed");
        socket.connect(new InetSocketAddress(url.getHost(), url.getPort()), 8000);
        socket.setSoTimeout(15000); socket.setTcpNoDelay(true);
        in = new BufferedInputStream(socket.getInputStream()); out = socket.getOutputStream();
    }
    boolean closed() { return closed.get(); }
    Protocol.Message read() throws IOException { return Protocol.read(in); }
    int send(String method, URI uri, String extra) throws IOException {
        if (closed.get()) throw new IOException("Stream closed");
        String target = uri.toASCIIString();
        if (target.contains("\r") || target.contains("\n")) throw new IOException("Invalid RTSP URI");
        synchronized (writer) {
            String message = method + " " + target + " RTSP/1.0\r\nCSeq: " + (++cseq) + "\r\nUser-Agent: ReolinkSatellite/0.2\r\n"
                + auth.header(method, target) + (session.isEmpty() ? "" : "Session: " + session + "\r\n") + extra + "\r\n";
            out.write(message.getBytes("UTF-8")); out.flush();
            return cseq;
        }
    }
    /** Sends one interleaved RTP packet: '$', channel, 16-bit length, packet. */
    void interleaved(int channel, byte[] packet) throws IOException {
        if (closed.get()) throw new IOException("Stream closed");
        if (packet.length > 65535) throw new IOException("RTP packet too large");
        byte[] frame = new byte[4 + packet.length];
        frame[0] = '$'; frame[1] = (byte) channel; frame[2] = (byte) (packet.length >>> 8); frame[3] = (byte) packet.length;
        System.arraycopy(packet, 0, frame, 4, packet.length);
        synchronized (writer) { out.write(frame); out.flush(); }
    }
    /** Sends a request and answers one Digest challenge. Returns the reply whatever its status, except auth failures. */
    Protocol.Message exchange(String method, URI uri, String extra) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            int sent = send(method, uri, extra);
            Protocol.Message m = response(sent);
            if (m.status == 401) {
                // First 401 carries the challenge; a second one with credentials means they are wrong.
                if (attempt > 0 || settings.password.isEmpty()) throw new IOException("Doorbell rejected the username or password");
                auth.challenge(m.headers.get("www-authenticate")); continue;
            }
            return m;
        }
        throw new IOException("Doorbell rejected the username or password");
    }
    Protocol.Message request(String method, URI uri, String extra) throws IOException {
        Protocol.Message m = exchange(method, uri, extra);
        if (m.status == 404) throw new IOException("Doorbell has no such stream; check the channel and that RTSP is enabled");
        if (m.status < 200 || m.status >= 300) throw new IOException("RTSP request rejected (status " + m.status + ")");
        return m;
    }
    /** Takes the session id from a SETUP reply; it must stay the same across tracks. */
    void session(Protocol.Message setup) throws IOException {
        String returned = setup.headers.getOrDefault("session", "").split(";")[0].trim();
        if (returned.isEmpty() || returned.contains("\r") || returned.contains("\n")) throw new IOException("Missing RTSP session");
        if (!session.isEmpty() && !session.equals(returned)) throw new IOException("RTSP session changed during setup");
        session = returned;
    }
    private Protocol.Message response(int sent) throws IOException {
        for (int i = 0; i < 1024; i++) {
            Protocol.Message m = Protocol.read(in);
            if (m.channel >= 0) continue;
            if (!String.valueOf(sent).equals(m.headers.get("cseq"))) throw new IOException("RTSP response sequence mismatch");
            return m;
        } throw new IOException("RTSP response not received");
    }
    public void close() { closed.set(true); cleanup.close(); }
}
