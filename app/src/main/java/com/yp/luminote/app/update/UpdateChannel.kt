package com.yp.luminote.app.update

import androidx.annotation.StringRes
import com.yp.luminote.app.R

enum class UpdateChannel(
    @get:StringRes val labelRes: Int,
    private val tagMarker: String?
) {
    STABLE(
        labelRes = R.string.update_channel_stable,
        tagMarker = null
    ),
    QA(
        labelRes = R.string.update_channel_qa,
        tagMarker = ".qa."
    ),
    DEV(
        labelRes = R.string.update_channel_dev,
        tagMarker = ".dev."
    );

    fun acceptsTag(tag: String): Boolean =
        tagMarker?.let(tag::contains) ?: (
            !tag.contains(QA.tagMarker.orEmpty()) &&
                !tag.contains(DEV.tagMarker.orEmpty())
            )
}
