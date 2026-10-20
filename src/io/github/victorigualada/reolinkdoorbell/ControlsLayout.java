// SPDX-License-Identifier: Apache-2.0
package io.github.victorigualada.reolinkdoorbell;

import android.view.Gravity;

/** Where the call bar sits; the listen toggle and status text take the corners away from it. */
final class ControlsLayout {
    static boolean vertical(String placement) { return "Left".equals(placement) || "Right".equals(placement); }
    static int gravity(String placement) {
        if ("Left".equals(placement)) return Gravity.LEFT | Gravity.CENTER_VERTICAL;
        if ("Right".equals(placement)) return Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        return ("Top".equals(placement) ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
    }
    static int corner(String placement, boolean start) {
        return ("Top".equals(placement) ? Gravity.BOTTOM : Gravity.TOP) | (start ? Gravity.START : Gravity.END);
    }
}
