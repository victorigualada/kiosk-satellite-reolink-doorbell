// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

import java.util.Map;

/**
 * Turns Home Assistant snapshots of the doorbell's visitor binary sensor into ring events.
 * Only an observed off to on change rings: the initial snapshot and states seen after a reconnect never do.
 */
final class VisitorWatch {
    private Boolean last;
    private long lastRing = Long.MIN_VALUE / 2;
    private final long cooldownMillis;

    VisitorWatch(long cooldownMillis) { this.cooldownMillis = cooldownMillis; }

    synchronized void reset() { last = null; }

    /** Returns true when this snapshot is a fresh press. */
    synchronized boolean observe(Map<String, Object> snapshot, long nowMillis) {
        if (snapshot == null || !"available".equals(snapshot.get("status"))) { last = null; return false; }
        Object state = snapshot.get("state");
        boolean on = "on".equals(state);
        if (!on && !"off".equals(state)) { last = null; return false; }
        boolean ring = on && Boolean.FALSE.equals(last) && nowMillis - lastRing >= cooldownMillis;
        last = on;
        if (ring) lastRing = nowMillis;
        return ring;
    }
}
