package com.drawify.app.update

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.drawify.app.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * Slim banner under the top bar when a newer build is on GitHub. Checks on launch and whenever the
 * app returns to the foreground ([check] from onStart). One tap downloads the APK and opens Android's
 * installer; the first time it walks the user through allowing "Install unknown apps".
 */
class UpdateBanner(private val activity: AppCompatActivity) {

    private val banner: View = activity.findViewById(R.id.updateBanner)
    private val title: TextView = activity.findViewById(R.id.updateTitle)
    private val subtitle: TextView = activity.findViewById(R.id.updateSubtitle)
    private val action: MaterialButton = activity.findViewById(R.id.updateAction)
    private val close: View = activity.findViewById(R.id.updateClose)
    private val progress: LinearProgressIndicator = activity.findViewById(R.id.updateProgress)

    private var release: Releases.ReleaseInfo? = null
    private var apk: File? = null
    private var waitingForPermission = false
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    init {
        action.setOnClickListener { onAction() }
        close.setOnClickListener { dismiss() }
    }

    fun check() {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        checkJob = activity.lifecycleScope.launch {
            // Null = offline, rate-limited, up to date or a debug build: leave the banner as it is.
            val latest = Updater.checkForUpdate(activity) ?: return@launch
            if (latest.build == dismissedBuild || latest.build == release?.build) return@launch
            release = latest
            apk = null
            waitingForPermission = false
            showAvailable()
        }
    }

    /** Call from onResume: continues the install once the user granted the permission in Settings. */
    fun onResume() {
        val file = apk
        if (waitingForPermission && file != null && Updater.canInstall(activity)) {
            waitingForPermission = false
            launchInstaller(file)
        }
    }

    private fun onAction() {
        if (waitingForPermission) {
            Updater.openInstallPermissionSettings(activity)
            return
        }
        val file = apk
        if (file != null && file.exists()) installOrAskPermission(file) else startDownload()
    }

    private fun startDownload() {
        val r = release ?: return
        if (downloadJob?.isActive == true) return
        showProgress(0f)
        downloadJob = activity.lifecycleScope.launch {
            try {
                val file = Updater.download(activity, r, ::showProgress)
                apk = file
                installOrAskPermission(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showAvailable(activity.getString(R.string.update_failed))
            }
        }
    }

    private fun installOrAskPermission(file: File) {
        if (Updater.canInstall(activity)) {
            launchInstaller(file)
        } else {
            waitingForPermission = true
            showNeedsPermission()
        }
    }

    private fun launchInstaller(file: File) {
        // Back to the normal banner, so it is still there if the user cancels the installer.
        try {
            Updater.install(activity, file)
            showAvailable()
        } catch (e: Exception) {
            showAvailable(activity.getString(R.string.update_installer_failed))
        }
    }

    private fun dismiss() {
        // Hidden for this build until the next launch; a newer build shows up again.
        release?.let { dismissedBuild = it.build }
        downloadJob?.cancel()
        waitingForPermission = false
        banner.visibility = View.GONE
    }

    private fun showAvailable(message: String? = null) {
        val r = release ?: return
        title.text = activity.getString(R.string.update_title, r.build)
        setSubtitle(message ?: r.notes)
        action.setText(R.string.update_install)
        action.visibility = View.VISIBLE
        progress.visibility = View.GONE
        banner.visibility = View.VISIBLE
    }

    private fun showProgress(fraction: Float) {
        if (fraction < 0f) {
            title.setText(R.string.update_downloading_unknown)
            progress.isIndeterminate = true
        } else {
            val percent = (fraction * 100).roundToInt().coerceIn(0, 100)
            title.text = activity.getString(R.string.update_downloading, percent)
            progress.isIndeterminate = false
            progress.setProgressCompat(percent, true)
        }
        setSubtitle(null)
        action.visibility = View.GONE
        progress.visibility = View.VISIBLE
        banner.visibility = View.VISIBLE
    }

    private fun showNeedsPermission() {
        title.setText(R.string.update_permission_title)
        setSubtitle(activity.getString(R.string.update_permission_body), maxLines = 3)
        action.setText(R.string.update_settings)
        action.visibility = View.VISIBLE
        progress.visibility = View.GONE
        banner.visibility = View.VISIBLE
    }

    private fun setSubtitle(text: String?, maxLines: Int = 1) {
        subtitle.maxLines = maxLines
        subtitle.text = text
        subtitle.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private companion object {
        /** Build the user closed the banner for; lives as long as the process (until next launch). */
        var dismissedBuild = -1
    }
}
