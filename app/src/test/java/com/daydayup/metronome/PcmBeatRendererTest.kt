package com.daydayup.metronome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmBeatRendererTest {
    @Test fun beatsAt180BpmAreExactly16000FramesApartAt48kHz() {
        val renderer = PcmBeatRenderer(48_000, shortArrayOf(12_000), 180)
        val pcm = ShortArray(48_001)
        renderer.render(pcm, 1f, true)

        assertEquals(listOf(0, 16_000, 32_000, 48_000), onsets(pcm))
    }

    @Test fun fractionalFrameIntervalsDoNotDriftOverOneMinute() {
        val renderer = PcmBeatRenderer(48_000, shortArrayOf(12_000), 175)
        val chunk = ShortArray(480)
        val onsets = mutableListOf<Int>()
        repeat(6_000) { block ->
            renderer.render(chunk, 1f, true)
            onsets += onsets(chunk).map { block * chunk.size + it }
        }

        assertEquals(175, onsets.size)
        onsets.forEachIndexed { beat, frame ->
            assertTrue("beat $beat at $frame", kotlin.math.abs(frame - beat * 48_000.0 * 60 / 175) <= 1.0)
        }
    }

    @Test fun muteKeepsCadenceAndBpmChangeSchedulesTheNextBeat() {
        val renderer = PcmBeatRenderer(48_000, shortArrayOf(12_000), 180)
        val muted = ShortArray(16_000)
        renderer.render(muted, 1f, false)
        assertTrue(onsets(muted).isEmpty())

        val audible = ShortArray(16_001)
        renderer.render(audible, 0.5f, true)
        assertEquals(listOf(0, 16_000), onsets(audible))
        assertEquals(6_000, audible[0].toInt())

        renderer.setBpm(200)
        val changed = ShortArray(14_401)
        renderer.render(changed, 1f, true)
        assertEquals(listOf(14_400), onsets(changed))
    }

    private fun onsets(samples: ShortArray) = samples.indices.filter { samples[it] != 0.toShort() }
}
