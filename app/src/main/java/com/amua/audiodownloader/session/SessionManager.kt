package com.amua.audiodownloader.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.amua.audiodownloader.util.MediaStoreManager
import java.util.Date

/**
 * Manages recording sessions - creation, persistence, and retrieval.
 * Session metadata is stored in SharedPreferences.
 * Recordings are stored in MediaStore and persist after app uninstall.
 */
class SessionManager(private val context: Context) {

    companion object {
        private const val TAG = "SessionManager"
        private const val PREFS_NAME = "session_prefs"
        private const val KEY_CURRENT_SESSION_ID = "current_session_id"
        private const val KEY_SESSION_IDS = "session_ids"
        private const val PREFIX_SESSION_NAME = "session_name_"
        private const val PREFIX_SESSION_CREATED = "session_created_"
        private const val PREFIX_SESSION_FOLDER = "session_folder_"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mediaStoreManager = MediaStoreManager(context)

    private var _currentSession: Session? = null

    /**
     * Get the current active session, creating one if none exists.
     */
    fun getCurrentSession(): Session {
        // Check if saved session ID differs from cached session (another instance may have changed it)
        val savedSessionId = prefs.getString(KEY_CURRENT_SESSION_ID, null)

        _currentSession?.let { session ->
            // If the saved ID differs from cached, invalidate cache and reload
            if (savedSessionId != null && savedSessionId != session.id) {
                Log.d(TAG, "Session changed externally, reloading from preferences")
                _currentSession = null
            } else {
                // Apply custom name if set
                val customName = getSessionName(session.id)
                return if (customName != null && session.name != customName) {
                    val updated = session.copy(name = customName)
                    _currentSession = updated
                    updated
                } else {
                    session
                }
            }
        }

        // Try to restore from preferences
        if (savedSessionId != null) {
            val session = loadSession(savedSessionId)
            if (session != null) {
                _currentSession = session
                Log.i(TAG, "Restored session: ${session.id}")
                return session
            }
        }

        // Create a new session if none exists
        return createNewSession()
    }

    /**
     * Create a new session, ending the current one.
     */
    fun createNewSession(): Session {
        val session = Session.create()
        _currentSession = session

        // Save session metadata
        saveSessionMetadata(session)
        prefs.edit().putString(KEY_CURRENT_SESSION_ID, session.id).apply()

        Log.i(TAG, "Created new session: ${session.id}")
        return session
    }

    /**
     * Get all sessions, sorted by creation date (newest first).
     */
    fun getAllSessions(): List<Session> {
        // Use sessions from preferences as the source of truth
        // (MediaStore folder names may not match session IDs after renaming)
        val sessionIds = getStoredSessionIds()

        return sessionIds.mapNotNull { id -> loadSession(id) }
            .sortedByDescending { it.createdAt }
    }

    /**
     * Delete a session and all its recordings.
     */
    fun deleteSession(session: Session): Boolean {
        return try {
            // Delete recordings from MediaStore (use folderName since it may have been renamed)
            mediaStoreManager.deleteSession(session.folderName)

            // Remove session metadata
            removeSessionMetadata(session.id)

            // If we deleted the current session, clear it
            if (_currentSession?.id == session.id) {
                _currentSession = null
                prefs.edit().remove(KEY_CURRENT_SESSION_ID).apply()
            }

            Log.i(TAG, "Deleted session: ${session.id}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete session: ${session.id}", e)
            false
        }
    }

    /**
     * Get a session by ID.
     */
    fun getSession(sessionId: String): Session? {
        return loadSession(sessionId)
    }

    /**
     * Switch to an existing session, making it the current session.
     */
    fun setCurrentSession(session: Session): Boolean {
        _currentSession = session
        prefs.edit().putString(KEY_CURRENT_SESSION_ID, session.id).apply()
        Log.i(TAG, "Switched to session: ${session.id}")
        return true
    }

    /**
     * Rename a session and its folder in MediaStore.
     */
    fun renameSession(session: Session, newName: String): Session? {
        if (newName.isBlank()) {
            Log.e(TAG, "New name cannot be blank")
            return null
        }

        val trimmedName = newName.trim()
        val oldFolderName = getSessionFolderName(session.id)

        // Rename the folder in MediaStore
        val renameSuccess = mediaStoreManager.renameSessionFolder(oldFolderName, trimmedName)
        if (!renameSuccess) {
            Log.w(TAG, "Some files may not have been renamed in MediaStore")
        }

        val renamedSession = session.copy(name = trimmedName, folderName = trimmedName)

        // Save the name and folder mapping to preferences
        prefs.edit()
            .putString("$PREFIX_SESSION_NAME${session.id}", trimmedName)
            .putString("$PREFIX_SESSION_FOLDER${session.id}", trimmedName)
            .apply()

        // Update current session if it's the one being renamed
        if (_currentSession?.id == session.id) {
            _currentSession = renamedSession
        }

        Log.i(TAG, "Renamed session ${session.id} to: $trimmedName")
        return renamedSession
    }

    /**
     * Get the custom name for a session, if set.
     */
    fun getSessionName(sessionId: String): String? {
        return prefs.getString("$PREFIX_SESSION_NAME$sessionId", null)
    }

    /**
     * Get the folder name for a session (defaults to session ID if not renamed).
     */
    fun getSessionFolderName(sessionId: String): String {
        return prefs.getString("$PREFIX_SESSION_FOLDER$sessionId", null) ?: sessionId
    }

    /**
     * Load a session from stored metadata or MediaStore.
     */
    private fun loadSession(sessionId: String): Session? {
        // Get custom name if set
        val customName = getSessionName(sessionId)

        // Get folder name (defaults to session ID if never renamed)
        val folderName = getSessionFolderName(sessionId)

        // Get creation time from preferences or parse from ID
        val createdAt = prefs.getLong("$PREFIX_SESSION_CREATED$sessionId", 0L).let {
            if (it > 0) Date(it) else Session.parseCreatedAt(sessionId)
        }

        return Session(
            id = sessionId,
            name = customName ?: sessionId,
            createdAt = createdAt,
            folderName = folderName
        )
    }

    /**
     * Save session metadata to preferences.
     */
    private fun saveSessionMetadata(session: Session) {
        val sessionIds = getStoredSessionIds().toMutableSet()
        sessionIds.add(session.id)

        prefs.edit()
            .putStringSet(KEY_SESSION_IDS, sessionIds)
            .putLong("$PREFIX_SESSION_CREATED${session.id}", session.createdAt.time)
            .apply()
    }

    /**
     * Remove session metadata from preferences.
     */
    private fun removeSessionMetadata(sessionId: String) {
        val sessionIds = getStoredSessionIds().toMutableSet()
        sessionIds.remove(sessionId)

        prefs.edit()
            .putStringSet(KEY_SESSION_IDS, sessionIds)
            .remove("$PREFIX_SESSION_NAME$sessionId")
            .remove("$PREFIX_SESSION_CREATED$sessionId")
            .remove("$PREFIX_SESSION_FOLDER$sessionId")
            .apply()
    }

    /**
     * Get stored session IDs from preferences.
     */
    private fun getStoredSessionIds(): Set<String> {
        return prefs.getStringSet(KEY_SESSION_IDS, emptySet()) ?: emptySet()
    }
}
