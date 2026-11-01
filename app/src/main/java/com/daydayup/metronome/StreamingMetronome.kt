package com.daydayup.metronome

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.daydayup.R

/** A continuous shared audio stream; beat placement is determined by PCM frame position. */
class StreamingMetronome(context: Context, initialBpm: Int) {
    companion object { private const val TAG = "DayDayUpAudio" }

    @Volatile var bpm = initialBpm
    @Volatile var volume = 1f
    @Volatile var soundEnabled = true
    @Volatile var callMuted = false
    @Volatile private var running = false

    private val sampleRate = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it in 8_000..192_000 } ?: 48_000
    private val click = context.resources.openRawResource(R.raw.click).use { PcmClick.fromWav(it.readBytes(), sampleRate) }
    private val minimumBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private val bufferBytes = maxOf(minimumBytes.coerceAtLeast(0), sampleRate / 10 * 2)
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(bufferBytes)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build()
    private val writer = Thread(::writeLoop, "daydayup-pcm")

    fun start() {
        check(track.state == AudioTrack.STATE_INITIALIZED)
        running = true
        track.play()
        Log.i(TAG, "PCM started rate=$sampleRate bufferFrames=${track.bufferSizeInFrames} mode=${track.performanceMode}")
        writer.start()
    }

    fun stop() {
        running = false
        runCatching { track.pause(); track.flush() }
        writer.interrupt()
    }

    private fun writeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val renderer = PcmBeatRenderer(sampleRate, click, bpm)
        val pcm = ShortArray((sampleRate / 100).coerceAtLeast(1))
        var lastCheck = android.os.SystemClock.elapsedRealtime()
        var lastUnderruns = 0
        try {
            while (running) {
                renderer.setBpm(bpm)
                renderer.render(pcm, volume, soundEnabled && !callMuted)
                var written = 0
                while (running && written < pcm.size) {
                    val count = track.write(pcm, written, pcm.size - written, AudioTrack.WRITE_BLOCKING)
                    if (count <= 0) {
                        if (running) Log.e(TAG, "PCM write failed: $count")
                        return
                    }
                    written += count
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastCheck >= 5_000L) {
                    val underruns = track.underrunCount
                    if (underruns > lastUnderruns) Log.w(TAG, "PCM underruns +${underruns - lastUnderruns}")
                    lastUnderruns = underruns
                    lastCheck = now
                }
            }
        } catch (e: IllegalStateException) {
            if (running) Log.e(TAG, "PCM stream stopped unexpectedly", e)
        } finally {
            track.release()
        }
    }
}
