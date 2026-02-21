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
import com.lsl.kotlin_agent_app.databinding.ItemAgentsSeparatorBinding

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

    sealed class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        class Entry(val binding: ItemAgentsEntryBinding) : VH(binding.root)
        class Separator(val binding: ItemAgentsSeparatorBinding) : VH(binding.root)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return if (viewType == VIEW_TYPE_SEPARATOR) {
            val binding = ItemAgentsSeparatorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            VH.Separator(binding)
        } else {
            val binding = ItemAgentsEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            VH.Entry(binding)
        }
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return if (item.type == AgentsDirEntryType.File && item.name == RADIOS_SEPARATOR_NAME) {
            VIEW_TYPE_SEPARATOR
        } else {
            VIEW_TYPE_ENTRY
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        when (holder) {
            is VH.Separator -> {
                holder.binding.textLabel.text = item.displayName ?: "其他"
            }
            is VH.Entry -> {
                holder.binding.entryName.text = item.displayName ?: item.name
                val subtitle = item.subtitle?.trim().orEmpty()
                holder.binding.entrySubtitle.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
                holder.binding.entrySubtitle.text = subtitle
                val emoji = item.iconEmoji?.trim()?.ifBlank { null }
                if (emoji != null) {
                    holder.binding.entryIcon.visibility = View.GONE
                    holder.binding.entryIconText.visibility = View.VISIBLE
                    holder.binding.entryIconText.text = emoji
                } else {
                    holder.binding.entryIconText.visibility = View.GONE
                    holder.binding.entryIcon.visibility = View.VISIBLE
                    holder.binding.entryIcon.setImageResource(
                        if (item.type == AgentsDirEntryType.Dir) R.drawable.ic_folder_24 else R.drawable.ic_insert_drive_file_24
                    )
                }
                holder.binding.root.setOnClickListener { onClick(item) }
                holder.binding.root.setOnLongClickListener {
                    onLongClick(item)
                    true
                }
            }
        }
    }

    private companion object {
        private const val VIEW_TYPE_ENTRY = 0
        private const val VIEW_TYPE_SEPARATOR = 1
        private const val RADIOS_SEPARATOR_NAME = "__separator_radios__"
    }
}
