package com.rinaemon.pitchshifter

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.pow

/**
 * Shared in-process state between [MainActivity] (UI) and [CaptureService] (audio pipeline).
 * Both run in the same process, so a simple singleton with StateFlow fields is sufficient —
 * no IPC/Binder plumbing needed.
 */
object PitchController {
    val isCapturing = MutableStateFlow(false)
    val semitones = MutableStateFlow(0f)
    val muteOriginal = MutableStateFlow(false)
    val statusMessage = MutableStateFlow("停止中")

    /** Current pitch ratio derived from [semitones] (1.0 = unchanged, 2.0 = +1 octave). */
    fun pitchRatio(): Float = 2f.pow(semitones.value / 12f)
}
