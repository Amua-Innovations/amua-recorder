package com.amua.audiodownloader.session

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a recording session containing multiple audio files.
 */
data class Session(
    val id: String,
    val name: String,
    val directory: File,
    val createdAt: Date
) {
    /**
     * Get all WAV files in this session.
     */
    fun getRecordings(): List<File> {
        return directory.listFiles { file -> file.extension == "wav" }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Get the number of recordings in this session.
     */
    fun getRecordingCount(): Int = getRecordings().size

    /**
     * Get the total size of all recordings in bytes.
     */
    fun getTotalSizeBytes(): Long {
        return getRecordings().sumOf { it.length() }
    }

    /**
     * Get formatted total size (e.g., "2.3 MB").
     */
    fun getFormattedSize(): String {
        val bytes = getTotalSizeBytes()
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
        fun create(baseDirectory: File): Session {
            val id = generateId()
            val directory = File(baseDirectory, id)
            directory.mkdirs()
            return Session(
                id = id,
                name = id,
                directory = directory,
                createdAt = Date()
            )
        }

        /**
         * Load an existing session from a directory.
         */
        fun fromDirectory(directory: File): Session? {
            if (!directory.isDirectory) return null

            val id = directory.name
            val createdAt = try {
                // Try to parse date from directory name
                val dateStr = id.removePrefix("Session_")
                DATE_FORMAT.parse(dateStr) ?: Date(directory.lastModified())
            } catch (e: Exception) {
                Date(directory.lastModified())
            }

            return Session(
                id = id,
                name = id,
                directory = directory,
                createdAt = createdAt
            )
        }
    }
}
