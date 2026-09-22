package com.yp.luminote.app.data.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class InstalledApp(
    val name: String,
    val packageName: String
)

class InstalledAppsRepository(
    context: Context
) {
    private val appContext =
        context.applicationContext

    private val packageManager: PackageManager =
        appContext.packageManager

    fun getInstalledApps(): List<InstalledApp> =
        cachedApps ?: synchronized(cacheLock) {
            cachedApps ?: scanInstalledApps().also { apps ->
                cachedApps = apps
            }
        }

    /** Rebuilds the list after the app returns to the foreground. */
    fun refreshInstalledApps(): List<InstalledApp> =
        synchronized(cacheLock) {
            scanInstalledApps().also { apps ->
                cachedApps = apps
            }
        }

    private fun scanInstalledApps(): List<InstalledApp> =
        packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.ResolveInfoFlags.of(0)
            )
            .asSequence()
            .map { resolveInfo ->
                resolveInfo.activityInfo.applicationInfo
            }
            .filter { applicationInfo ->
                applicationInfo.packageName !=
                        appContext.packageName
            }
            .filter { applicationInfo ->
                applicationInfo.enabled
            }
            .mapNotNull { applicationInfo ->
                val label =
                    applicationInfo
                        .loadLabel(packageManager)
                        .toString()
                        .trim()

                if (label.isBlank()) {
                    null
                } else {
                    InstalledApp(
                        name = label,
                        packageName =
                            applicationInfo.packageName
                    )
                }
            }
            .distinctBy {
                it.packageName
            }
            .sortedWith(
                compareBy(
                    String.CASE_INSENSITIVE_ORDER
                ) {
                    it.name
                }
            )
            .toList()

    private companion object {
        private val cacheLock =
            Any()

        private var cachedApps:
                List<InstalledApp>? =
            null
    }
}
