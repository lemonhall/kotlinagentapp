package com.lsl.kotlin_agent_app.ui.dashboard

import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.lsl.kotlin_agent_app.media.lyrics.LrcLine

class LyricsLineAdapter : RecyclerView.Adapter<LyricsLineAdapter.VH>() {
    private var lines: List<LrcLine> = emptyList()
    private var highlightedIndex: Int = -1

    fun submitLines(newLines: List<LrcLine>) {
        lines = newLines
        highlightedIndex = -1
        notifyDataSetChanged()
    }

    fun setHighlightedIndex(index: Int) {
        if (index == highlightedIndex) return
        val prev = highlightedIndex
        highlightedIndex = index
        if (prev in lines.indices) notifyItemChanged(prev)
        if (index in lines.indices) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv =
            TextView(parent.context).apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSurface,
                        android.graphics.Color.BLACK,
                    ),
                )
            }
        return VH(tv)
    }

    override fun getItemCount(): Int = lines.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val line = lines[position]
        val tv = holder.tv
        tv.text = line.text
        val isHighlighted = position == highlightedIndex
        tv.setTypeface(null, if (isHighlighted) Typeface.BOLD else Typeface.NORMAL)
        tv.alpha = if (isHighlighted) 1.0f else 0.7f
    }

    class VH(
        val tv: TextView,
    ) : RecyclerView.ViewHolder(tv)

    private fun dp(value: Int): Int {
        val d = (android.content.res.Resources.getSystem().displayMetrics.density).coerceAtLeast(1f)
        return (value * d).toInt()
    }
}

