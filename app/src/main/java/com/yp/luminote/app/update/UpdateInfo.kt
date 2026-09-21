package com.yp.luminote.app.update

data class UpdateInfo(
    val channel: UpdateChannel,
    val tag: String,
    val versionName: String,
    val versionCode: Long,
    val apkName: String,
    val apkUrl: String,
    val sha256: String,
    val releaseNotes: String
)
