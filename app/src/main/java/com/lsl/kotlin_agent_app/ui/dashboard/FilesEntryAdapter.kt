package com.lsl.kotlin_agent_app.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsl.kotlin_agent_app.agent.AgentsDirEntry
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import com.lsl.kotlin_agent_app.R
import com.lsl.kotlin_agent_app.databinding.ItemAgentsEntryBinding

class FilesEntryAdapter(
    private val onClick: (AgentsDirEntry) -> Unit,
    private val onLongClick: (AgentsDirEntry) -> Unit,
) : ListAdapter<AgentsDirEntry, FilesEntryAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<AgentsDirEntry>() {
        override fun areItemsTheSame(oldItem: AgentsDirEntry, newItem: AgentsDirEntry): Boolean {
            return oldItem.name == newItem.name && oldItem.type == newItem.type
        }

        override fun areContentsTheSame(oldItem: AgentsDirEntry, newItem: AgentsDirEntry): Boolean {
            return oldItem == newItem
        }
    }

    class VH(val binding: ItemAgentsEntryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAgentsEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.binding.entryName.text = item.displayName ?: item.name
        val subtitle = item.subtitle?.trim().orEmpty()
        holder.binding.entrySubtitle.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        holder.binding.entrySubtitle.text = subtitle
        holder.binding.entryIcon.setImageResource(
            if (item.type == AgentsDirEntryType.Dir) R.drawable.ic_folder_24 else R.drawable.ic_insert_drive_file_24
        )
        holder.binding.root.setOnClickListener { onClick(item) }
        holder.binding.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }
}
