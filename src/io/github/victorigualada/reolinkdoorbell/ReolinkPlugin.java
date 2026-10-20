// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

import android.content.Context;
import android.view.View;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import me.jxl.kiosk.plugins.*;

public final class ReolinkPlugin implements KioskPlugin {
    static final String RING_TRIGGER = "ring";
    private volatile PluginHost host;
    private volatile Settings settings = new Settings(Collections.emptyMap());
    private volatile CameraView view;
    /** A stream started by show() that no view has taken yet. */
    private volatile StreamPlayer warming;
    private HaLocks locks;
    private String watchedVisitor = "";
    private final VisitorWatch visitor = new VisitorWatch(5000);
    private final AtomicInteger generation = new AtomicInteger();
    private final Object validationLock = new Object();
    private String validationRun = "";
    private AutoCloseable validation;

    public void start(PluginHost host, Map<String, Object> values) {
        this.host = host;
        locks = new HaLocks(host, new HaLocks.Listener() {
            public void changed(String state, boolean busy) { CameraView current = view; if (current != null) current.lockStatus(state, busy); }
            public void error(String message) { CameraView current = view; if (current != null) current.showError(message); }
        });
        apply(values);
        Thread codecs = new Thread(VideoDecoder::codecs, "reolink-codecs"); codecs.setDaemon(true); codecs.start();
    }
    public void configure(Map<String, Object> values) { hide(); apply(values); }
    private void apply(Map<String, Object> values) {
        Settings next;
        try { next = new Settings(values); }
        catch (IllegalArgumentException e) { status(Failures.describe(e, "Invalid settings"), true); next = new Settings(Collections.emptyMap()); }
        settings = next;
        locks.select(next.lockEntity);
        watchVisitor(next.visitorEntity);
        if (next.host.isEmpty() || next.username.isEmpty()) status("Enter the doorbell address, username and password", false);
        else if (next.visitorEntity.isEmpty()) status("Ready. Choose the visitor sensor to open on a ring", false);
        else status("Ready. Opens when " + next.visitorEntity + " turns on", false);
    }
    private void watchVisitor(String entity) {
        PluginHost h = host; if (h == null || entity.equals(watchedVisitor)) return;
        if (!watchedVisitor.isEmpty()) try { h.unsubscribe("ha.entity." + watchedVisitor); } catch (RuntimeException ignored) {}
        watchedVisitor = ""; visitor.reset();
        if (entity.isEmpty()) return;
        try { h.subscribe("ha.entity." + entity); watchedVisitor = entity; }
        catch (RuntimeException e) { status("Home Assistant entities are unavailable on this kiosk", true); }
    }
    public void execute(String command, Map<String, Object> arguments) {
        if ("show".equals(command)) show();
        else if ("hide".equals(command)) hide();
        else if (RING_TRIGGER.equals(command)) ring();
        else throw new IllegalArgumentException("Unknown Reolink plugin command");
    }
    /** What a doorbell press does: wake the kiosk, fire the gesture trigger and open the viewer. */
    private void ring() {
        PluginHost h = host; if (h == null) return;
        Settings snapshot = settings;
        try { h.fireTrigger(RING_TRIGGER); } catch (RuntimeException | LinkageError ignored) { /* Older hosts have no triggers. */ }
        if (snapshot.wakeScreen) {
            try { h.executeCommand("stopScreensaver", Collections.emptyMap(), (ok, data, error) -> {}); }
            catch (RuntimeException ignored) { /* host.control missing on this host. */ }
        }
        if (snapshot.autoOpen) show();
    }
    private void show() {
        Settings snapshot = settings;
        try { snapshot.requireConnection(); } catch (IllegalArgumentException e) { status(Failures.describe(e, "Configure the doorbell first"), true); return; }
        hide();
        final int token = generation.get(); PluginHost h = host; if (h == null) return;
        // Connect now, while the screen wakes and the overlay opens; the view picks the stream up.
        StreamPlayer early = new StreamPlayer(snapshot, null, null); early.start();
        warming = early;
        OverlaySpec spec = OverlaySpec.fullScreen().closeOnBack(true);
        try { spec = spec.onTop(true); } catch (LinkageError ignored) { /* Older hosts draw it under the screensaver. */ }
        h.showOverlay("camera", spec, new OverlayFactory() {
            public View create(Context context, KsTheme theme) {
                final CameraView[] owner = new CameraView[1];
                StreamPlayer stream = early == warming ? early : null; if (stream != null) warming = null;
                CameraView created = new CameraView(context, theme, snapshot, stream,
                    () -> { if (generation.get() == token && view == owner[0]) hide(); },
                    () -> { if (generation.get() == token && view == owner[0]) locks.toggle(); });
                owner[0] = created;
                if (generation.get() != token || host == null) { created.dispose(); return created; }
                view = created; locks.refresh(); return created;
            }
            public void onDestroy(View destroyed) {
                if (destroyed instanceof CameraView) ((CameraView) destroyed).dispose();
                if (view == destroyed) { view = null; locks.cancelAction(); }
            }
            public void onThemeChanged(View changed, KsTheme theme) { if (changed instanceof CameraView) ((CameraView) changed).applyTheme(theme); }
        });
    }
    private void hide() {
        generation.incrementAndGet(); CameraView current = view; view = null;
        StreamPlayer pending = warming; warming = null; if (pending != null) pending.close();
        if (locks != null) locks.cancelAction();
        if (current != null) { current.stopTransports(); current.post(current::dispose); }
        PluginHost h = host; if (h != null) h.hideOverlay("camera");
    }
    private void validate(String run) {
        PluginHost h = host; if (h == null) return;
        Settings snapshot = settings;
        ReolinkApi api;
        try { api = new ReolinkApi(snapshot); }
        catch (IllegalArgumentException e) { report(run, "error", Failures.describe(e, "Configure the doorbell first")); return; }
        synchronized (validationLock) { cancelValidation(); validationRun = run; validation = api; }
        report(run, "running", "Connecting to " + snapshot.host);
        Thread worker = new Thread(() -> {
            try {
                ReolinkApi.DeviceInfo info = api.deviceInfo();
                report(run, "success", info.summary());
            } catch (Exception e) {
                report(run, "error", Failures.describe(e, "Could not reach the doorbell HTTP API; check the address, HTTP port and that HTTP is enabled"));
            } finally {
                synchronized (validationLock) { if (validation == api) { validation = null; validationRun = ""; } }
            }
        }, "reolink-validate");
        worker.setDaemon(true); worker.start();
    }
    /**
     * Times how soon video starts over RTSP and over HTTP-FLV, each twice and in both orders with pauses between,
     * so a stream the doorbell still has warm from the previous try does not favour either one.
     */
    private void measure(String run) {
        Settings snapshot = settings;
        StartupProbe probe;
        try { probe = new StartupProbe(snapshot); }
        catch (IllegalArgumentException e) { report(run, "error", Failures.describe(e, "Configure the doorbell first")); return; }
        synchronized (validationLock) { cancelValidation(); validationRun = run; validation = probe; }
        Thread worker = new Thread(() -> {
            try {
                StringBuilder out = new StringBuilder();
                String[] order = {"rtsp", "flv", "flv", "rtsp"};
                for (int i = 0; i < order.length; i++) {
                    report(run, "running", "Measuring " + (order[i].equals("rtsp") ? "RTSP" : "HTTP-FLV") + " (" + (i + 1) + " of 4, about a minute)");
                    for (int wait = 0; i > 0 && wait < 20 && !probe.cancelled(); wait++) Thread.sleep(500);
                    if (probe.cancelled()) return;
                    StartupProbe.Result r = order[i].equals("rtsp") ? probe.rtsp() : probe.flv();
                    out.append(r.describe(order[i].equals("rtsp") ? "RTSP" : "HTTP-FLV")).append('\n');
                }
                report(run, "success", out.toString().trim());
            } catch (InterruptedException e) {
                report(run, "error", "Measurement cancelled");
            } finally {
                synchronized (validationLock) { if (validation == probe) { validation = null; validationRun = ""; } }
            }
        }, "reolink-measure");
        worker.setDaemon(true); worker.start();
    }
    private void report(String run, String state, String message) {
        PluginHost h = host; if (h == null) return;
        try { h.setupResult(run, state, DisplayText.limit(message, 1000)); } catch (RuntimeException | LinkageError ignored) {}
    }
    private void cancelValidation() {
        synchronized (validationLock) {
            AutoCloseable old = validation; validation = null; validationRun = "";
            if (old != null) try { old.close(); } catch (Exception ignored) {}
        }
    }
    private void status(String message, boolean error) {
        PluginHost h = host; if (h == null) return;
        try { h.status(DisplayText.limit(message, 1000), error); } catch (RuntimeException ignored) {}
    }
    public void onEvent(String event, Map<String, Object> payload) {
        if (!watchedVisitor.isEmpty() && event.equals("ks.ha.entity." + watchedVisitor)) {
            if (visitor.observe(payload, System.currentTimeMillis())) ring();
            return;
        }
        if ("settings.button".equals(event) && "validateConnection".equals(payload.get("id")) && payload.get("run") instanceof String) {
            validate((String) payload.get("run")); return;
        }
        if ("settings.button".equals(event) && "measureStartup".equals(payload.get("id")) && payload.get("run") instanceof String) {
            measure((String) payload.get("run")); return;
        }
        if ("settings.cancel".equals(event)) {
            synchronized (validationLock) { if (Objects.equals(validationRun, payload.get("run"))) cancelValidation(); }
            return;
        }
        if (locks != null) locks.event(event, payload);
        if ("overlay.closed".equals(event) && "camera".equals(payload.get("key")) && view != null) hide();
    }
    public void stop() {
        // The SDK revokes host access before stop. Resource cleanup must not call it.
        generation.incrementAndGet(); host = null; watchedVisitor = "";
        cancelValidation();
        if (locks != null) locks.stop();
        StreamPlayer pending = warming; warming = null; if (pending != null) pending.close();
        CameraView current = view; view = null; if (current != null) { current.stopTransports(); current.post(current::dispose); }
    }
}
