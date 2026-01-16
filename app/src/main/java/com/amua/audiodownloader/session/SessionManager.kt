package com.amua.audiodownloader.session

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Manages recording sessions - creation, persistence, and retrieval.
 */
class SessionManager(private val context: Context) {

    companion object {
        private const val TAG = "SessionManager"
        private const val PREFS_NAME = "session_prefs"
        private const val KEY_CURRENT_SESSION_ID = "current_session_id"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val baseDirectory: File
        get() {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "AmuaRecordings")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

    private var _currentSession: Session? = null

    /**
     * Get the current active session, creating one if none exists.
     */
    fun getCurrentSession(): Session {
        _currentSession?.let { return it }

        // Try to restore from preferences
        val savedSessionId = prefs.getString(KEY_CURRENT_SESSION_ID, null)
        if (savedSessionId != null) {
            val sessionDir = File(baseDirectory, savedSessionId)
            if (sessionDir.exists()) {
                val session = Session.fromDirectory(sessionDir)
                if (session != null) {
                    _currentSession = session
                    Log.i(TAG, "Restored session: ${session.id}")
                    return session
                }
            }
        }

        // Create a new session if none exists
        return createNewSession()
    }

    /**
     * Create a new session, ending the current one.
     */
    fun createNewSession(): Session {
        val session = Session.create(baseDirectory)
        _currentSession = session

        // Save to preferences
        prefs.edit().putString(KEY_CURRENT_SESSION_ID, session.id).apply()

        Log.i(TAG, "Created new session: ${session.id}")
        return session
    }

    /**
     * Get all sessions, sorted by creation date (newest first).
     */
    fun getAllSessions(): List<Session> {
        val dirs = baseDirectory.listFiles { file -> file.isDirectory }
        return dirs?.mapNotNull { Session.fromDirectory(it) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    /**
     * Get the directory for storing recordings in the current session.
     */
    fun getCurrentSessionDirectory(): File {
        return getCurrentSession().directory
    }

    /**
     * Delete a session and all its recordings.
     */
    fun deleteSession(session: Session): Boolean {
        return try {
            session.directory.deleteRecursively()
            Log.i(TAG, "Deleted session: ${session.id}")

            // If we deleted the current session, clear it
            if (_currentSession?.id == session.id) {
                _currentSession = null
                prefs.edit().remove(KEY_CURRENT_SESSION_ID).apply()
            }
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
        val sessionDir = File(baseDirectory, sessionId)
        return if (sessionDir.exists()) {
            Session.fromDirectory(sessionDir)
        } else {
            null
        }
    }
}
