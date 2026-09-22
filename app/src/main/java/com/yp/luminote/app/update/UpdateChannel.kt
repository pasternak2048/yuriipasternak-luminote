package com.yp.luminote.app.update

enum class UpdateChannel(
    val label: String,
    private val tagMarker: String?
) {
    STABLE(
        label = "Stable",
        tagMarker = null
    ),
    QA(
        label = "QA",
        tagMarker = ".qa."
    ),
    DEV(
        label = "Dev",
        tagMarker = ".dev."
    );

    fun acceptsTag(tag: String): Boolean =
        tagMarker?.let(tag::contains) ?: (
            !tag.contains(QA.tagMarker.orEmpty()) &&
                !tag.contains(DEV.tagMarker.orEmpty())
            )
}
