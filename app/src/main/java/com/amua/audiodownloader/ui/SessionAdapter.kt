package com.amua.audiodownloader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.amua.audiodownloader.R
import com.amua.audiodownloader.session.Session
import com.google.android.material.button.MaterialButton

/**
 * RecyclerView adapter for displaying sessions.
 */
class SessionAdapter(
    private val currentSessionId: String?,
    private val onSelectClick: (Session) -> Unit,
    private val onEditClick: (Session) -> Unit,
    private val onShareClick: (Session) -> Unit,
    private val onDeleteClick: (Session) -> Unit
) : ListAdapter<Session, SessionAdapter.SessionViewHolder>(SessionDiffCallback()) {

    private var _currentSessionId: String? = currentSessionId

    fun updateCurrentSession(sessionId: String?) {
        val oldId = _currentSessionId
        _currentSessionId = sessionId

        // Update affected items
        currentList.forEachIndexed { index, session ->
            if (session.id == oldId || session.id == sessionId) {
                notifyItemChanged(index)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session, parent, false)
        return SessionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        val session = getItem(position)
        val isCurrent = session.id == _currentSessionId
        holder.bind(session, isCurrent, onSelectClick, onEditClick, onShareClick, onDeleteClick)
    }

    class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val sessionName: TextView = itemView.findViewById(R.id.sessionName)
        private val sessionDate: TextView = itemView.findViewById(R.id.sessionDate)
        private val recordingCount: TextView = itemView.findViewById(R.id.recordingCount)
        private val sessionSize: TextView = itemView.findViewById(R.id.sessionSize)
        private val currentBadge: TextView = itemView.findViewById(R.id.currentBadge)
        private val selectButton: MaterialButton = itemView.findViewById(R.id.selectButton)
        private val editButton: MaterialButton = itemView.findViewById(R.id.editButton)
        private val shareButton: MaterialButton = itemView.findViewById(R.id.shareButton)
        private val deleteButton: MaterialButton = itemView.findViewById(R.id.deleteButton)

        fun bind(
            session: Session,
            isCurrent: Boolean,
            onSelectClick: (Session) -> Unit,
            onEditClick: (Session) -> Unit,
            onShareClick: (Session) -> Unit,
            onDeleteClick: (Session) -> Unit
        ) {
            val context = itemView.context
            sessionName.text = session.name
            sessionDate.text = session.getFormattedDate()

            val count = session.getRecordingCount(context)
            recordingCount.text = "$count recording${if (count != 1) "s" else ""}"
            sessionSize.text = session.getFormattedSize(context)

            currentBadge.visibility = if (isCurrent) View.VISIBLE else View.GONE

            // Hide select button if already current session
            selectButton.visibility = if (isCurrent) View.GONE else View.VISIBLE
            selectButton.setOnClickListener { onSelectClick(session) }

            // Edit button
            editButton.setOnClickListener { onEditClick(session) }

            // Disable share if no recordings
            shareButton.isEnabled = count > 0
            shareButton.alpha = if (count > 0) 1.0f else 0.4f
            shareButton.setOnClickListener { onShareClick(session) }

            // Disable delete for current session
            deleteButton.isEnabled = !isCurrent
            deleteButton.alpha = if (!isCurrent) 1.0f else 0.4f
            deleteButton.setOnClickListener { onDeleteClick(session) }
        }
    }
}

class SessionDiffCallback : DiffUtil.ItemCallback<Session>() {
    override fun areItemsTheSame(oldItem: Session, newItem: Session): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Session, newItem: Session): Boolean {
        // Only compare properties that don't require context
        // Recording count/size changes will be reflected when list is resubmitted
        return oldItem.id == newItem.id &&
                oldItem.name == newItem.name &&
                oldItem.createdAt == newItem.createdAt
    }
}
