package com.daydayup.metronome

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object PcmClick {
    fun fromWav(bytes: ByteArray, outputRate: Int): ShortArray {
        require(outputRate > 0 && bytes.size >= 44)
        val wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        require(wav.getShort(20).toInt() == 1 && wav.getShort(22).toInt() == 1 && wav.getShort(34).toInt() == 16)
        val inputRate = wav.getInt(24)
        val dataSize = wav.getInt(40)
        require(inputRate > 0 && dataSize > 0 && 44 + dataSize <= bytes.size && dataSize % 2 == 0)
        val samples = ShortArray(dataSize / 2) { wav.getShort(44 + it * 2) }
        val count = (samples.size.toDouble() * outputRate / inputRate).roundToInt()
        return ShortArray(count) { index ->
            val source = index.toDouble() * inputRate / outputRate
            val left = source.toInt().coerceAtMost(samples.lastIndex)
            val right = (left + 1).coerceAtMost(samples.lastIndex)
            (samples[left] + (samples[right] - samples[left]) * (source - left)).roundToInt().toShort()
        }
    }
}
