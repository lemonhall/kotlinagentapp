package com.lsl.kotlin_agent_app.ui.chat

import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.lsl.kotlin_agent_app.ui.markdown.MarkwonProvider

@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    textSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val markwon = remember(context.applicationContext) { MarkwonProvider.get(context) }
    val textColor = color.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                movementMethod = LinkMovementMethod.getInstance()
                linksClickable = true
                setTextIsSelectable(true)
            }
        },
        update = { tv ->
            tv.setTextColor(textColor)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            // Avoid crashing on empty content; Markwon handles empty, but keep it cheap.
            val md = markdown
            if (md.isBlank()) {
                tv.text = ""
            } else {
                markwon.setMarkdown(tv, md)
            }
        },
    )
}

