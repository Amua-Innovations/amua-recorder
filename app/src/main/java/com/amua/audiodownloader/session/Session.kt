package com.amua.audiodownloader.session

import android.content.Context
import com.amua.audiodownloader.util.MediaStoreManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a recording session containing multiple audio files.
 * Recordings are stored in MediaStore and persist after app uninstall.
 */
data class Session(
    val id: String,
    val name: String,
    val createdAt: Date,
    val folderName: String = id  // The folder name in MediaStore (may differ from id after rename)
) {
    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)

        /**
         * Generate a session ID based on current timestamp.
         */
        fun generateId(): String {
            return "Session_${DATE_FORMAT.format(Date())}"
        }

        /**
         * Create a new session.
         */
        fun create(): Session {
            val id = generateId()
            return Session(
                id = id,
                name = id,
                createdAt = Date()
            )
        }

        /**
         * Parse creation date from session ID.
         */
        fun parseCreatedAt(sessionId: String): Date {
            return try {
                val dateStr = sessionId.removePrefix("Session_")
                DATE_FORMAT.parse(dateStr) ?: Date()
            } catch (e: Exception) {
                Date()
            }
        }
    }

    /**
     * Get all recordings in this session from MediaStore.
     */
    fun getRecordings(context: Context): List<MediaStoreManager.MediaStoreRecording> {
        val mediaStoreManager = MediaStoreManager(context)
        return mediaStoreManager.getRecordingsForSession(folderName)
    }

    /**
     * Get the number of recordings in this session.
     */
    fun getRecordingCount(context: Context): Int {
        return getRecordings(context).size
    }

    /**
     * Get the total size of all recordings in bytes.
     */
    fun getTotalSizeBytes(context: Context): Long {
        return getRecordings(context).sumOf { it.size }
    }

    /**
     * Get formatted total size (e.g., "2.3 MB").
     */
    fun getFormattedSize(context: Context): String {
        val bytes = getTotalSizeBytes(context)
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    /**
     * Get formatted creation date.
     */
    fun getFormattedDate(): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return format.format(createdAt)
    }
}
