package com.daydayup.metronome

import org.junit.Assert.*
import org.junit.Test

class MetronomeSessionTest {
    @Test fun cadenceBounds() {
        assertTrue(Cadence.isValid(160))
        assertTrue(Cadence.isValid(220))
        assertFalse(Cadence.isValid(159))
        assertFalse(Cadence.isValid(221))
    }

    @Test fun pauseExcludesTimeAndChangeRecordsElapsedOffset() {
        val session = MetronomeSession(160, 1_000L)
        session.pause(4_000L)
        assertEquals(3_000L, session.elapsedMs(100_000L))
        session.resume(9_000L)
        assertTrue(session.changeBpm(175, 10_000L))
        assertFalse(session.changeBpm(300, 11_000L))
        assertEquals(175, session.bpm)
        assertEquals(4_000L, session.changes.single().elapsedMs)
        assertEquals(5_000L, session.elapsedMs(11_000L))
    }
}
