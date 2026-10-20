// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One talk session over the doorbell's ONVIF RTSP backchannel: a second RTSP connection that asks for the
 * a=sendonly audio track and sends microphone RTP on it, interleaved. Never records or buffers speech to disk.
 */
final class Talkback implements AutoCloseable {
    interface Listener { void status(String text, boolean error); void microphoneActive(); void finished(); }
    static final String BACKCHANNEL = "Require: www.onvif.org/ver20/backchannel\r\n";
    static final String NO_BACKCHANNEL = "This doorbell does not offer two-way audio over RTSP; check its firmware";
    private final Context context; private final Settings settings; private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object gate = new Object();
    private final long deadline;
    private volatile RtspClient rtsp;
    private volatile AudioRecord recorder;
    private volatile Exception failure;
    private MediaCodec encoder; private boolean encoderStarted;
    Talkback(Context context, Settings settings, Listener listener) {
        this.context = context; this.settings = settings; this.listener = listener;
        deadline = System.nanoTime() + settings.talkSeconds * 1_000_000_000L;
    }
    void start() { Thread t = new Thread(this::run, "reolink-talk"); t.setDaemon(true); t.start(); }
    private boolean active() { return !closed.get() && failure == null && System.nanoTime() < deadline; }
    private void run() {
        try {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException();
            listener.status("Connecting the microphone to the doorbell", false);
            URI url = settings.streamUrl();
            RtspClient client = new RtspClient(settings); rtsp = client;
            if (!active()) return;
            client.connect(url);
            if (!active()) return;
            Protocol.Message description = client.exchange("DESCRIBE", url, "Accept: application/sdp\r\n" + BACKCHANNEL);
            if (description.status == 551) throw new IOException(NO_BACKCHANNEL);
            if (description.status < 200 || description.status >= 300) throw new IOException("Doorbell refused two-way audio (status " + description.status + ")");
            List<Sdp.Track> tracks = Sdp.parse(new String(description.body, "UTF-8"));
            Sdp.Track track = Sdp.backchannel(tracks);
            if (track == null) throw new IOException(Sdp.hasBackchannel(tracks) ? "The doorbell's talk codec is not supported yet" : NO_BACKCHANNEL);
            URI base = url;
            if (description.headers.containsKey("content-base")) base = Sdp.control(url, description.headers.get("content-base"));
            Protocol.Message setup = client.request("SETUP", Sdp.control(base, track.control), "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n" + BACKCHANNEL);
            int channel = interleavedChannel(setup.headers.getOrDefault("transport", ""));
            client.session(setup);
            client.request("PLAY", base, "Range: npt=0.000-\r\n" + BACKCHANNEL);
            if (!active()) return;
            Thread reader = new Thread(() -> drain(client), "reolink-talk-reader"); reader.setDaemon(true); reader.start();
            boolean aac = "MPEG4-GENERIC".equals(track.codec);
            if (track.channels != 1) throw new IOException("Only mono talk audio is supported");
            if (aac) startEncoder(track.rate);
            int minimum = AudioRecord.getMinBufferSize(track.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("Microphone does not support the doorbell's sample rate");
            AudioRecord capture = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setSampleRate(track.rate).build())
                .setBufferSizeInBytes(Math.max(minimum * 2, track.rate / 5)).build();
            synchronized (gate) {
                recorder = capture;
                if (!active()) return;
                if (capture.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone initialization failed");
                capture.startRecording();
            }
            if (capture.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Microphone is unavailable or in use");
            listener.microphoneActive();
            listener.status("Talking — " + (aac ? "AAC" : track.codec) + " — tap the green phone to stop", false);
            SecureRandom random = new SecureRandom();
            int sequence = random.nextInt(65536), ssrc = random.nextInt();
            long samples = 0, keepalive = System.nanoTime();
            byte[] pcm = new byte[track.rate / 50 * 2]; // 20 ms mono PCM.
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (active()) {
                if (System.nanoTime() - keepalive > 10_000_000_000L) { client.send("GET_PARAMETER", base, ""); keepalive = System.nanoTime(); }
                if (!aac) {
                    int n = capture.read(pcm, 0, pcm.length);
                    if (n < 0) throw new IOException("Microphone capture failed; talk stopped");
                    if (n < 2) continue;
                    byte[] payload = G711.encode(pcm, n, "PCMA".equals(track.codec));
                    sequence = send(client, channel, Protocol.rtp(payload, sequence, samples, ssrc, track.payload), sequence);
                    samples += payload.length;
                    continue;
                }
                int index = encoder.dequeueInputBuffer(10000);
                if (index >= 0) {
                    ByteBuffer buffer = encoder.getInputBuffer(index);
                    if (buffer == null) throw new IOException("Encoder input unavailable");
                    int n = capture.read(pcm, 0, Math.min(pcm.length, buffer.capacity()) & ~1);
                    if (n < 0) throw new IOException("Microphone capture failed; talk stopped");
                    buffer.clear(); buffer.put(pcm, 0, n);
                    encoder.queueInputBuffer(index, 0, n, samples * 1000000L / track.rate, 0); samples += n / 2;
                }
                for (;;) {
                    int output = encoder.dequeueOutputBuffer(info, 0);
                    if (output == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (output < 0) continue;
                    try {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || info.size == 0) continue;
                        ByteBuffer data = encoder.getOutputBuffer(output);
                        if (data == null || info.size > 8191) throw new IOException("Unexpected talk encoder output");
                        data.position(info.offset); data.limit(info.offset + info.size);
                        byte[] encoded = new byte[info.size]; data.get(encoded);
                        byte[] packet = Protocol.rtp(aacPayload(encoded), sequence, Math.max(0, info.presentationTimeUs) * track.rate / 1000000L, ssrc, track.payload);
                        packet[1] |= (byte) 0x80; // Each packet ends an access unit.
                        sequence = send(client, channel, packet, sequence);
                    } finally { encoder.releaseOutputBuffer(output, false); }
                }
            }
            if (failure != null) throw failure;
            if (!closed.get()) listener.status("Talk time limit reached", false);
        } catch (Exception e) {
            Exception cause = failure != null ? failure : e;
            if (!closed.get()) listener.status(Failures.describe(cause, "Two-way audio failed; check the doorbell firmware and microphone permission"), true);
        } finally {
            close();
            synchronized (gate) {
                AudioRecord r = recorder; recorder = null;
                if (r != null) { try { r.release(); } catch (RuntimeException ignored) {} }
            }
            if (encoder != null) {
                try { if (encoderStarted) encoder.stop(); } catch (RuntimeException ignored) {}
                try { encoder.release(); } catch (RuntimeException ignored) {} encoder = null;
            }
            listener.finished();
        }
    }
    private void startEncoder(int rate) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, 1);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 32000);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(4096, rate / 10));
        String name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format);
        if (name == null) throw new IOException("This tablet has no AAC encoder for the doorbell's sample rate");
        encoder = MediaCodec.createByCodecName(name); encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); encoder.start(); encoderStarted = true;
    }
    /** Stop and send share the gate: no audio goes out after close returns. */
    private int send(RtspClient client, int channel, byte[] packet, int sequence) throws IOException {
        synchronized (gate) { if (active()) client.interleaved(channel, packet); }
        return (sequence + 1) & 65535;
    }
    /** Reads replies and RTCP so the doorbell's side of the socket never fills; a read error ends the talk session. */
    private void drain(RtspClient client) {
        try { while (active()) client.read(); }
        catch (Exception e) { if (!closed.get()) { failure = e; client.close(); } }
    }
    /** RFC 3640 AAC-hbr: one AU header of 13-bit size and 3-bit index. */
    static byte[] aacPayload(byte[] frame) {
        byte[] out = new byte[4 + frame.length];
        out[1] = 16; out[2] = (byte) (frame.length >>> 5); out[3] = (byte) ((frame.length & 0x1F) << 3);
        System.arraycopy(frame, 0, out, 4, frame.length); return out;
    }
    static int interleavedChannel(String transport) throws IOException {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("interleaved=(\\d+)").matcher(transport.toLowerCase(java.util.Locale.ROOT));
        if (!m.find()) throw new IOException("Doorbell did not accept the talk channel over TCP");
        int channel = Integer.parseInt(m.group(1));
        if (channel > 255) throw new IOException("Doorbell returned an invalid talk channel");
        return channel;
    }
    public void close() {
        closed.set(true);
        // Closing the socket first (off this thread) unblocks a send that holds the gate.
        RtspClient c = rtsp; if (c != null) c.close();
        synchronized (gate) {
            AudioRecord r = recorder; if (r != null) try { r.stop(); } catch (RuntimeException ignored) {}
        }
    }
}
