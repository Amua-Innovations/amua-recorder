package com.amua.audiodownloader.ui

import android.os.Bundle
import android.view.View
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
        val count = session.getRecordingCount()
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
