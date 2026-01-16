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
        holder.bind(session, isCurrent, onShareClick, onDeleteClick)
    }

    class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val sessionName: TextView = itemView.findViewById(R.id.sessionName)
        private val sessionDate: TextView = itemView.findViewById(R.id.sessionDate)
        private val recordingCount: TextView = itemView.findViewById(R.id.recordingCount)
        private val sessionSize: TextView = itemView.findViewById(R.id.sessionSize)
        private val currentBadge: TextView = itemView.findViewById(R.id.currentBadge)
        private val shareButton: MaterialButton = itemView.findViewById(R.id.shareButton)
        private val deleteButton: MaterialButton = itemView.findViewById(R.id.deleteButton)

        fun bind(
            session: Session,
            isCurrent: Boolean,
            onShareClick: (Session) -> Unit,
            onDeleteClick: (Session) -> Unit
        ) {
            sessionName.text = session.name
            sessionDate.text = session.getFormattedDate()

            val count = session.getRecordingCount()
            recordingCount.text = "$count recording${if (count != 1) "s" else ""}"
            sessionSize.text = session.getFormattedSize()

            currentBadge.visibility = if (isCurrent) View.VISIBLE else View.GONE

            // Disable share if no recordings
            shareButton.isEnabled = count > 0
            shareButton.setOnClickListener { onShareClick(session) }

            // Disable delete for current session
            deleteButton.isEnabled = !isCurrent
            deleteButton.setOnClickListener { onDeleteClick(session) }
        }
    }
}

class SessionDiffCallback : DiffUtil.ItemCallback<Session>() {
    override fun areItemsTheSame(oldItem: Session, newItem: Session): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Session, newItem: Session): Boolean {
        return oldItem.id == newItem.id &&
                oldItem.getRecordingCount() == newItem.getRecordingCount() &&
                oldItem.getTotalSizeBytes() == newItem.getTotalSizeBytes()
    }
}
