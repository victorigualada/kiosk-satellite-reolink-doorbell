// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

import android.media.*;
import android.view.Surface;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plain RTSP/TCP receiver straight from the doorbell. Android MediaCodec decodes H264/H265 and AAC; no restreamer.
 * It can start before the viewer has a surface: video from the latest keyframe is held until {@link #attach},
 * so connecting and the keyframe wait overlap with waking the screen and opening the overlay.
 */
final class StreamPlayer implements AutoCloseable {
    interface Listener { void status(String message, boolean error); void size(int width, int height); void failed(); default void decoderFallback() {} default void connectionFailed(String reason) { failed(); } }
    private static final Listener QUIET = new Listener() {
        public void status(String message, boolean error) {} public void size(int width, int height) {} public void failed() {}
    };
    private static final long ATTACH_TIMEOUT = 15_000_000_000L;
    private final Settings settings;
    private volatile Surface surface;
    private volatile Listener listener;
    private final Object attachLock = new Object();
    private boolean finished;
    private final List<String> early = new ArrayList<>();
    private final ArrayDeque<byte[]> held = new ArrayDeque<>();
    private final ArrayDeque<Long> heldTimes = new ArrayDeque<>();
    private int heldBytes;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile RtspClient rtsp;
    private volatile boolean muted;
    private final Map<Integer, Decoder> decoders = new HashMap<>();
    private final Protocol.H264 h264 = new Protocol.H264();
    private final Protocol.H265 h265 = new Protocol.H265();
    private long lastVideoFrame;
    // Start-up timing, shown once so a slow first image can be traced to the network or to the keyframe wait.
    private final long opened = System.nanoTime();
    private volatile long playing, firstPacket, firstKey;
    private boolean shownFirst;
    /** A null surface starts the stream ahead of the viewer; {@link #attach} hands it the view later. */
    StreamPlayer(Settings settings, Surface surface, Listener listener) {
        this.settings = settings; this.surface = surface; this.listener = surface == null ? QUIET : listener; muted = settings.startMuted;
    }
    /** Gives an early stream its view. False when it already ended, so the caller starts a fresh one. */
    boolean attach(Surface view, Listener target) {
        List<String> notes;
        synchronized (attachLock) {
            if (finished || closed.get() || surface != null) return false;
            listener = target; surface = view; notes = new ArrayList<>(early); early.clear();
        }
        for (String note : notes) target.status(note, false);
        return true;
    }
    private Listener listener() { return listener; }
    private void note(String message) {
        synchronized (attachLock) { if (surface == null) { early.add(message); return; } }
        listener().status(message, false);
    }
    void start() { Thread t = new Thread(this::run, "reolink-stream"); t.setDaemon(true); t.start(); }
    void setMuted(boolean value) { muted = value; }
    private void run() {
        boolean connecting = true;
        try {
            URI url = settings.streamUrl();
            RtspClient client = new RtspClient(settings); rtsp = client;
            if (closed.get()) return;
            client.connect(url);
            if (closed.get()) return;
            Protocol.Message description = client.request("DESCRIBE", url, "Accept: application/sdp\r\n");
            connecting = false; // SDP/codec errors are media errors, not connection failures.
            List<Sdp.Track> tracks = Sdp.parse(new String(description.body, "UTF-8"));
            URI base = url;
            if (description.headers.containsKey("content-base")) base = Sdp.control(url, description.headers.get("content-base"));
            boolean hasVideo = false, hasAudio = false; int channel = 0; String videoLabel = "";
            for (Sdp.Track track : tracks) {
                boolean video = "video".equals(track.kind) && !hasVideo;
                boolean audio = "audio".equals(track.kind) && !hasAudio;
                if (!video && !audio) continue;
                if (video) { Sdp.videoMime(track); videoLabel = track.codec; }
                if (audio && !"MPEG4-GENERIC".equals(track.codec)) {
                    note("Video only: this stream's audio codec is not yet supported"); continue;
                }
                Decoder decoder = new Decoder(track, video);
                decoders.put(channel, decoder);
                connecting = true;
                Protocol.Message setup = client.request("SETUP", Sdp.control(base, track.control), "Transport: RTP/AVP/TCP;unicast;interleaved=" + channel + "-" + (channel + 1) + "\r\n");
                String transport = setup.headers.getOrDefault("transport", "");
                if (!transport.toLowerCase(Locale.ROOT).contains("interleaved=" + channel + "-" + (channel + 1)))
                    throw new IOException("Doorbell did not accept requested interleaved RTP channels");
                client.session(setup);
                hasVideo |= video; hasAudio |= audio; channel += 2;
                connecting = false;
            }
            if (!hasVideo) throw new IOException("No compatible video track found");
            connecting = true; client.request("PLAY", base, "Range: npt=0.000-\r\n"); connecting = false;
            playing = System.nanoTime();
            note("Connected in " + seconds(playing - opened) + " — " + videoLabel + " video" + (hasAudio ? " / AAC audio" : " (audio unavailable)"));
            long keepalive = System.nanoTime(); lastVideoFrame = keepalive;
            while (!closed.get()) {
                Protocol.Message message = client.read();
                if (message.channel >= 0) packet(message);
                else if (message.status < 200 || message.status >= 300) throw new IOException("RTSP keepalive rejected");
                long now = System.nanoTime();
                if (surface == null && now - opened > ATTACH_TIMEOUT) return; // The viewer never came.
                if (now - keepalive > 15_000_000_000L) { client.send("GET_PARAMETER", base, ""); keepalive = now; }
                if (now - lastVideoFrame > 10_000_000_000L) {
                    boolean fallback = false;
                    for (Decoder decoder : decoders.values()) if (decoder.video && decoder.videoDecoder != null) fallback = decoder.videoDecoder.fallbackAfterStall();
                    if (!fallback) throw new IOException("Video decoder stalled or keyframe missing; try the sub stream");
                    lastVideoFrame = now; listener().decoderFallback();
                }
            }
        } catch (Exception e) {
            if (!closed.get()) {
                String reason = Failures.describe(e, "Stream failed; verify the doorbell address, RTSP port and selected stream");
                Listener l = listener();
                l.status(reason, true);
                if (FailureRouting.setup(e, connecting)) l.connectionFailed(reason);
                else l.failed();
            }
        } finally {
            close();
            Surface view;
            synchronized (attachLock) { finished = true; view = surface; }
            for (Decoder decoder : decoders.values()) decoder.close(); decoders.clear();
            if (view != null) try { view.release(); } catch (RuntimeException ignored) {}
        }
    }
    private void packet(Protocol.Message message) throws Exception {
        Decoder decoder = decoders.get(message.channel); if (decoder == null) return;
        Protocol.Rtp p = new Protocol.Rtp(message.body);
        if (p.payloadType != decoder.track.payload) return;
        if (decoder.video) {
            if (firstPacket == 0) firstPacket = System.nanoTime();
            byte[] frame = "H265".equals(decoder.track.codec) ? h265.accept(p) : h264.accept(p);
            if (frame != null && firstKey == 0 && VideoParameters.keyframe(frame, "H265".equals(decoder.track.codec))) firstKey = System.nanoTime();
            if (frame != null) video(decoder, frame, p.timestamp);
        } else {
            // RFC 3640 AAC-hbr with 13-bit size and 3-bit index; no fragmentation.
            if (p.length < 4) throw new IOException("Truncated AAC access-unit header");
            int bits = Protocol.u16(p.data, p.offset);
            if (bits == 0 || bits % 16 != 0 || bits > 4096) throw new IOException("Unsupported AAC RTP header layout");
            int count = bits / 16, at = p.offset + 2 + bits / 8;
            if (at > p.offset + p.length) throw new IOException("Truncated AAC header block");
            for (int i = 0; i < count; i++) {
                int size = Protocol.u16(p.data, p.offset + 2 + i * 2) >>> 3;
                if (size == 0 || at + size > p.offset + p.length) throw new IOException("Fragmented AAC audio is not supported");
                decoder.input(Arrays.copyOfRange(p.data, at, at + size), (p.timestamp + i * 1024L) & 0xffffffffL); at += size;
            }
            if (at != p.offset + p.length) throw new IOException("Unsupported AAC packet trailer");
        }
    }
    /** Feeds the decoder once the view is here; until then holds the frames since the latest keyframe. */
    private void video(Decoder decoder, byte[] frame, long timestamp) throws Exception {
        Surface view = surface;
        if (view == null) {
            lastVideoFrame = System.nanoTime(); // Receiving, not stalled.
            boolean key = VideoParameters.keyframe(frame, "H265".equals(decoder.track.codec));
            if (key) { held.clear(); heldTimes.clear(); heldBytes = 0; }
            if (held.isEmpty() && !key) return;
            if (heldBytes + frame.length > 8 * 1024 * 1024) { held.clear(); heldTimes.clear(); heldBytes = 0; return; }
            held.add(frame); heldTimes.add(timestamp); heldBytes += frame.length;
            return;
        }
        if (!decoder.attached) {
            decoder.attached = true; decoder.videoDecoder.attach(view, listener());
            while (!held.isEmpty()) decoder.input(held.poll(), heldTimes.poll());
            heldBytes = 0;
        }
        decoder.input(frame, timestamp);
    }
    private void rendered() {
        lastVideoFrame = System.nanoTime();
        if (shownFirst) return;
        shownFirst = true;
        long key = firstKey == 0 ? lastVideoFrame : firstKey, packet = firstPacket == 0 ? key : firstPacket;
        listener().status("First image after " + seconds(lastVideoFrame - opened) + ": connected " + seconds(playing - opened)
            + ", first video " + seconds(packet - playing) + " later, keyframe " + seconds(key - packet)
            + " later, decoded " + seconds(lastVideoFrame - key) + " later", false);
    }
    static String seconds(long nanos) { return String.format(Locale.ROOT, "%.1f s", Math.max(0, nanos) / 1e9); }
    public void close() {
        closed.set(true);
        RtspClient client = rtsp; if (client != null) client.close();
    }
    private final class Decoder implements AutoCloseable {
        final Sdp.Track track; final boolean video;
        MediaCodec codec; AudioTrack audio; VideoDecoder videoDecoder;
        long epoch = -1; boolean started, attached;
        Decoder(Sdp.Track track, boolean video) throws Exception {
            this.track = track; this.video = video;
            try {
                MediaFormat format;
                if (video) {
                    videoDecoder = new VideoDecoder(track, closed, StreamPlayer.this::rendered);
                    return;
                } else {
                    if (track.channels < 1 || track.channels > 2 || track.rate < 8000 || track.rate > 96000
                            || !"13".equals(track.fmtp.get("sizelength")) || !"3".equals(track.fmtp.get("indexlength"))
                            || !"3".equals(track.fmtp.get("indexdeltalength"))) throw new IOException("Unsupported AAC stream parameters");
                    byte[] config = Sdp.hex(track.fmtp.get("config"));
                    if ((config[0] & 255) >>> 3 != 2) throw new IOException("Listening requires AAC-LC audio");
                    format = MediaFormat.createAudioFormat("audio/mp4a-latm", track.rate, track.channels);
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(config)); format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);
                }
                codec = MediaCodec.createDecoderByType("audio/mp4a-latm");
                codec.configure(format, null, null, 0); codec.start(); started = true;
            } catch (Exception e) { close(); throw e; }
        }
        void input(byte[] data, long timestamp) throws Exception {
            if (closed.get()) return;
            if (video) { videoDecoder.input(data, timestamp); return; }
            if (surface == null) return; // No sound before the viewer shows.
            drain();
            int index = codec.dequeueInputBuffer(10000);
            if (index < 0) return; // Drop rather than build latency without bounds.
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null || data.length > buffer.capacity()) throw new IOException("Encoded frame exceeds decoder input capacity");
            buffer.clear(); buffer.put(data);
            if (epoch < 0) epoch = timestamp;
            codec.queueInputBuffer(index, 0, data.length, ((timestamp - epoch) & 0xffffffffL) * 1000000L / track.rate, 0);
            drain();
        }
        void drain() throws Exception {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (;;) {
                int index = codec.dequeueOutputBuffer(info, 0);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = codec.getOutputFormat();
                    if (video) {
                        int width = format.getInteger(MediaFormat.KEY_WIDTH), height = format.getInteger(MediaFormat.KEY_HEIGHT);
                        if (format.containsKey("crop-right")) width = format.getInteger("crop-right") - format.getInteger("crop-left") + 1;
                        if (format.containsKey("crop-bottom")) height = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1;
                        listener().size(width, height);
                    } else {
                        if (audio != null) { audio.release(); audio = null; }
                        int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE), count = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (count != 1 && count != 2) throw new IOException("Unsupported decoded audio channel count");
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING) && format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT)
                            throw new IOException("Unsupported decoded PCM format");
                        int mask = count == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                        int minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
                        if (minimum <= 0) throw new IOException("Audio output configuration unavailable");
                        audio = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(Math.max(minimum, rate * count / 5)).setTransferMode(AudioTrack.MODE_STREAM).build(); audio.play();
                    }
                } else if (index >= 0) {
                    try {
                        if (video) lastVideoFrame = System.nanoTime();
                        else if (audio != null && info.size > 0) {
                            audio.setVolume(muted ? 0 : 1);
                            ByteBuffer pcm = codec.getOutputBuffer(index);
                            if (pcm != null) { pcm.position(info.offset); pcm.limit(info.offset + info.size); audio.write(pcm, info.size, AudioTrack.WRITE_NON_BLOCKING); }
                        }
                    } finally { codec.releaseOutputBuffer(index, video && !closed.get()); }
                }
            }
        }
        public void close() {
            if (videoDecoder != null) { videoDecoder.close(); videoDecoder = null; }
            if (codec != null) {
                try { if (started) codec.stop(); } catch (RuntimeException ignored) {}
                try { codec.release(); } catch (RuntimeException ignored) {} codec = null;
            }
            if (audio != null) {
                try { audio.pause(); audio.flush(); } catch (RuntimeException ignored) {}
                try { audio.release(); } catch (RuntimeException ignored) {} audio = null;
            }
        }
    }
}
