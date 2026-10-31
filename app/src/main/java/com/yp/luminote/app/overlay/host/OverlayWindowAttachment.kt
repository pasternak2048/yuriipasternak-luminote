package com.yp.luminote.app.overlay.host

import android.os.Looper
import android.view.View
import android.view.WindowManager

/** A controlled handoff cannot admit the replacement until the old root is detached. */
internal fun removeOverlayWindowImmediately(manager: WindowManager, view: View) {
    check(Looper.myLooper() == Looper.getMainLooper())
    // addView registers a ViewRoot/parent before the first traversal attaches the View.
    if (view.parent != null || view.isAttachedToWindow) manager.removeViewImmediate(view)
    check(view.parent == null && !view.isAttachedToWindow) { "Overlay window remained registered after removal" }
}
