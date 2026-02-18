package com.lsl.kotlin_agent_app.ui.terminal

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lsl.kotlin_agent_app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TerminalRunsAdapter(
    private val onClick: (TerminalRunResult) -> Unit,
) : RecyclerView.Adapter<TerminalRunsAdapter.Vh>() {

    private val items = mutableListOf<TerminalRunResult>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    fun submit(list: List<TerminalRunResult>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_terminal_run, parent, false)
        return Vh(v, onClick, fmt)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class Vh(
        itemView: View,
        private val onClick: (TerminalRunResult) -> Unit,
        private val fmt: SimpleDateFormat,
    ) : RecyclerView.ViewHolder(itemView) {
        private val textTime: TextView = itemView.findViewById(R.id.text_time)
        private val textExit: TextView = itemView.findViewById(R.id.text_exit)
        private val textCmd: TextView = itemView.findViewById(R.id.text_command)

        fun bind(item: TerminalRunResult) {
            val s = item.summary
            textTime.text = fmt.format(Date(s.timestampMs))
            textExit.text = "exit ${s.exitCode}"
            textCmd.text = s.command

            val color =
                if (s.exitCode == 0) {
                    itemView.context.getColor(R.color.teal_700)
                } else {
                    itemView.context.getColor(android.R.color.holo_red_dark)
                }
            textExit.setTextColor(color)

            itemView.setOnClickListener { onClick(item) }
        }
    }
}

