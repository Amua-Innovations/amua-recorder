package com.amua.audiodownloader.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.amua.audiodownloader.databinding.ActivitySessionsBinding
import com.amua.audiodownloader.session.Session
import com.amua.audiodownloader.session.SessionManager
import com.amua.audiodownloader.util.ShareHelper

/**
 * Activity for managing recording sessions.
 */
class SessionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionsBinding
    private lateinit var sessionManager: SessionManager
    private lateinit var shareHelper: ShareHelper
    private lateinit var adapter: SessionAdapter

    // Track if we're returning from a share action
    private var pendingShareCleanup = false

    private val shareResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Clean up zip file after sharing completes (or is cancelled)
        if (pendingShareCleanup) {
            shareHelper.cleanupPendingFiles()
            pendingShareCleanup = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)
        shareHelper = ShareHelper(this)

        setupToolbar()
        setupRecyclerView()
        setupButtons()
        loadSessions()
    }

    override fun onResume() {
        super.onResume()
        // Refresh the list when returning to this activity
        loadSessions()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = SessionAdapter(
            currentSessionId = sessionManager.getCurrentSession().id,
            onSelectClick = { session -> selectSession(session) },
            onEditClick = { session -> showEditDialog(session) },
            onShareClick = { session -> shareSession(session) },
            onDeleteClick = { session -> confirmDeleteSession(session) }
        )

        binding.sessionsList.apply {
            layoutManager = LinearLayoutManager(this@SessionsActivity)
            adapter = this@SessionsActivity.adapter
        }
    }

    private fun setupButtons() {
        binding.newSessionButton.setOnClickListener {
            createNewSession()
        }
    }

    private fun loadSessions() {
        val sessions = sessionManager.getAllSessions()

        if (sessions.isEmpty()) {
            binding.sessionsList.visibility = View.GONE
            binding.emptyStateText.visibility = View.VISIBLE
        } else {
            binding.sessionsList.visibility = View.VISIBLE
            binding.emptyStateText.visibility = View.GONE
            adapter.submitList(sessions)
            adapter.updateCurrentSession(sessionManager.getCurrentSession().id)
        }
    }

    private fun selectSession(session: Session) {
        val success = sessionManager.setCurrentSession(session)
        if (success) {
            Toast.makeText(this, "Switched to ${session.name}", Toast.LENGTH_SHORT).show()
            loadSessions()
        } else {
            Toast.makeText(this, "Failed to switch session", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditDialog(session: Session) {
        val editText = EditText(this).apply {
            setText(session.name)
            setSelection(text.length) // Move cursor to end
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Rename Session")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotBlank()) {
                    renameSession(session, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameSession(session: Session, newName: String) {
        val renamed = sessionManager.renameSession(session, newName)
        if (renamed != null) {
            Toast.makeText(this, "Session renamed", Toast.LENGTH_SHORT).show()
            loadSessions()
        } else {
            Toast.makeText(this, "Failed to rename session", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createNewSession() {
        AlertDialog.Builder(this)
            .setTitle("New Session")
            .setMessage("Start a new recording session? The current session will be ended.")
            .setPositiveButton("Create") { _, _ ->
                sessionManager.createNewSession()
                loadSessions()
                Toast.makeText(this, "New session created", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareSession(session: Session) {
        val shareIntent = shareHelper.shareSession(session) { error ->
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        }

        if (shareIntent != null) {
            pendingShareCleanup = true
            shareResultLauncher.launch(shareIntent)
        }
    }

    private fun confirmDeleteSession(session: Session) {
        val count = session.getRecordingCount(this)
        AlertDialog.Builder(this)
            .setTitle("Delete Session")
            .setMessage("Delete \"${session.name}\" and its $count recording${if (count != 1) "s" else ""}?\n\nThis cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                deleteSession(session)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSession(session: Session) {
        val success = sessionManager.deleteSession(session)
        if (success) {
            Toast.makeText(this, "Session deleted", Toast.LENGTH_SHORT).show()
            loadSessions()
        } else {
            Toast.makeText(this, "Failed to delete session", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up any remaining cached zip files
        shareHelper.cleanupAllShareCache()
    }
}
