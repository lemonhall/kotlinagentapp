package com.lsl.kotlin_agent_app.media

import kotlin.random.Random

class PlaybackQueueController(
    private val random: Random = Random.Default,
) {
    private var items: List<String> = emptyList()
    private var index: Int = -1
    private var mode: MusicPlaybackMode = MusicPlaybackMode.SequentialLoop

    private var shuffleOrder: IntArray? = null
    private var shufflePos: Int = -1

    fun snapshot(): PlaybackQueueSnapshot {
        return PlaybackQueueSnapshot(
            items = items,
            index = index.takeIf { it >= 0 },
            mode = mode,
        )
    }

    fun setMode(newMode: MusicPlaybackMode) {
        if (mode == newMode) return
        mode = newMode
        if (mode == MusicPlaybackMode.ShuffleLoop) {
            ensureShuffleOrder()
        }
    }

    fun setQueue(
        newItems: List<String>,
        current: String,
    ): PlaybackQueueSnapshot {
        items = newItems
        index = newItems.indexOfFirst { it == current }.takeIf { it >= 0 } ?: 0
        shuffleOrder = null
        shufflePos = -1
        if (mode == MusicPlaybackMode.ShuffleLoop) ensureShuffleOrder()
        return snapshot()
    }

    fun clear() {
        items = emptyList()
        index = -1
        shuffleOrder = null
        shufflePos = -1
    }

    fun manualNextIndex(): Int? = computeNextIndex(onEnded = false)

    fun manualPrevIndex(): Int? = computePrevIndex()

    fun onEndedNextIndex(): Int? = computeNextIndex(onEnded = true)

    private fun computeNextIndex(onEnded: Boolean): Int? {
        if (items.isEmpty()) return null
        if (index < 0) index = 0

        if (onEnded && mode == MusicPlaybackMode.PlayOnce) return null
        if (onEnded && mode == MusicPlaybackMode.RepeatOne) return index

        val next =
            if (mode == MusicPlaybackMode.ShuffleLoop) {
            ensureShuffleOrder()
            advanceShuffle(1)
        } else {
            val next = (index + 1) % items.size
            next
        }
        index = next
        if (mode == MusicPlaybackMode.ShuffleLoop) {
            shufflePos = shuffleOrder?.indexOf(index)?.takeIf { it >= 0 } ?: shufflePos
        }
        return next
    }

    private fun computePrevIndex(): Int? {
        if (items.isEmpty()) return null
        if (index < 0) index = 0

        val prev =
            if (mode == MusicPlaybackMode.ShuffleLoop) {
            ensureShuffleOrder()
            advanceShuffle(-1)
        } else {
            if (index - 1 < 0) items.size - 1 else index - 1
        }
        index = prev
        if (mode == MusicPlaybackMode.ShuffleLoop) {
            shufflePos = shuffleOrder?.indexOf(index)?.takeIf { it >= 0 } ?: shufflePos
        }
        return prev
    }

    private fun ensureShuffleOrder() {
        val size = items.size
        if (size <= 0) {
            shuffleOrder = null
            shufflePos = -1
            return
        }
        if (shuffleOrder != null && shuffleOrder?.size == size && shufflePos in 0 until size) return

        val order = (0 until size).toMutableList()
        for (i in size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = order[i]
            order[i] = order[j]
            order[j] = tmp
        }
        shuffleOrder = order.toIntArray()
        shufflePos = order.indexOf(index).takeIf { it >= 0 } ?: 0
    }

    private fun advanceShuffle(delta: Int): Int {
        val order = shuffleOrder ?: intArrayOf(index)
        val size = order.size
        if (size <= 1) return 0

        val newPos =
            when {
                delta > 0 -> (shufflePos + 1) % size
                delta < 0 -> if (shufflePos - 1 < 0) size - 1 else shufflePos - 1
                else -> shufflePos
            }
        shufflePos = newPos
        return order[newPos]
    }
}

data class PlaybackQueueSnapshot(
    val items: List<String>,
    val index: Int?,
    val mode: MusicPlaybackMode,
)
