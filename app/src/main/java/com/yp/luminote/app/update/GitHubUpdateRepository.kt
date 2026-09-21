package com.yp.luminote.app.update

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class GitHubUpdateRepository(
    private val context: Context
) {

    suspend fun findUpdate(channel: UpdateChannel): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val installedVersionCode =
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                ).longVersionCode

            val releases =
                JSONArray(
                    readText(RELEASES_URL)
                )

            buildList {
                for (index in 0 until releases.length()) {
                    releaseFrom(
                        release = releases.getJSONObject(index),
                        channel = channel
                    )?.let(::add)
                }
            }
                .filter { update -> update.versionCode > installedVersionCode }
                .maxByOrNull(UpdateInfo::versionCode)
        }

    suspend fun download(update: UpdateInfo): File =
        withContext(Dispatchers.IO) {
            val updatesDirectory =
                File(context.cacheDir, UPDATES_DIRECTORY).apply {
                    mkdirs()
                }
            val destination =
                File(updatesDirectory, update.apkName)
            val temporary =
                File(updatesDirectory, "${update.apkName}.part")

            if (destination.isFile && sha256(destination) == update.sha256) {
                return@withContext destination
            }

            temporary.delete()
            openConnection(update.apkUrl).useInput { input ->
                temporary.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            check(sha256(temporary) == update.sha256) {
                "Downloaded APK checksum does not match the release manifest."
            }

            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }

            destination
        }

    private fun releaseFrom(
        release: JSONObject,
        channel: UpdateChannel
    ): UpdateInfo? {
        if (release.optBoolean("draft")) return null

        val tag = release.optString("tag_name")
        if (!channel.acceptsTag(tag)) return null

        val assets = release.optJSONArray("assets") ?: return null
        val manifestAsset =
            assets.findByName(MANIFEST_FILE_NAME) ?: return null
        val manifest =
            JSONObject(
                readText(
                    manifestAsset.getString("browser_download_url")
                )
            )

        if (
            manifest.optInt("schema") != MANIFEST_SCHEMA ||
            manifest.optString("tag") != tag ||
            manifest.optString("channel") != channel.name.lowercase()
        ) {
            return null
        }

        val apkName = manifest.optString("apkName")
        val apkAsset = assets.findByName(apkName) ?: return null
        val versionCode = manifest.optLong("versionCode")
        val checksum = manifest.optString("sha256").lowercase()
        if (versionCode <= 0L || !checksum.matches(SHA_256_PATTERN)) return null

        return UpdateInfo(
            channel = channel,
            tag = tag,
            versionName = manifest.optString("versionName"),
            versionCode = versionCode,
            apkName = apkName,
            apkUrl = apkAsset.getString("browser_download_url"),
            sha256 = checksum,
            releaseNotes = release.optString("body")
        )
    }

    private fun JSONArray.findByName(name: String): JSONObject? =
        (0 until length())
            .asSequence()
            .map(::getJSONObject)
            .firstOrNull { asset -> asset.optString("name") == name }

    private fun readText(url: String): String =
        openConnection(url).useInput { input ->
            input.bufferedReader().use { reader ->
                reader.readText()
            }
        }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
            instanceFollowRedirects = true
            check(responseCode in HTTP_SUCCESS_RANGE) {
                "GitHub request failed with HTTP $responseCode."
            }
        }

    private inline fun <T> HttpURLConnection.useInput(
        block: (java.io.InputStream) -> T
    ): T = try {
        block(inputStream)
    } finally {
        disconnect()
    }

    private fun sha256(file: File): String =
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { byte ->
                "%02x".format(byte)
            }
        }

    private companion object {
        const val OWNER = "pasternak2048"
        const val REPOSITORY = "yuriipasternak-luminote"
        const val RELEASES_URL =
            "https://api.github.com/repos/$OWNER/$REPOSITORY/releases?per_page=100"
        const val MANIFEST_FILE_NAME = "luminote-update.json"
        const val MANIFEST_SCHEMA = 1
        const val UPDATES_DIRECTORY = "updates"
        const val NETWORK_TIMEOUT_MS = 15_000
        const val USER_AGENT = "Luminote-updater"
        val HTTP_SUCCESS_RANGE = 200..299
        val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
    }
}
