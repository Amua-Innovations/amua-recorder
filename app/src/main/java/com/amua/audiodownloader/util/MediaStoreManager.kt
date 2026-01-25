package com.amua.audiodownloader.util

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * Manages audio file storage using MediaStore API.
 * Files are stored in Music/AmuaRecordings/ and persist after app uninstall.
 */
class MediaStoreManager(private val context: Context) {

    companion object {
        private const val TAG = "MediaStoreManager"
        private const val BASE_FOLDER = "AmuaRecordings"
    }

    private val contentResolver: ContentResolver = context.contentResolver

    /**
     * Represents a recording stored in MediaStore.
     */
    data class MediaStoreRecording(
        val uri: Uri,
        val displayName: String,
        val relativePath: String,
        val size: Long,
        val dateModified: Long
    )

    /**
     * Save a file to MediaStore.
     *
     * @param sourceFile The source file to save
     * @param sessionId The session ID (used as subfolder)
     * @param displayName The display name for the file
     * @return The content URI if successful, null otherwise
     */
    fun saveToMediaStore(sourceFile: File, sessionId: String, displayName: String): Uri? {
        if (!sourceFile.exists()) {
            Log.e(TAG, "Source file does not exist: ${sourceFile.absolutePath}")
            return null
        }

        val relativePath = "${Environment.DIRECTORY_MUSIC}/$BASE_FOLDER/$sessionId"

        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        return try {
            val uri = contentResolver.insert(collection, contentValues)
            if (uri == null) {
                Log.e(TAG, "Failed to create MediaStore entry")
                return null
            }

            // Copy file content
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            // Mark as complete
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                contentResolver.update(uri, contentValues, null, null)
            }

            Log.i(TAG, "Saved to MediaStore: $displayName in $relativePath")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to MediaStore", e)
            null
        }
    }

    /**
     * Get all recordings for a session.
     *
     * @param sessionId The session ID
     * @return List of recordings in the session
     */
    fun getRecordingsForSession(sessionId: String): List<MediaStoreRecording> {
        val recordings = mutableListOf<MediaStoreRecording>()
        val relativePath = "${Environment.DIRECTORY_MUSIC}/$BASE_FOLDER/$sessionId"

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED
        )

        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf("$relativePath/")

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        try {
            contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Audio.Media.DATE_MODIFIED} ASC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(collection, id)
                    recordings.add(
                        MediaStoreRecording(
                            uri = uri,
                            displayName = cursor.getString(nameColumn),
                            relativePath = cursor.getString(pathColumn),
                            size = cursor.getLong(sizeColumn),
                            dateModified = cursor.getLong(dateColumn)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query recordings for session $sessionId", e)
        }

        return recordings
    }

    /**
     * Get all session IDs that have recordings.
     *
     * @return Set of session IDs
     */
    fun getAllSessionIds(): Set<String> {
        val sessionIds = mutableSetOf<String>()
        val basePath = "${Environment.DIRECTORY_MUSIC}/$BASE_FOLDER/"

        val projection = arrayOf(
            MediaStore.Audio.Media.RELATIVE_PATH
        )

        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("$basePath%")

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        try {
            contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(pathColumn)
                    // Extract session ID from path like "Music/AmuaRecordings/Session_xxx/"
                    val sessionId = path.removePrefix(basePath).removeSuffix("/")
                    if (sessionId.isNotEmpty() && !sessionId.contains("/")) {
                        sessionIds.add(sessionId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query session IDs", e)
        }

        return sessionIds
    }

    /**
     * Delete a recording from MediaStore.
     *
     * @param uri The content URI of the recording
     * @return True if deleted successfully
     */
    fun deleteRecording(uri: Uri): Boolean {
        return try {
            val deleted = contentResolver.delete(uri, null, null)
            deleted > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete recording", e)
            false
        }
    }

    /**
     * Delete all recordings for a session.
     *
     * @param sessionId The session ID
     * @return Number of deleted recordings
     */
    fun deleteSession(sessionId: String): Int {
        val recordings = getRecordingsForSession(sessionId)
        var deletedCount = 0

        for (recording in recordings) {
            if (deleteRecording(recording.uri)) {
                deletedCount++
            }
        }

        Log.i(TAG, "Deleted $deletedCount recordings for session $sessionId")
        return deletedCount
    }

    /**
     * Get total size of all recordings in a session.
     *
     * @param sessionId The session ID
     * @return Total size in bytes
     */
    fun getSessionSize(sessionId: String): Long {
        return getRecordingsForSession(sessionId).sumOf { it.size }
    }

    /**
     * Copy a MediaStore recording to a temporary file.
     * Useful for operations that require file access (like zipping).
     *
     * @param recording The recording to copy
     * @param destFile The destination file
     * @return True if copied successfully
     */
    fun copyToFile(recording: MediaStoreRecording, destFile: File): Boolean {
        return try {
            contentResolver.openInputStream(recording.uri)?.use { inputStream ->
                destFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy recording to file", e)
            false
        }
    }

    /**
     * Rename a session's folder by updating the RELATIVE_PATH of all its recordings.
     *
     * @param oldSessionId The current session ID (folder name)
     * @param newFolderName The new folder name
     * @return True if all recordings were renamed successfully
     */
    fun renameSessionFolder(oldSessionId: String, newFolderName: String): Boolean {
        val recordings = getRecordingsForSession(oldSessionId)
        if (recordings.isEmpty()) {
            Log.d(TAG, "No recordings to rename for session $oldSessionId")
            return true
        }

        val newRelativePath = "${Environment.DIRECTORY_MUSIC}/$BASE_FOLDER/$newFolderName"
        var successCount = 0

        for (recording in recordings) {
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, newRelativePath)
                }
                val updated = contentResolver.update(recording.uri, contentValues, null, null)
                if (updated > 0) {
                    successCount++
                } else {
                    Log.w(TAG, "Failed to update path for ${recording.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error renaming ${recording.displayName}", e)
            }
        }

        Log.i(TAG, "Renamed $successCount/${recordings.size} recordings from $oldSessionId to $newFolderName")
        return successCount == recordings.size
    }
}
