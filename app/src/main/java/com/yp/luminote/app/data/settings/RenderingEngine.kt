package com.yp.luminote.app.data.settings

/** Persist the user's preference, never a transient executor fallback. */
enum class RenderingEngine {
    CANVAS, GPU;

    companion object {
        fun fromStorage(value: String?): RenderingEngine = entries.firstOrNull { it.name == value } ?: CANVAS
    }
}
