package com.lsl.kotlin_agent_app.ui.dashboard

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal object TranslationLanguagePickerDialog {
    internal data class Lang(
        val code: String,
        val label: String,
    )

    private val languages =
        listOf(
            Lang(code = "zh", label = "🇨🇳 中文"),
            Lang(code = "ja", label = "🇯🇵 日语"),
            Lang(code = "ko", label = "🇰🇷 韩语"),
            Lang(code = "en", label = "🇬🇧 英语"),
            Lang(code = "fr", label = "🇫🇷 法语"),
            Lang(code = "de", label = "🇩🇪 德语"),
            Lang(code = "es", label = "🇪🇸 西班牙语"),
            Lang(code = "ru", label = "🇷🇺 俄语"),
            Lang(code = "it", label = "🇮🇹 意大利语"),
            Lang(code = "ar", label = "🇸🇦 阿拉伯语"),
            Lang(code = "pt", label = "🇧🇷 葡萄牙语"),
        )

    fun show(
        context: Context,
        title: String = "选择目标语言",
        onPicked: (Lang) -> Unit,
    ) {
        val items = languages.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setItems(items) { _, which ->
                val picked = languages.getOrNull(which) ?: return@setItems
                onPicked(picked)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

