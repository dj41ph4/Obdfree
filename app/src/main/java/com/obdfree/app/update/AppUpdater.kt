package com.obdfree.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.obdfree.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface AppUpdateState {
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Downloading(val version: String) : AppUpdateState
    data class Ready(val release: AppRelease, val apk: File) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}

data class AppRelease(
    val version: String,
    val apkUrl: String,
    val sha256: String?,
    val notes: String,
)

class AppUpdater(
    private val context: Context,
) {
    suspend fun checkForUpdate(): AppRelease? = withContext(Dispatchers.IO) {
        val connection = openConnection(LATEST_RELEASE_URL)
        try {
            if (connection.responseCode !in 200..299) {
                error("GitHub a répondu ${connection.responseCode}")
            }

            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(payload)
            val version = json.getString("tag_name").removePrefix("v")

            if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) {
                return@withContext null
            }

            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            var digest: String? = null

            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue

                apkUrl = asset.getString("browser_download_url")
                digest = asset.optString("digest")
                    .takeIf { it.startsWith("sha256:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.lowercase()
                break
            }

            if (apkUrl.isNullOrBlank()) {
                error("La release $version ne contient aucun APK")
            }

            AppRelease(
                version = version,
                apkUrl = apkUrl,
                sha256 = digest,
                notes = json.optString("body"),
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadAndVerify(release: AppRelease): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(updatesDir, "OBDfree-${release.version}.apk")

        if (!target.isFile || !isValidDownloadedApk(target, release)) {
            target.delete()
            download(release.apkUrl, target)
        }

        if (!isValidDownloadedApk(target, release)) {
            target.delete()
            error("La mise à jour téléchargée n’a pas passé la vérification de sécurité")
        }

        target
    }

    fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apk,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        activity.startActivity(intent)
    }

    private fun download(url: String, target: File) {
        val connection = openConnection(url)
        try {
            if (connection.responseCode !in 200..299) {
                error("Téléchargement impossible (${connection.responseCode})")
            }

            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isValidDownloadedApk(file: File, release: AppRelease): Boolean {
        if (!file.isFile || file.length() <= 0L) return false

        release.sha256?.let { expected ->
            if (!sha256(file).equals(expected, ignoreCase = true)) return false
        }

        val downloadedInfo = archivePackageInfo(file) ?: return false
        if (downloadedInfo.packageName != context.packageName) return false

        val installedInfo = installedPackageInfo() ?: return false
        val installedSignatures = certificateDigests(installedInfo)
        val downloadedSignatures = certificateDigests(downloadedInfo)

        return installedSignatures.isNotEmpty() && installedSignatures == downloadedSignatures
    }

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, flags)
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun certificateDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            info.signatures?.toList().orEmpty()
        }

        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OBDfree/${BuildConfig.VERSION_NAME}")
        }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val rightParts = right.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val max = maxOf(leftParts.size, rightParts.size)

        repeat(max) { index ->
            val a = leftParts.getOrElse(index) { 0 }
            val b = rightParts.getOrElse(index) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/dj41ph4/Obdfree/releases/latest"
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
