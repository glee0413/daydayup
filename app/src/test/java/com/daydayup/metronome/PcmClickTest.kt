package com.daydayup.metronome

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PcmClickTest {
    @Test fun retainsClickShapeWhenResamplingToOutputRate() {
        val wav = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(42).put("WAVEfmt ".toByteArray())
        wav.putInt(16).putShort(1.toShort()).putShort(1.toShort()).putInt(24_000)
        wav.putInt(48_000).putShort(2.toShort()).putShort(16.toShort())
        wav.put("data".toByteArray()).putInt(6)
        wav.putShort(0.toShort()).putShort(10_000.toShort()).putShort(0.toShort())

        assertArrayEquals(shortArrayOf(0, 5_000, 10_000, 5_000, 0, 0), PcmClick.fromWav(wav.array(), 48_000))
    }
}
