package com.lsl.kotlin_agent_app.translation

import com.lsl.kotlin_agent_app.radio_transcript.TranscriptSegment
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal object TranslationPromptBuilder {
    fun buildResponsesInput(
        segments: List<TranscriptSegment>,
        context: List<TranslatedSegment>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<JsonObject> {
        val sys =
            buildJsonObject {
                put("role", JsonPrimitive("system"))
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("input_text"))
                                put(
                                    "text",
                                    JsonPrimitive(
                                        """
                                        You are a translation engine.
                                        Translate from $sourceLanguage to $targetLanguage.
                                        Output MUST be valid JSON (no markdown, no code fences).
                                        Output format: a JSON array of objects, one per input segment:
                                        [{ "id": <int>, "translatedText": <string> }, ...]
                                        Rules:
                                        - Keep the same number of items as input.
                                        - Preserve ids exactly; do not reorder.
                                        - translatedText must be in $targetLanguage.
                                        """.trimIndent(),
                                    ),
                                )
                            },
                        )
                    },
                )
            }

        val user =
            buildJsonObject {
                put("role", JsonPrimitive("user"))
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("input_text"))
                                put(
                                    "text",
                                    JsonPrimitive(renderPayload(segments = segments, context = context)),
                                )
                            },
                        )
                    },
                )
            }

        return listOf(sys, user)
    }

    private fun renderPayload(
        segments: List<TranscriptSegment>,
        context: List<TranslatedSegment>,
    ): String {
        val ctx =
            if (context.isEmpty()) {
                "[]"
            } else {
                context.joinToString(
                    prefix = "[\n",
                    postfix = "\n]",
                    separator = ",\n",
                ) { s ->
                    val src = s.sourceText.replace("\n", " ").trim()
                    val tgt = s.translatedText.replace("\n", " ").trim()
                    """{"id":${s.id},"sourceText":${jsonString(src)},"translatedText":${jsonString(tgt)}}"""
                }
            }

        val segs =
            if (segments.isEmpty()) {
                "[]"
            } else {
                segments.joinToString(
                    prefix = "[\n",
                    postfix = "\n]",
                    separator = ",\n",
                ) { s ->
                    val t = s.text.replace("\n", " ").trim()
                    """{"id":${s.id},"text":${jsonString(t)}}"""
                }
            }

        return """
            Context (previous translations, may be empty):
            $ctx

            Segments to translate (JSON array):
            $segs
        """.trimIndent()
    }

    private fun jsonString(s: String): String {
        val escaped =
            buildString(s.length + 16) {
                append('"')
                for (ch in s) {
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(ch)
                    }
                }
                append('"')
            }
        return escaped
    }
}
