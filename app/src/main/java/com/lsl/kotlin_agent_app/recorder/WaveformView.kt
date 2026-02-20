package com.lsl.kotlin_agent_app.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min

internal class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val levels = FloatArray(48) { 0f }
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeWidth = resources.displayMetrics.density * 3f
            color = MaterialColors.getColor(this@WaveformView, androidx.appcompat.R.attr.colorPrimary)
        }

    fun pushLevel(level01: Float) {
        val v = level01.coerceIn(0f, 1f)
        for (i in 0 until levels.size - 1) {
            levels[i] = levels[i + 1]
        }
        levels[levels.size - 1] = v
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val mid = h / 2f

        val n = levels.size
        val step = w / max(1, n)
        val minBar = (resources.displayMetrics.density * 4f).coerceAtLeast(2f)
        val maxBar = (h * 0.9f).coerceAtLeast(minBar)

        for (i in 0 until n) {
            val x = (i + 0.5f) * step
            val amp = levels[i]
            val bar = min(maxBar, max(minBar, minBar + amp * (maxBar - minBar)))
            canvas.drawLine(x, mid - bar / 2f, x, mid + bar / 2f, paint)
        }
    }
}
