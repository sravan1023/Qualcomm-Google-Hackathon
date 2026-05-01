package com.sightsense.perception.vision

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import com.sightsense.core.VisionDetection

class DetectorFrameProcessor(
    context: Context,
    private val onVision: (List<VisionDetection>) -> Unit
) : FrameProcessor {

    private val detector = runCatching { Detector(context) }
        .onFailure { Log.w(TAG, "Detector unavailable (${it.message}) — trying FFNet fallback") }
        .getOrNull()

    private val fallback = if (detector == null) {
        runCatching { FfnetSegmentationProcessor(context, onVision) }
            .onFailure { Log.w(TAG, "FFNet fallback also unavailable: ${it.message}") }
            .getOrNull()
    } else {
        null
    }

    private val history = mutableListOf<List<VisionDetection>>()
    private val historySize = 3

    init {
        val active = when {
            detector != null -> "DETECTOR"
            fallback != null -> "FFNET FALLBACK"
            else -> "NONE"
        }
        Log.i(TAG, ">>> VISION PROCESSOR ACTIVE: $active <<<")
    }

    override fun process(image: ImageProxy): ProcessResult {
        return if (detector != null) {
            val (detections, inferMs) = detector.detect(image)
            processAndSmooth(detections)
            ProcessResult(inferMs = inferMs)
        } else {
            fallback?.process(image) ?: run {
                onVision(emptyList())
                ProcessResult()
            }
        }
    }

    private fun processAndSmooth(currentDetections: List<VisionDetection>) {
        if (currentDetections.isEmpty()) {
            onVision(emptyList())
            return
        }

        history.add(currentDetections)
        if (history.size > historySize) {
            history.removeAt(0)
        }

        val stable = currentDetections.filter { current ->
            val matches = history.count { frame ->
                frame.any {
                    it.label == current.label &&
                            it.position == current.position
                }
            }
            matches >= 2
        }

        onVision(if (stable.isNotEmpty()) stable else currentDetections)
    }

    override fun close() {
        runCatching { detector?.close() }
        runCatching { fallback?.close() }
    }

    private companion object {
        const val TAG = "VisionProcessor"
    }
}