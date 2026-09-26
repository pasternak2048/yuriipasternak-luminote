package com.yp.luminote.app.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

object UpdateInstaller {

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri()
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(context: Context, apk: File) {
        val apkUri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.updates",
                apk
            )

        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    apkUri,
                    APK_MIME_TYPE
                )
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        )
    }

    private const val APK_MIME_TYPE =
        "application/vnd.android.package-archive"
}
