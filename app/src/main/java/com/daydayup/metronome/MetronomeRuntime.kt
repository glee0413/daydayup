package com.daydayup.metronome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object MetronomeRuntime {
    var phase by mutableStateOf("idle")
    var bpm by mutableStateOf(180)
    var volume by mutableStateOf(0.65f)
    var soundEnabled by mutableStateOf(true)
    var audible by mutableStateOf(true)
    var elapsedMs by mutableStateOf(0L)
}
