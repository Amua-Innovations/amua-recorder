package com.amua.audiodownloader.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages checking for and installing app updates from GitHub releases.
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val GITHUB_API_URL = "https://api.github.com/repos/Amua-Innovations/amua-recorder/releases/latest"
        private const val PREFS_NAME = "update_prefs"
        private const val PREF_SKIPPED_VERSION = "skipped_version"
        private const val PREF_LAST_AUTO_CHECK_TIME = "last_auto_check_time"
        private const val PREF_LAST_MANUAL_CHECK_TIME = "last_manual_check_time"
        private const val AUTO_CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000L // 4 hours
        private const val MANUAL_CHECK_INTERVAL_MS = 60 * 60 * 1000L // 1 hour
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var downloadReceiver: BroadcastReceiver? = null
    private var currentDownloadId: Long = -1

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val releaseNotes: String,
        val downloadUrl: String,
        val apkSize: Long
    )

    /**
     * Result of a rate-limited check attempt.
     */
    data class CheckResult(
        val updateInfo: UpdateInfo?,
        val wasRateLimited: Boolean = false,
        val minutesUntilNextCheck: Int = 0
    )

    /**
     * Check for updates from GitHub releases.
     * Returns UpdateInfo if an update is available, null otherwise.
     *
     * @param isManualCheck If true, uses manual check rate limiting (1 hour).
     *                      If false, uses automatic check rate limiting (4 hours).
     */
    suspend fun checkForUpdates(isManualCheck: Boolean = false): CheckResult = withContext(Dispatchers.IO) {
        try {
            // Apply rate limiting based on check type
            val prefKey = if (isManualCheck) PREF_LAST_MANUAL_CHECK_TIME else PREF_LAST_AUTO_CHECK_TIME
            val intervalMs = if (isManualCheck) MANUAL_CHECK_INTERVAL_MS else AUTO_CHECK_INTERVAL_MS
            val lastCheck = prefs.getLong(prefKey, 0)
            val timeSinceLastCheck = System.currentTimeMillis() - lastCheck

            if (timeSinceLastCheck < intervalMs) {
                val minutesRemaining = ((intervalMs - timeSinceLastCheck) / 60000).toInt() + 1
                Log.d(TAG, "Skipping ${if (isManualCheck) "manual" else "auto"} check - $minutesRemaining minutes until next allowed check")
                return@withContext CheckResult(null, wasRateLimited = true, minutesUntilNextCheck = minutesRemaining)
            }

            // Update last check time
            prefs.edit().putLong(prefKey, System.currentTimeMillis()).apply()

            val url = URL(GITHUB_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "AmuaRecorder-Android")
                connectTimeout = 10000
                readTimeout = 10000
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Failed to check for updates: HTTP ${connection.responseCode}")
                return@withContext CheckResult(null)
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val json = JSONObject(response)
            val tagName = json.getString("tag_name") // e.g., "v1.2.0"
            val releaseNotes = json.optString("body", "No release notes available")

            // Parse version from tag (e.g., "v1.2.0" -> "1.2.0")
            val latestVersion = tagName.removePrefix("v")

            // Get current app version
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersion = packageInfo.versionName ?: "0.0.0"

            // Check if user has skipped this version (but allow manual checks to bypass)
            val skippedVersion = prefs.getString(PREF_SKIPPED_VERSION, null)
            if (skippedVersion == latestVersion && !isManualCheck) {
                Log.d(TAG, "User skipped version $latestVersion")
                return@withContext CheckResult(null)
            }

            // Compare versions
            if (!isNewerVersion(latestVersion, currentVersion)) {
                Log.d(TAG, "No update available (current: $currentVersion, latest: $latestVersion)")
                return@withContext CheckResult(null)
            }

            // Find APK download URL from assets
            val assets = json.getJSONArray("assets")
            var downloadUrl: String? = null
            var apkSize: Long = 0

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    downloadUrl = asset.getString("browser_download_url")
                    apkSize = asset.getLong("size")
                    break
                }
            }

            if (downloadUrl == null) {
                Log.w(TAG, "No APK found in release assets")
                return@withContext CheckResult(null)
            }

            // Extract version code from tag if possible (e.g., v1.2.0 -> 120)
            val latestVersionCode = parseVersionCode(latestVersion)

            Log.i(TAG, "Update available: $currentVersion -> $latestVersion")

            CheckResult(
                UpdateInfo(
                    versionName = latestVersion,
                    versionCode = latestVersionCode,
                    releaseNotes = releaseNotes,
                    downloadUrl = downloadUrl,
                    apkSize = apkSize
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
            CheckResult(null)
        }
    }

    /**
     * Compare version strings (e.g., "1.2.0" vs "1.1.0")
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    /**
     * Convert version string to version code (e.g., "1.2.3" -> 10203)
     */
    private fun parseVersionCode(version: String): Int {
        val parts = version.split(".").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 10000 + parts[1] * 100 + parts[2]
            2 -> parts[0] * 10000 + parts[1] * 100
            1 -> parts[0] * 10000
            else -> 0
        }
    }

    /**
     * Skip this version and don't prompt again.
     */
    fun skipVersion(version: String) {
        prefs.edit().putString(PREF_SKIPPED_VERSION, version).apply()
        Log.i(TAG, "Skipped version: $version")
    }

    /**
     * Download and install the APK update.
     */
    fun downloadAndInstall(updateInfo: UpdateInfo, onProgress: ((Int) -> Unit)? = null, onComplete: ((Boolean) -> Unit)? = null) {
        try {
            val fileName = "AmuaRecorder-${updateInfo.versionName}.apk"
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val apkFile = File(downloadDir, fileName)

            // Delete old APK if exists
            if (apkFile.exists()) {
                apkFile.delete()
            }

            val request = DownloadManager.Request(Uri.parse(updateInfo.downloadUrl))
                .setTitle("AmuaRecorder Update")
                .setDescription("Downloading version ${updateInfo.versionName}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            currentDownloadId = downloadManager.enqueue(request)

            // Register receiver for download completion
            downloadReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == currentDownloadId) {
                        unregisterReceiver()

                        // Check download status
                        val query = DownloadManager.Query().setFilterById(currentDownloadId)
                        val cursor = downloadManager.query(query)
                        if (cursor.moveToFirst()) {
                            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val status = cursor.getInt(statusIndex)

                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                installApk(apkFile)
                                onComplete?.invoke(true)
                            } else {
                                Log.e(TAG, "Download failed with status: $status")
                                onComplete?.invoke(false)
                            }
                        }
                        cursor.close()
                    }
                }
            }

            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(downloadReceiver, filter)
            }

            Log.i(TAG, "Started download: $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading update", e)
            onComplete?.invoke(false)
        }
    }

    /**
     * Install the downloaded APK.
     */
    private fun installApk(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(intent)
            Log.i(TAG, "Launched APK installer for: ${apkFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK", e)
        }
    }

    /**
     * Unregister the download receiver.
     */
    private fun unregisterReceiver() {
        try {
            downloadReceiver?.let {
                context.unregisterReceiver(it)
            }
        } catch (e: Exception) {
            // Ignore if already unregistered
        }
        downloadReceiver = null
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        unregisterReceiver()
    }

    /**
     * Format file size for display.
     */
    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
