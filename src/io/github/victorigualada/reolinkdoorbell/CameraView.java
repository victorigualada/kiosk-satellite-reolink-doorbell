// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;
import me.jxl.kiosk.plugins.KsTheme;

/** One native view owns the doorbell stream, its audio and all their resource lifetimes. */
final class CameraView extends FrameLayout implements TextureView.SurfaceTextureListener, Application.ActivityLifecycleCallbacks {
    private final Settings settings;
    private final Runnable closeAction;
    private final FrameLayout viewport;
    private final TextureView texture;
    private final TextView status;
    private final ImageButton listen, doorLock;
    private KsTheme theme;
    private final List<ImageButton> controls = new ArrayList<>();
    private String lockState = "";
    private boolean lockBusy;
    private final MusicFocus music;
    private boolean statusError, audioInterrupted;
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
        music = new MusicFocus(context, () -> { audioInterrupted = true; listeningMuted = true; styleListen(); applyMute(); message("Audio interrupted — tap Listen to resume", false); });
        setBackgroundColor(Color.BLACK); setKeepScreenOn(true); setClickable(true);
        viewport = new FrameLayout(context); viewport.setClipChildren(true); addView(viewport, new LayoutParams(-1, -1, Gravity.CENTER));
        texture = new TextureView(context); viewport.addView(texture, new LayoutParams(-1, -1)); texture.setSurfaceTextureListener(this);
        status = new TextView(context); status.setVisibility(GONE); status.setPadding(dp(12), dp(8), dp(12), dp(8));
        LayoutParams statusPosition = new LayoutParams(-2, -2, "Top".equals(settings.controls) ? Gravity.BOTTOM | Gravity.START : Gravity.TOP | Gravity.START);
        statusPosition.setMargins(dp(12), dp(12), dp(12), dp(12)); addView(status, statusPosition);
        LinearLayout buttons = new LinearLayout(context); buttons.setOrientation(ControlsLayout.vertical(settings.controls) ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        ImageButton back = button("Close"); back.setOnClickListener(v -> { dispose(); closeAction.run(); }); buttons.addView(back, cell());
        listen = button(listeningMuted ? "Listen" : "Mute"); listen.setOnClickListener(v -> {
            audioInterrupted = false; listeningMuted = !listeningMuted; styleListen(); applyMute();
        }); buttons.addView(listen, cell());
        doorLock = button("Door lock"); doorLock.setVisibility(GONE);
        doorLock.setOnClickListener(v -> { if (!disposed && !stopping && !lockBusy && !lockState.isEmpty()) toggleLock.run(); }); buttons.addView(doorLock, cell());
        LayoutParams buttonPosition = new LayoutParams(-2, -2, ControlsLayout.gravity(settings.controls));
        buttonPosition.setMargins(dp(12), dp(12), dp(12), dp(12)); addView(buttons, buttonPosition);
        applyTheme(theme);
        Context app = context.getApplicationContext(); application = app instanceof Application ? (Application) app : null;
        if (application != null) application.registerActivityLifecycleCallbacks(this);
    }
    private ImageButton button(String label) {
        ImageButton b = new ImageButton(getContext()) {
            @Override public boolean performClick() { userActivity(); return super.performClick(); }
        }; b.setTag(label); b.setContentDescription(label);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE); b.setPadding(dp(14), dp(14), dp(14), dp(14));
        b.setMinimumWidth(0); b.setMinimumHeight(0); controls.add(b); return b;
    }
    private LinearLayout.LayoutParams cell() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(Math.max(48, settings.controlSize)), dp(Math.max(48, settings.controlSize))); p.setMargins(dp(4), dp(4), dp(4), dp(4)); return p; }
    private void icon(ImageButton button, String name, String label, int fallback) {
        Drawable image = null;
        try { image = theme.icon(name, "onSurface"); } catch (LinkageError ignored) { /* Older hosts have no icon set. */ }
        if (image == null) image = getContext().getDrawable(fallback).mutate();
        image.setTint(theme.color("onSurface")); image.setBounds(0, 0, dp(26), dp(26));
        button.setImageDrawable(image); button.setContentDescription(label);
        if (android.os.Build.VERSION.SDK_INT >= 26) button.setTooltipText(label);
    }
    private void styleListen() { icon(listen, listeningMuted ? "mdi:volume-off" : "mdi:volume-high", listeningMuted ? "Listen" : "Mute doorbell audio", android.R.drawable.ic_lock_silent_mode_off); }
    void applyTheme(KsTheme next) {
        theme = next;
        for (ImageButton b : controls) {
            b.setBackgroundTintList(null); b.setBackground(ThemeUi.surface(theme, "surfaceContainerHigh", 100));
            if ("Close".equals(b.getTag())) icon(b, "mdi:close", "Close", android.R.drawable.ic_menu_close_clear_cancel);
        }
        theme.styleText(status, 14, 400, "onSurface"); status.setBackground(theme.card());
        if (statusError) status.setTextColor(theme.color("error"));
        styleListen(); styleLock();
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
        if (!error) { removeCallbacks(hideStatus); postDelayed(hideStatus, 4000); }
    }
    private void hideStatus() { if (!statusError) status.setVisibility(GONE); }
    private void message(String text, boolean error) { post(() -> showMessage(text, error)); }
    void showError(String text) { message(text, true); }
    void lockStatus(String state, boolean busy) { post(() -> { if (!disposed) { lockState = state; lockBusy = busy; styleLock(); } }); }
    private void colorButton(ImageButton button, boolean red) {
        button.setBackgroundTintList(ColorStateList.valueOf(red ? 0xFFC62828 : 0xFF2E7D32));
        if (button.getDrawable() != null) button.getDrawable().setTint(Color.WHITE);
    }
    private void styleLock() {
        doorLock.setVisibility(!settings.lockEntity.isEmpty() && ("locked".equals(lockState) || "unlocked".equals(lockState)) ? VISIBLE : GONE);
        icon(doorLock, "locked".equals(lockState) ? "mdi:lock" : "mdi:lock-open", "locked".equals(lockState) ? "Locked — tap to unlock" : "Unlocked — tap to lock", android.R.drawable.ic_lock_lock);
        colorButton(doorLock, "unlocked".equals(lockState)); doorLock.setEnabled(!lockBusy && !disposed && !stopping); doorLock.setAlpha(lockBusy ? .55f : 1f);
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
        if (disposed || stopping || audioInterrupted || player == null) music.close(); else music.update(!listeningMuted, false);
    }
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (disposed || stopping) return;
        final long token = playbackCallbacks.next();
        StreamPlayer previous = player; player = null; if (previous != null) previous.close();
        StreamPlayer next = new StreamPlayer(settings, new Surface(st), new StreamPlayer.Listener() {
            public void status(String text, boolean error) { postPlayback(token, () -> showMessage(text, error)); }
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
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (!focus && music != null) { audioInterrupted = true; listeningMuted = true; styleListen(); music.close(); applyMute(); } }
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
