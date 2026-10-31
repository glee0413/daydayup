package com.daydayup.metronome

object Cadence {
    const val MIN = 160
    const val MAX = 220
    fun isValid(value: Int): Boolean = value in MIN..MAX
}

data class RateChange(val elapsedMs: Long, val bpm: Int)

class MetronomeSession(initialBpm: Int, private val startedAtMs: Long) {
    init { require(Cadence.isValid(initialBpm)) }

    var bpm: Int = initialBpm
        private set
    var paused: Boolean = false
        private set
    private var pausedAtMs: Long = 0
    private var totalPausedMs: Long = 0
    private var endedAtMs: Long? = null
    val changes = mutableListOf<RateChange>()

    fun elapsedMs(nowMs: Long): Long {
        val end = endedAtMs ?: if (paused) pausedAtMs else nowMs
        return (end - startedAtMs - totalPausedMs).coerceAtLeast(0)
    }

    fun pause(nowMs: Long) {
        if (!paused && endedAtMs == null) {
            pausedAtMs = nowMs
            paused = true
        }
    }

    fun resume(nowMs: Long) {
        if (paused && endedAtMs == null) {
            totalPausedMs += (nowMs - pausedAtMs).coerceAtLeast(0)
            paused = false
        }
    }

    fun changeBpm(value: Int, nowMs: Long): Boolean {
        if (!Cadence.isValid(value) || endedAtMs != null) return false
        if (value != bpm) {
            bpm = value
            changes.add(RateChange(elapsedMs(nowMs), value))
        }
        return true
    }

    fun stop(nowMs: Long): Long {
        if (endedAtMs == null) endedAtMs = if (paused) pausedAtMs else nowMs
        return elapsedMs(nowMs)
    }
}
