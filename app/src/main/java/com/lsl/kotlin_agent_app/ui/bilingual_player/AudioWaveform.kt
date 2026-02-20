package com.lsl.kotlin_agent_app.ui.bilingual_player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun AudioWaveform(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI.toFloat()),
            animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing), repeatMode = RepeatMode.Restart),
            label = "phase",
        )

    val bars = remember { 7 }
    val minScale = 0.25f
    val maxScale = 1.0f

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val gap = w / (bars * 2f)
        val barW = gap
        val baseY = h
        val centerX0 = gap

        for (i in 0 until bars) {
            val x = centerX0 + i * (barW + gap)
            val t = (i.toFloat() / bars.toFloat())
            val s = sin(phase + t * 2f * PI.toFloat()).toFloat()
            val scale =
                if (isPlaying) {
                    (minScale + (s + 1f) / 2f * (maxScale - minScale)).coerceIn(minScale, maxScale)
                } else {
                    minScale
                }
            val barH = h * scale
            drawLine(
                color = color,
                start = Offset(x, baseY),
                end = Offset(x, (baseY - barH).coerceAtLeast(0f)),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

