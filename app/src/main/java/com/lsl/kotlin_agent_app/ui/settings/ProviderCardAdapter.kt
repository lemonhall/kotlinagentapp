package com.lsl.kotlin_agent_app.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsl.kotlin_agent_app.config.ModelFetcher
import com.lsl.kotlin_agent_app.config.ProviderEntry
import com.lsl.kotlin_agent_app.databinding.ItemProviderCardBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ProviderCardAdapter(
    private val scope: CoroutineScope,
    private val activeProviderId: () -> String,
    private val onDelete: (ProviderEntry) -> Unit,
    private val onChanged: (ProviderEntry) -> Unit,
) : ListAdapter<ProviderEntry, ProviderCardAdapter.VH>(Diff) {

    private val expandedIds = mutableSetOf<String>()

    object Diff : DiffUtil.ItemCallback<ProviderEntry>() {
        override fun areItemsTheSame(a: ProviderEntry, b: ProviderEntry) = a.id == b.id
        override fun areContentsTheSame(a: ProviderEntry, b: ProviderEntry) = a == b
    }

    inner class VH(val binding: ItemProviderCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemProviderCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        val b = holder.binding
        val isActive = entry.id == activeProviderId()
        val isExpanded = entry.id in expandedIds

        b.textName.text = entry.displayName
        b.textModelChip.text = entry.selectedModel.ifBlank { "(no model)" }
        b.iconActive.visibility = if (isActive) View.VISIBLE else View.GONE
        b.body.visibility = if (isExpanded) View.VISIBLE else View.GONE

        b.header.setOnClickListener {
            if (isExpanded) expandedIds.remove(entry.id) else expandedIds.add(entry.id)
            notifyItemChanged(holder.bindingAdapterPosition)
        }

        // Body fields
        b.chipType.text = entry.type.label
        b.inputBaseUrl.setText(entry.baseUrl)
        b.inputApiKey.setText(entry.apiKey)

        // Model dropdown
        val modelAdapter = ArrayAdapter(
            b.root.context, android.R.layout.simple_dropdown_item_1line,
            entry.models.toMutableList(),
        )
        b.inputModel.setAdapter(modelAdapter)
        b.inputModel.setText(entry.selectedModel, false)

        // Show fetch button only for supported types
        b.btnFetchModels.visibility =
            if (ModelFetcher.supportsModelFetching(entry.type)) View.VISIBLE else View.GONE

        b.btnFetchModels.setOnClickListener {
            val url = b.inputBaseUrl.text?.toString().orEmpty().trim()
            val key = b.inputApiKey.text?.toString().orEmpty().trim()
            if (url.isBlank() || key.isBlank()) {
                Toast.makeText(b.root.context, "Fill Base URL and API Key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            b.btnFetchModels.isEnabled = false
            scope.launch {
                runCatching { ModelFetcher.fetchModels(url, key) }
                    .onSuccess { models ->
                        val updated = entry.copy(models = models)
                        modelAdapter.clear()
                        modelAdapter.addAll(models)
                        modelAdapter.notifyDataSetChanged()
                        onChanged(updated)
                        Toast.makeText(b.root.context, "${models.size} models loaded", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { e ->
                        Toast.makeText(b.root.context, "Fetch failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                b.btnFetchModels.isEnabled = true
            }
        }

        b.btnDelete.setOnClickListener { onDelete(entry) }

        // Propagate edits back on focus loss
        val focusWatcher = View.OnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) {
                val updated = entry.copy(
                    baseUrl = b.inputBaseUrl.text?.toString().orEmpty(),
                    apiKey = b.inputApiKey.text?.toString().orEmpty(),
                    selectedModel = b.inputModel.text?.toString().orEmpty(),
                )
                if (updated != entry) onChanged(updated)
            }
        }
        b.inputBaseUrl.onFocusChangeListener = focusWatcher
        b.inputApiKey.onFocusChangeListener = focusWatcher
        b.inputModel.onFocusChangeListener = focusWatcher
    }
}
