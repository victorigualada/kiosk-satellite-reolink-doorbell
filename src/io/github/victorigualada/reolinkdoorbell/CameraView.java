// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * One native view owns the doorbell stream, its audio, the talk channel and all their resource lifetimes.
 * Call-style controls: green phone opens the microphone, blue door opens the lock, red phone hangs up.
 */
final class CameraView extends FrameLayout implements TextureView.SurfaceTextureListener, Application.ActivityLifecycleCallbacks {
    private final Settings settings;
    private final Runnable closeAction;
    private final FrameLayout viewport;
    private final TextureView texture;
    private final TextView status;
    private static final int GREEN = 0xFF2E7D32, BLUE = 0xFF1565C0, RED = 0xFFC62828;
    private final ImageButton listen, talkButton, door, hangup;
    private KsTheme theme;
    private String lockState = "";
    private boolean lockBusy;
    private final MusicFocus music;
    private boolean statusError, audioInterrupted;
    private Talkback talk;
    private boolean talking;
    private final Application application;
    private volatile StreamPlayer player;
    private volatile boolean stopping;
    private boolean listeningMuted;
    private volatile boolean disposed;
    private final CallbackScope playbackCallbacks = new CallbackScope();
    private int videoWidth = 4, videoHeight = 3;
    private final IdleTimeout idleTimer;
    private final Runnable idleCheck = this::checkIdle;
    private final Runnable hideStatus = this::hideStatus;

    CameraView(Context context, KsTheme theme, Settings settings, Runnable closeAction, Runnable toggleLock) {
        super(context); this.theme = theme; this.settings = settings; this.closeAction = closeAction; listeningMuted = settings.startMuted;
        idleTimer = new IdleTimeout(settings.autoCloseSeconds, android.os.SystemClock.uptimeMillis());
        music = new MusicFocus(context, () -> { stopTalk(); audioInterrupted = true; listeningMuted = true; styleListen(); applyMute(); message("Audio interrupted — tap Listen to resume", false); });
        setBackgroundColor(Color.BLACK); setKeepScreenOn(true); setClickable(true);
        viewport = new FrameLayout(context); viewport.setClipChildren(true); addView(viewport, new LayoutParams(-1, -1, Gravity.CENTER));
        texture = new TextureView(context); viewport.addView(texture, new LayoutParams(-1, -1)); texture.setSurfaceTextureListener(this);
        status = new TextView(context); status.setVisibility(GONE); status.setPadding(dp(12), dp(8), dp(12), dp(8));
        LayoutParams statusPosition = new LayoutParams(-2, -2, ControlsLayout.corner(settings.controls, true));
        statusPosition.setMargins(dp(12), dp(12), dp(12), dp(12)); addView(status, statusPosition);
        LinearLayout bar = new LinearLayout(context); bar.setOrientation(ControlsLayout.vertical(settings.controls) ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        talkButton = button("Talk"); talkButton.setOnClickListener(v -> toggleTalk()); bar.addView(talkButton, cell(settings.controlSize, 12));
        door = button("Door"); door.setVisibility(settings.lockEntity.isEmpty() ? GONE : VISIBLE);
        door.setOnClickListener(v -> { if (!disposed && !stopping && !lockBusy && !lockState.isEmpty()) toggleLock.run(); }); bar.addView(door, cell(settings.controlSize, 12));
        hangup = button("Hang up"); hangup.setOnClickListener(v -> { stopTalk(); dispose(); closeAction.run(); }); bar.addView(hangup, cell(settings.controlSize, 12));
        LayoutParams barPosition = new LayoutParams(-2, -2, ControlsLayout.gravity(settings.controls));
        barPosition.setMargins(dp(24), dp(24), dp(24), dp(24)); addView(bar, barPosition);
        listen = button(listeningMuted ? "Listen" : "Mute"); listen.setOnClickListener(v -> {
            audioInterrupted = false; listeningMuted = !listeningMuted; styleListen(); applyMute();
        });
        LayoutParams listenPosition = new LayoutParams(dp(48), dp(48), ControlsLayout.corner(settings.controls, false));
        listenPosition.setMargins(dp(12), dp(12), dp(12), dp(12)); addView(listen, listenPosition);
        applyTheme(theme);
        Context app = context.getApplicationContext(); application = app instanceof Application ? (Application) app : null;
        if (application != null) application.registerActivityLifecycleCallbacks(this);
    }
    private ImageButton button(String label) {
        ImageButton b = new ImageButton(getContext()) {
            @Override public boolean performClick() { userActivity(); return super.performClick(); }
        }; b.setTag(label); b.setContentDescription(label);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE); b.setPadding(dp(14), dp(14), dp(14), dp(14));
        b.setMinimumWidth(0); b.setMinimumHeight(0); return b;
    }
    private LinearLayout.LayoutParams cell(int size, int margin) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(Math.max(48, size)), dp(Math.max(48, size))); p.setMargins(dp(margin), dp(margin), dp(margin), dp(margin)); return p; }
    /** KS's own Material Design Icons; Android's built-ins only on hosts too old to share them. */
    private void icon(ImageButton button, String name, String label, int fallback, int tint, int size) {
        Drawable image = null;
        try { image = theme.icon(name, "onSurface"); } catch (LinkageError ignored) { /* Older hosts have no icon set. */ }
        if (image == null) image = getContext().getDrawable(fallback).mutate();
        image.setTint(tint); image.setBounds(0, 0, dp(size), dp(size));
        button.setImageDrawable(image); button.setContentDescription(label);
        if (android.os.Build.VERSION.SDK_INT >= 26) button.setTooltipText(label);
    }
    private void round(ImageButton button, int color, boolean ring) {
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL); circle.setColor(color);
        if (ring) circle.setStroke(dp(3), Color.WHITE);
        button.setBackgroundTintList(null); button.setBackground(circle);
        int pad = dp(Math.max(48, settings.controlSize)) / 4; button.setPadding(pad, pad, pad, pad);
        button.setElevation(dp(4));
    }
    private void styleListen() {
        icon(listen, listeningMuted ? "mdi:volume-off" : "mdi:volume-high", listeningMuted ? "Listen" : "Mute doorbell audio",
            listeningMuted ? android.R.drawable.ic_lock_silent_mode : android.R.drawable.ic_lock_silent_mode_off, theme.color("onSurface"), 24);
    }
    private void styleTalk() {
        int size = Math.max(24, settings.controlSize / 2);
        icon(talkButton, talk != null ? "mdi:phone-in-talk" : "mdi:phone", talking ? "Talking — tap to stop" : talk != null ? "Connecting microphone — tap to cancel" : "Talk",
            android.R.drawable.ic_menu_call, Color.WHITE, size);
        round(talkButton, GREEN, talking); talkButton.setAlpha(talk != null && !talking ? .7f : 1f);
        icon(hangup, "mdi:phone-hangup", "Hang up", android.R.drawable.ic_menu_close_clear_cancel, Color.WHITE, size);
        round(hangup, RED, false);
    }
    void applyTheme(KsTheme next) {
        theme = next;
        listen.setBackgroundTintList(null); listen.setBackground(ThemeUi.surface(theme, "surfaceContainerHigh", 100));
        listen.setPadding(dp(12), dp(12), dp(12), dp(12));
        theme.styleText(status, 14, 400, "onSurface"); status.setBackground(theme.card());
        if (statusError) status.setTextColor(theme.color("error"));
        styleListen(); styleTalk(); styleLock();
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void userActivity() {
        if (idleTimer == null || disposed || stopping) return;
        idleTimer.interaction(android.os.SystemClock.uptimeMillis()); scheduleIdle();
    }
    private void scheduleIdle() {
        removeCallbacks(idleCheck);
        if (disposed || stopping || !isAttachedToWindow()) return;
        long remaining = idleTimer.remaining(android.os.SystemClock.uptimeMillis(), false);
        if (remaining >= 0) postDelayed(idleCheck, remaining);
    }
    private void checkIdle() {
        if (disposed || stopping) return;
        if (talk != null) { userActivity(); return; } // Never hang up on a conversation.
        long remaining = idleTimer.remaining(android.os.SystemClock.uptimeMillis(), false);
        if (remaining == 0) { dispose(); closeAction.run(); }
        else if (remaining > 0) postDelayed(idleCheck, remaining);
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); userActivity(); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) userActivity();
        return super.dispatchTouchEvent(event);
    }
    private void showMessage(String text, boolean error) {
        if (disposed) return;
        statusError = error; status.setText(text); status.setTextColor(theme.color(error ? "error" : "onSurface")); status.setVisibility(VISIBLE);
        if (!error) { removeCallbacks(hideStatus); postDelayed(hideStatus, settings.diagnostics ? 10000 : 4000); }
    }
    private void hideStatus() { if (!statusError) status.setVisibility(GONE); }
    private void message(String text, boolean error) { post(() -> showMessage(text, error)); }
    void showError(String text) { message(text, true); }
    void lockStatus(String state, boolean busy) { post(() -> { if (!disposed) { lockState = state; lockBusy = busy; styleLock(); } }); }
    private void styleLock() {
        if (settings.lockEntity.isEmpty()) return;
        boolean locked = "locked".equals(lockState), open = "unlocked".equals(lockState);
        icon(door, locked ? "mdi:door-closed-lock" : open ? "mdi:door-open" : "mdi:door-closed",
            locked ? "Locked — tap to open the door" : open ? "Open — tap to lock the door" : "Door state unknown",
            android.R.drawable.ic_lock_lock, Color.WHITE, Math.max(24, settings.controlSize / 2));
        round(door, BLUE, open);
        boolean usable = (locked || open) && !lockBusy && !disposed && !stopping;
        door.setEnabled(usable); door.setAlpha(usable ? 1f : .55f);
    }
    private void toggleTalk() {
        if (disposed || stopping) return;
        if (talk != null) { stopTalk(); return; }
        if (!settings.talkEnabled) { showMessage("Two-way audio is turned off in plugin settings", false); return; }
        final Talkback[] owner = new Talkback[1];
        Talkback next = new Talkback(getContext(), settings, new Talkback.Listener() {
            public void status(String text, boolean error) { post(() -> { if (talk == owner[0]) showMessage(text, error); }); }
            public void microphoneActive() { post(() -> { if (talk == owner[0] && !disposed) { talking = true; styleTalk(); applyMute(); } }); }
            public void finished() { post(() -> { if (talk == owner[0]) { talk = null; talking = false; if (!disposed) { styleTalk(); applyMute(); } } }); }
        });
        owner[0] = next; talk = next; styleTalk(); next.start();
    }
    private void stopTalk() {
        Talkback current = talk; talk = null; talking = false;
        if (current != null) current.close();
        if (!disposed) { styleTalk(); applyMute(); }
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); post(this::layoutImage); }
    private void layoutImage() {
        if (disposed || getWidth() == 0 || getHeight() == 0) return;
        int w = getWidth(), h = getHeight(); float aspect = Geometry.aspect(settings.viewport);
        if (aspect > 0) { if ((float) w / h > aspect) w = Math.round(h * aspect); else h = Math.round(w / aspect); }
        viewport.setLayoutParams(new LayoutParams(w, h, Gravity.CENTER));
        float[] r = Geometry.image(w, h, videoWidth, videoHeight, settings.sizing, settings.horizontal, settings.vertical);
        LayoutParams p = new LayoutParams(Math.max(1, Math.round(r[2])), Math.max(1, Math.round(r[3])));
        p.leftMargin = Math.round(r[0]); p.topMargin = Math.round(r[1]); p.gravity = Gravity.TOP | Gravity.LEFT; texture.setLayoutParams(p);
    }
    private void applyMute() {
        if (player != null) player.setMuted(listeningMuted);
        if (disposed || stopping || (player == null && !talking)) music.close(); else music.update(!listeningMuted && !audioInterrupted && player != null, talking);
    }
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (disposed || stopping) return;
        final long token = playbackCallbacks.next();
        StreamPlayer previous = player; player = null; if (previous != null) previous.close();
        StreamPlayer next = new StreamPlayer(settings, new Surface(st), new StreamPlayer.Listener() {
            // Connection, codec and timing notes are diagnostics; errors always show.
            public void status(String text, boolean error) { if (error || settings.diagnostics) postPlayback(token, () -> showMessage(text, error)); }
            public void size(int width, int height) { postPlayback(token, () -> { videoWidth = width; videoHeight = height; layoutImage(); }); }
            public void failed() { postPlayback(token, () -> { playbackCallbacks.next(); if (player != null) { player.close(); player = null; } music.close(); }); }
        });
        player = next; if (stopping) next.close(); applyMute(); next.start(); layoutImage();
    }
    private void postPlayback(long token, Runnable action) { post(() -> { if (!disposed && !stopping && playbackCallbacks.current(token)) action.run(); }); }
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
    public void onSurfaceTextureUpdated(SurfaceTexture st) {}
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { playbackCallbacks.next(); if (player != null) { player.close(); player = null; } music.close(); return true; }
    @Override protected void onDetachedFromWindow() { dispose(); super.onDetachedFromWindow(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (!focus && music != null) { stopTalk(); audioInterrupted = true; listeningMuted = true; styleListen(); music.close(); applyMute(); } }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != View.VISIBLE && status != null && !disposed) { dispose(); closeAction.run(); }
    }
    void dispose() {
        if (disposed) return; disposed = true; stopTransports();
        removeCallbacks(idleCheck); removeCallbacks(hideStatus);
        if (player != null) { player.close(); player = null; }
        if (application != null) application.unregisterActivityLifecycleCallbacks(this);
        setKeepScreenOn(false);
    }
    // Safe from the plugin worker: does not wait for the UI queue.
    void stopTransports() {
        stopping = true;
        playbackCallbacks.next();
        music.close();
        Talkback t = talk; if (t != null) t.close();
        StreamPlayer p = player; if (p != null) p.close();
    }
    public void onActivityPaused(Activity activity) { stopTransports(); post(() -> { if (!disposed) { dispose(); closeAction.run(); } }); }
    public void onActivityStopped(Activity activity) { onActivityPaused(activity); }
    public void onActivityDestroyed(Activity activity) { onActivityPaused(activity); }
    public void onActivityCreated(Activity activity, Bundle state) {}
    public void onActivityStarted(Activity activity) {}
    public void onActivityResumed(Activity activity) {}
    public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
}
