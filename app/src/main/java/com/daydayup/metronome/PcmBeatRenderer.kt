package com.daydayup.metronome

/** Places each click on the PCM frame timeline instead of asking a timer to fire each beat. */
class PcmBeatRenderer(private val sampleRate: Int, private val click: ShortArray, initialBpm: Int) {
    private var bpm = initialBpm
    private var frame = 0L
    private var nextBeatFrame = 0.0
    private var clickFrame = click.size

    init {
        require(sampleRate > 0 && click.isNotEmpty() && initialBpm > 0)
    }

    fun setBpm(value: Int) {
        require(value > 0)
        if (value != bpm) {
            bpm = value
            nextBeatFrame = frame + framesPerBeat()
        }
    }

    fun render(output: ShortArray, volume: Float, audible: Boolean) {
        val gain = if (audible) volume.coerceIn(0f, 1f) else 0f
        for (index in output.indices) {
            if (frame >= nextBeatFrame) {
                clickFrame = 0
                do { nextBeatFrame += framesPerBeat() } while (frame >= nextBeatFrame)
            }
            output[index] = if (clickFrame < click.size) (click[clickFrame++].toInt() * gain).toInt().toShort() else 0
            frame++
        }
    }

    private fun framesPerBeat(): Double = sampleRate * 60.0 / bpm
}
