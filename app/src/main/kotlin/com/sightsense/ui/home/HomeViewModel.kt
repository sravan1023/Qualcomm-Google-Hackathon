package com.sightsense.ui.home

import androidx.lifecycle.ViewModel
import com.sightsense.core.AudioDetection
import com.sightsense.core.SafetyAlert
import com.sightsense.core.SightSenseSnapshot
import com.sightsense.core.VisionDetection
import com.sightsense.ui.debug.DebugFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeViewModel : ViewModel() {
    private val _debug = MutableStateFlow(DebugFrame())
    val debug: StateFlow<DebugFrame> = _debug.asStateFlow()

    private val _snapshot = MutableStateFlow(SightSenseSnapshot())
    val snapshot: StateFlow<SightSenseSnapshot> = _snapshot.asStateFlow()

    fun updateDebug(fps: Float, inferMs: Float, width: Int, height: Int, delegate: String) {
        _debug.value = DebugFrame(
            fps = fps,
            activeDelegate = delegate,
            lastInferMs = inferMs,
            frameSize = "${width}x${height}"
        )
    }

    // Keeps old callers working if any file still calls update(...)
    fun update(fps: Float, inferMs: Float, width: Int, height: Int, delegate: String) {
        updateDebug(fps, inferMs, width, height, delegate)
    }

    fun updateVision(vision: List<VisionDetection>) {
        _snapshot.value = _snapshot.value.copy(vision = vision)
    }

    fun updateAudio(audio: List<AudioDetection>) {
        _snapshot.value = _snapshot.value.copy(audio = audio)
    }

    fun updateAlert(alert: SafetyAlert, prompt: String) {
        _snapshot.value = _snapshot.value.copy(alert = alert, llmPrompt = prompt)
    }
}
