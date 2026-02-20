package com.lsl.kotlin_agent_app.radio_bilingual.player

import com.lsl.kotlin_agent_app.agent.AgentsWorkspace
import com.lsl.kotlin_agent_app.agent.AgentsDirEntryType
import com.lsl.kotlin_agent_app.radio_transcript.TranscriptChunkV1
import com.lsl.kotlin_agent_app.radio_translation.TranslationChunkV1
import java.io.File

internal class BilingualSessionLoader(
    private val workspace: AgentsWorkspace,
    private val durationReader: ChunkDurationReader,
) {
    interface ChunkDurationReader {
        fun readDurationMs(file: File): Long?
    }

    data class Chunk(
        val index: Int,
        val file: File,
        val durationMs: Long,
    )

    data class LoadedSession(
        val sessionId: String,
        val chunks: List<Chunk>,
        val timeline: SessionTimeline,
        val segments: List<SubtitleSyncEngine.SubtitleSegment>,
        val hadSubtitleLoadError: Boolean,
    )

    sealed class LoadError(message: String) : IllegalStateException(message) {
        class SessionNotFound(sessionId: String) : LoadError("SessionNotFound: $sessionId")

        class SessionNoChunks(sessionId: String) : LoadError("SessionNoChunks: $sessionId")
    }

    fun load(sessionId: String): LoadedSession {
        val sid = sessionId.trim()
        if (sid.isBlank()) throw LoadError.SessionNotFound(sessionId)
        val sessionDir = ".agents/workspace/radio_recordings/$sid"
        if (!workspace.exists(sessionDir)) throw LoadError.SessionNotFound(sid)

        val chunkRx = Regex("^chunk_(\\d{3})\\.ogg$", RegexOption.IGNORE_CASE)
        val chunkNames =
            workspace
                .listDir(sessionDir)
                .filter { it.type == AgentsDirEntryType.File }
                .mapNotNull { e ->
                    val m = chunkRx.matchEntire(e.name.trim()) ?: return@mapNotNull null
                    val idx = m.groupValues.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    idx to e.name.trim()
                }
                .sortedBy { it.first }

        if (chunkNames.isEmpty()) throw LoadError.SessionNoChunks(sid)

        val chunkSegments = linkedMapOf<Int, List<SubtitleSyncEngine.SubtitleSegment>>()
        var subtitleLoadError = false

        fun loadChunkSegments(chunkIndex: Int): List<SubtitleSyncEngine.SubtitleSegment> {
            val baseIdx = chunkIndex.toString().padStart(3, '0')
            val txPath = "$sessionDir/translations/chunk_${baseIdx}.translation.json"
            val trPath = "$sessionDir/transcripts/chunk_${baseIdx}.transcript.json"

            if (workspace.exists(txPath)) {
                val raw =
                    try {
                        workspace.readTextFile(txPath, maxBytes = 2L * 1024L * 1024L)
                    } catch (_: Throwable) {
                        subtitleLoadError = true
                        null
                    }
                if (raw != null) {
                    val parsed =
                        try {
                            TranslationChunkV1.parse(raw)
                        } catch (_: Throwable) {
                            subtitleLoadError = true
                            null
                        }
                    if (parsed != null) {
                        return parsed.segments
                            .sortedBy { it.startMs }
                            .map { s ->
                                SubtitleSyncEngine.SubtitleSegment(
                                    id = s.id,
                                    totalStartMs = s.startMs.coerceAtLeast(0L),
                                    totalEndMs = s.endMs.coerceAtLeast(0L),
                                    sourceText = s.sourceText,
                                    translatedText = s.translatedText,
                                    emotion = s.emotion,
                                )
                            }
                    }
                }
            }

            if (workspace.exists(trPath)) {
                val raw =
                    try {
                        workspace.readTextFile(trPath, maxBytes = 2L * 1024L * 1024L)
                    } catch (_: Throwable) {
                        subtitleLoadError = true
                        null
                    }
                if (raw != null) {
                    val parsed =
                        try {
                            TranscriptChunkV1.parse(raw)
                        } catch (_: Throwable) {
                            subtitleLoadError = true
                            null
                        }
                    if (parsed != null) {
                        return parsed.segments
                            .sortedBy { it.startMs }
                            .map { s ->
                                SubtitleSyncEngine.SubtitleSegment(
                                    id = s.id,
                                    totalStartMs = s.startMs.coerceAtLeast(0L),
                                    totalEndMs = s.endMs.coerceAtLeast(0L),
                                    sourceText = s.text,
                                    translatedText = null,
                                    emotion = s.emotion,
                                )
                            }
                    }
                }
            }

            return emptyList()
        }

        val chunks =
            chunkNames.map { (idx, name) ->
                val file = workspace.toFile("$sessionDir/$name")
                val segs = loadChunkSegments(idx)
                chunkSegments[idx] = segs

                val durFromMeta = durationReader.readDurationMs(file)?.coerceAtLeast(0L) ?: 0L
                val durFromSegs = segs.maxOfOrNull { it.totalEndMs }?.coerceAtLeast(0L) ?: 0L
                val durationMs = maxOf(durFromMeta, durFromSegs)
                Chunk(index = idx, file = file, durationMs = durationMs)
            }

        val timeline = SessionTimeline(chunks.map { it.durationMs })

        val offsetByChunkIdx =
            buildMap {
                chunks.forEachIndexed { i, c ->
                    put(c.index, timeline.chunkOffsetsMs.getOrNull(i) ?: 0L)
                }
            }

        val merged =
            chunks.flatMap { c ->
                val offset = offsetByChunkIdx[c.index] ?: 0L
                val segs = chunkSegments[c.index].orEmpty()
                segs.map { s ->
                    s.copy(
                        totalStartMs = (offset + s.totalStartMs).coerceAtLeast(0L),
                        totalEndMs = (offset + s.totalEndMs).coerceAtLeast(0L),
                    )
                }
            }.sortedBy { it.totalStartMs }

        return LoadedSession(
            sessionId = sid,
            chunks = chunks,
            timeline = timeline,
            segments = merged,
            hadSubtitleLoadError = subtitleLoadError,
        )
    }
}
