package com.yp.luminote.app.ui.access

internal enum class LockScreenAccessibilityAccessAction {
    SHOW_DISCLOSURE,
    OPEN_SETTINGS
}

internal fun lockScreenAccessibilityAccessAction(
    accessAlreadyAllowed: Boolean
): LockScreenAccessibilityAccessAction =
    if (accessAlreadyAllowed) {
        LockScreenAccessibilityAccessAction.OPEN_SETTINGS
    } else {
        LockScreenAccessibilityAccessAction.SHOW_DISCLOSURE
    }
