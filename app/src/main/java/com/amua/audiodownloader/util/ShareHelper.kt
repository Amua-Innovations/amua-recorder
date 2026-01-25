package com.amua.audiodownloader.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.amua.audiodownloader.session.Session
import java.io.File

/**
 * Helper for sharing session recordings via Android's share sheet.
 * Handles zip creation and cleanup after sharing.
 */
class ShareHelper(private val context: Context) {

    companion object {
        private const val TAG = "ShareHelper"
        private const val AUTHORITY = "com.amua.audiodownloader.fileprovider"
    }

    private val mediaStoreManager = MediaStoreManager(context)

    // Track zip files that need to be deleted after sharing
    private val pendingCleanup = mutableSetOf<File>()

    /**
     * Share a session as a zip file.
     * The zip file will be deleted after sharing.
     *
     * @param session The session to share
     * @param onError Callback for errors
     * @return The share intent, or null if failed
     */
    fun shareSession(session: Session, onError: ((String) -> Unit)? = null): Intent? {
        val recordings = session.getRecordings(context)
        if (recordings.isEmpty()) {
            onError?.invoke("No recordings in this session")
            return null
        }

        // Create temp directory for recordings
        val tempDir = File(context.cacheDir, "share_temp/${session.id}")
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
        tempDir.mkdirs()

        // Copy recordings from MediaStore to temp directory
        var copiedCount = 0
        for (recording in recordings) {
            val destFile = File(tempDir, recording.displayName)
            if (mediaStoreManager.copyToFile(recording, destFile)) {
                copiedCount++
            }
        }

        if (copiedCount == 0) {
            onError?.invoke("Failed to prepare recordings for sharing")
            tempDir.deleteRecursively()
            return null
        }

        // Create zip file in cache directory (for FileProvider access)
        val zipCacheDir = File(context.cacheDir, "share")
        if (!zipCacheDir.exists()) {
            zipCacheDir.mkdirs()
        }
        val zipFile = File(zipCacheDir, "${session.id}.zip")

        // Create the zip from temp directory
        val success = ZipUtils.zipDirectory(
            sourceDirectory = tempDir,
            outputFile = zipFile,
            fileFilter = { it.extension == "wav" }
        )

        // Clean up temp directory
        tempDir.deleteRecursively()

        if (!success) {
            onError?.invoke("Failed to create zip file")
            return null
        }

        // Track for cleanup
        pendingCleanup.add(zipFile)

        // Get content URI via FileProvider
        val contentUri: Uri = try {
            FileProvider.getUriForFile(context, AUTHORITY, zipFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get content URI", e)
            onError?.invoke("Failed to prepare file for sharing")
            zipFile.delete()
            return null
        }

        // Create share intent
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "AmuaRecorder - ${session.name}")
            putExtra(Intent.EXTRA_TEXT, "Session: ${session.name}\nRecordings: ${recordings.size}\nSize: ${session.getFormattedSize(context)}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        Log.i(TAG, "Created share intent for session ${session.id} with ${recordings.size} recordings")

        return Intent.createChooser(shareIntent, "Share Session Recordings")
    }

    /**
     * Clean up any pending zip files.
     * Call this after the share activity has completed.
     */
    fun cleanupPendingFiles() {
        for (file in pendingCleanup) {
            if (file.exists()) {
                val deleted = file.delete()
                Log.i(TAG, "Cleanup ${file.name}: ${if (deleted) "deleted" else "failed"}")
            }
        }
        pendingCleanup.clear()
    }

    /**
     * Clean up all zip files in the share cache directory.
     */
    fun cleanupAllShareCache() {
        val cacheDir = File(context.cacheDir, "share")
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { file ->
                if (file.extension == "zip") {
                    file.delete()
                    Log.i(TAG, "Cleaned up cached zip: ${file.name}")
                }
            }
        }

        // Also clean up temp directories
        val tempDir = File(context.cacheDir, "share_temp")
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }

        pendingCleanup.clear()
    }
}
