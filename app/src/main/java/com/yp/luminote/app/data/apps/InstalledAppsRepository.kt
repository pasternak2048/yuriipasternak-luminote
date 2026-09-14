package com.yp.luminote.app.data.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

data class InstalledApp(
    val name: String,
    val packageName: String
)

class InstalledAppsRepository(
    context: Context
) {
    private val appContext = context.applicationContext

    private val packageManager: PackageManager =
        appContext.packageManager

    fun getInstalledApps(): List<InstalledApp> =
        cachedApps ?: synchronized(cacheLock) {
            cachedApps ?: scanInstalledApps().also { apps ->
                cachedApps = apps
            }
        }

    private fun scanInstalledApps(): List<InstalledApp> =
        packageManager
            .getInstalledApplications(
                PackageManager.GET_META_DATA
            )
            .asSequence()
            .filter { applicationInfo ->
                applicationInfo.packageName != appContext.packageName
            }
            .filter { applicationInfo ->
                applicationInfo.enabled
            }
            .filter { applicationInfo ->
                applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
            }
            .map { applicationInfo ->
                InstalledApp(
                    name = applicationInfo
                        .loadLabel(packageManager)
                        .toString(),
                    packageName = applicationInfo.packageName
                )
            }
            .distinctBy { it.packageName }
            .sortedBy {
                it.name.lowercase()
            }
            .toList()

    private companion object {
        private val cacheLock = Any()
        private var cachedApps: List<InstalledApp>? = null
    }
}
