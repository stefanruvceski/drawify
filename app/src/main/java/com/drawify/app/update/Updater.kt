package com.drawify.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.drawify.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Talks to GitHub Releases and hands downloaded APKs to Android's package installer. */
object Updater {

    private const val RELEASES_URL = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=15"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val TIMEOUT_MS = 15_000

    /** Installed build number (versionCode). */
    fun installedBuild(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    /**
     * The newest release if it is newer than the installed build. Returns null when updates are
     * disabled (debug builds), offline, rate-limited or on any other error.
     */
    suspend fun checkForUpdate(context: Context): Releases.ReleaseInfo? {
        if (!BuildConfig.UPDATE_CHECK_ENABLED) return null
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout = TIMEOUT_MS
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    Releases.newerThan(Releases.pickLatest(Releases.parse(body)), installedBuild(context))
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Downloads the release APK into the cache, reporting progress as 0..1 (or -1 if unknown). */
    suspend fun download(
        context: Context,
        release: Releases.ReleaseInfo,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Keep only the APK being downloaded; older ones are dead weight.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "drawify-b${release.build}.apk")
        val partial = File(dir, "${target.name}.part")

        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            var done = 0L
            var lastReported = -1
            conn.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != lastReported) {
                            lastReported = percent
                            val fraction = if (total > 0) done.toFloat() / total else -1f
                            withContext(Dispatchers.Main) { onProgress(fraction) }
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Incomplete download")
        } finally {
            conn.disconnect()
        }
        if (!partial.renameTo(target)) throw IOException("Could not save the update")
        target
    }

    /** Whether Android lets this app install packages ("Install unknown apps"). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" setting for this app. */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        context.startActivity(intent)
    }

    /** Opens Android's package installer for [apk]. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
