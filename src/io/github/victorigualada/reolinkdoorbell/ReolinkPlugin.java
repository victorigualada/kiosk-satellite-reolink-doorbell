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
    private ReolinkApi validation;
    // Discovery: the settings values last applied, so a found address can be merged in, and the cameras last found.
    private final Object settingsLock = new Object();
    private Map<String, Object> values = Collections.emptyMap();
    private volatile List<Discovery.Device> devices = Collections.emptyList();
    /**
     * The host runs callbacks one at a time; the discovery and relocate threads and the viewer's buttons take this
     * lock for every change to plugin state, so they never interleave with a callback. Held only briefly: never
     * around a network search.
     */
    private final Object state = new Object();
    private boolean searching, relocating;
    /** A Refresh run waiting for the current search; null once reported or cancelled. */
    private String refreshRun;

    public void start(PluginHost host, Map<String, Object> values) { synchronized (state) { begin(host, values); } }
    private void begin(PluginHost host, Map<String, Object> values) {
        this.host = host;
        locks = new HaLocks(host, new HaLocks.Listener() {
            public void changed(String state, boolean busy) { CameraView current = view; if (current != null) current.lockStatus(state, busy); }
            public void error(String message) { CameraView current = view; if (current != null) current.showError(message); }
        });
        apply(values);
        discover(null);
        Thread codecs = new Thread(VideoDecoder::codecs, "reolink-codecs"); codecs.setDaemon(true); codecs.start();
    }
    public void configure(Map<String, Object> values) {
        synchronized (state) { Settings before = settings; hide(); apply(values); follow(before); }
    }
    private void apply(Map<String, Object> values) {
        synchronized (state) { applyLocked(values); }
    }
    private void applyLocked(Map<String, Object> values) {
        synchronized (settingsLock) { this.values = new HashMap<>(values); }
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
    /**
     * A doorbell picked from the list: use the address it was last found at. An address the person has just typed
     * wins over that list, which may be out of date: the pick is cleared and learnt again from that address.
     */
    private void follow(Settings before) {
        Settings s = settings;
        if (s.doorbell.isEmpty()) return;
        if (!s.host.isEmpty() && !s.host.equals(before.host) && s.doorbell.equals(before.doorbell)) {
            adopt(Collections.singletonMap("doorbell", ""));
            discover(null);
            return;
        }
        Discovery.Device d = find(devices, s.doorbell);
        if (d == null) discover(null);
        else if (!d.host.equals(s.host)) adopt(Collections.singletonMap("host", d.host));
    }
    private static Discovery.Device find(List<Discovery.Device> list, String id) {
        for (Discovery.Device d : list) if (d.id.equals(id)) return d;
        return null;
    }
    /** Saves settings the plugin worked out, and applies them here too: updateSettings does not call configure. */
    private void adopt(Map<String, Object> updates) {
        synchronized (state) { adoptLocked(updates); }
    }
    private void adoptLocked(Map<String, Object> updates) {
        PluginHost h = host; if (h == null) return;
        try { h.updateSettings(updates); } catch (RuntimeException | LinkageError ignored) { /* Older hosts: this session only. */ }
        Map<String, Object> merged;
        synchronized (settingsLock) { merged = new HashMap<>(values); merged.putAll(updates); }
        apply(merged);
    }
    /**
     * Searches the network for ONVIF cameras and lists them in the Doorbell picker. With nothing set up yet it picks
     * the only doorbell it finds; with a doorbell picked it follows it to a new address; with only an address typed
     * it learns which device that is, so it can follow it later. {@code run} is a Refresh run, or null on its own.
     */
    private void discover(String run) {
        synchronized (state) {
            if (run != null) {
                refreshRun = run;
                if (!report(run, "running", "Searching the network for doorbells")) { refreshRun = null; return; }
            }
            // One search at a time: a Refresh during the automatic one gets that search's result.
            if (searching) return;
            searching = true;
        }
        final boolean automatic = run == null;
        Thread worker = new Thread(() -> {
            String state = "error", message = "Could not search the network; type the doorbell's address instead";
            try {
                List<Discovery.Device> found = Discovery.search(3000);
                String note = found(found, automatic);
                state = "success";
                message = found.isEmpty()
                    ? "No camera answered. Check that ONVIF is on in the Reolink app (Network > Advanced > Server settings), or type the address."
                    : "Found " + found.size() + (found.size() == 1 ? " camera." : " cameras.") + note;
            } catch (Exception e) {
                message = Failures.describe(e, message);
            } finally {
                String waiting;
                synchronized (this.state) { waiting = refreshRun; refreshRun = null; searching = false; }
                report(waiting, state, message);
            }
        }, "reolink-discovery");
        worker.setDaemon(true); worker.start();
    }
    /** Lists what a search found and picks, follows or learns the doorbell from it. Returns a note for the Refresh result. */
    private String found(List<Discovery.Device> found, boolean automatic) {
        synchronized (state) {
            PluginHost h = host; if (h == null) return "";
            devices = found;
            String[] ids = new String[found.size()], labels = new String[found.size()];
            for (int i = 0; i < ids.length; i++) { ids[i] = found.get(i).id; labels[i] = found.get(i).label(); }
            try { h.setSettingChoices("doorbell", ids, labels); } catch (RuntimeException | LinkageError ignored) { /* Older hosts show a text field. */ }
            // A Refresh that was cancelled only lists what it found; it saves nothing.
            if (!automatic && refreshRun == null) return "";
            Settings s = settings; // Read now, not when the search began: the person may have typed an address meanwhile.
            if (s.doorbell.isEmpty() && s.host.isEmpty()) {
                Discovery.Device d = Discovery.obvious(found);
                if (d != null) {
                    Map<String, Object> pick = new HashMap<>(); pick.put("doorbell", d.id); pick.put("host", d.host); adopt(pick);
                    return " Picked " + d.label() + ".";
                }
            } else if (!s.doorbell.isEmpty()) {
                Discovery.Device d = find(found, s.doorbell);
                if (d != null && !d.host.equals(s.host)) { adopt(Collections.singletonMap("host", d.host)); return " The doorbell is now at " + d.host + "; address updated."; }
            } else {
                for (Discovery.Device d : found) if (d.host.equals(s.host)) { adopt(Collections.singletonMap("doorbell", d.id)); break; }
            }
            return "";
        }
    }
    /**
     * Nothing answered at the configured address. Look for the doorbell on the network: the picked one, or the only
     * doorbell there. If it moved, save its new address and open the viewer again; once per opening.
     */
    private void relocate(int token) {
        final Settings s;
        synchronized (state) {
            if (relocating || generation.get() != token) return;
            relocating = true;
            s = settings;
            CameraView v = view; if (v != null) v.showError("Nothing answers at " + s.host + "; looking for the doorbell on the network");
        }
        Thread worker = new Thread(() -> {
            try {
                List<Discovery.Device> found = Discovery.search(3000);
                synchronized (state) {
                    devices = found;
                    // Closed, reopened or reconfigured meanwhile: this search is out of date.
                    if (generation.get() != token || settings != s) return;
                    CameraView current = view;
                    Discovery.Device there = null;
                    for (Discovery.Device d : found) if (d.host.equals(s.host)) there = d;
                    if (!s.doorbell.isEmpty()) {
                        Discovery.Device d = find(found, s.doorbell);
                        if (d != null && !d.host.equals(s.host)) {
                            Map<String, Object> moved = new HashMap<>(); moved.put("host", d.host); moved.put("doorbell", d.id);
                            adopt(moved);
                            if (generation.get() == token) show();
                            return;
                        }
                        if (current != null) current.showError(d == null
                            ? "The doorbell was not found on the network; check that it is powered and that ONVIF is on, or set its address"
                            : "The doorbell is still at " + s.host + " but did not answer; check that RTSP is on");
                        return;
                    }
                    // Only an address typed: never swap it for another device on its own, just say what is there.
                    Discovery.Device other = Discovery.obvious(found);
                    if (current != null) current.showError(there != null
                        ? "The doorbell is still at " + s.host + " but did not answer; check that RTSP is on"
                        : other != null
                            ? "Nothing answers at " + s.host + ". Found " + other.label() + "; pick it under Doorbell in plugin settings"
                            : "The doorbell was not found on the network; check that it is powered and that ONVIF is on, or set its address");
                }
            } catch (Exception e) {
                CameraView current = view; if (current != null) current.showError(Failures.describe(e, "Could not search the network for the doorbell"));
            } finally { synchronized (state) { relocating = false; } }
        }, "reolink-relocate");
        worker.setDaemon(true); worker.start();
    }
    private void watchVisitor(String entity) {
        PluginHost h = host; if (h == null || entity.equals(watchedVisitor)) return;
        if (!watchedVisitor.isEmpty()) try { h.unsubscribe("ha.entity." + watchedVisitor); } catch (RuntimeException ignored) {}
        watchedVisitor = ""; visitor.reset();
        if (entity.isEmpty()) return;
        try { h.subscribe("ha.entity." + entity); watchedVisitor = entity; }
        catch (RuntimeException e) { status("Home Assistant entities are unavailable on this kiosk", true); }
    }
    public void execute(String command, Map<String, Object> arguments) { synchronized (state) { run(command); } }
    private void run(String command) {
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
    private void show() { synchronized (state) { showLocked(); } }
    private void showLocked() {
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
                    () -> { synchronized (state) { if (generation.get() == token && view == owner[0]) hide(); } },
                    () -> { if (generation.get() == token && view == owner[0]) locks.toggle(); },
                    () -> { if (generation.get() == token && view == owner[0]) relocate(token); });
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
    private void hide() { synchronized (state) { hideLocked(); } }
    private void hideLocked() {
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
    /** Returns false once the host says the run was cancelled, replaced or timed out. */
    private boolean report(String run, String state, String message) {
        PluginHost h = host; if (h == null || run == null) return false;
        try { return h.setupResult(run, state, DisplayText.limit(message, 1000)); } catch (RuntimeException | LinkageError ignored) { return true; }
    }
    private void cancelValidation() {
        synchronized (validationLock) { ReolinkApi old = validation; validation = null; validationRun = ""; if (old != null) old.close(); }
    }
    private void status(String message, boolean error) {
        PluginHost h = host; if (h == null) return;
        try { h.status(DisplayText.limit(message, 1000), error); } catch (RuntimeException ignored) {}
    }
    public void onEvent(String event, Map<String, Object> payload) { synchronized (state) { handle(event, payload); } }
    private void handle(String event, Map<String, Object> payload) {
        if (!watchedVisitor.isEmpty() && event.equals("ks.ha.entity." + watchedVisitor)) {
            if (visitor.observe(payload, System.currentTimeMillis())) ring();
            return;
        }
        if ("settings.button".equals(event) && "validateConnection".equals(payload.get("id")) && payload.get("run") instanceof String) {
            validate((String) payload.get("run")); return;
        }
        if ("settings.choices".equals(event) && "doorbell".equals(payload.get("key")) && payload.get("run") instanceof String) {
            discover((String) payload.get("run")); return;
        }
        if ("settings.cancel".equals(event)) {
            synchronized (validationLock) { if (Objects.equals(validationRun, payload.get("run"))) cancelValidation(); }
            if (refreshRun != null && refreshRun.equals(payload.get("run"))) refreshRun = null;
            return;
        }
        if (locks != null) locks.event(event, payload);
        if ("overlay.closed".equals(event) && "camera".equals(payload.get("key")) && view != null) hide();
    }
    public void stop() { synchronized (state) { end(); } }
    private void end() {
        // The SDK revokes host access before stop. Resource cleanup must not call it.
        generation.incrementAndGet(); host = null; watchedVisitor = "";
        cancelValidation();
        if (locks != null) locks.stop();
        StreamPlayer pending = warming; warming = null; if (pending != null) pending.close();
        CameraView current = view; view = null; if (current != null) { current.stopTransports(); current.post(current::dispose); }
    }
}
