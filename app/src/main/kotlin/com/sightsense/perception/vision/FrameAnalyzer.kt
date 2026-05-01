package com.sightsense.perception.vision

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.sightsense.ui.debug.FpsCounter

interface FrameProcessor {
    fun process(image: ImageProxy): ProcessResult
    fun close() {}
}

data class ProcessResult(
    val inferMs: Float = 0f
)

data class FpsTick(
    val fps: Float,
    val inferMs: Float,
    val width: Int,
    val height: Int
)

class FrameAnalyzer(
    private val onFrame: (FpsTick) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile
    var processor: FrameProcessor? = null

    private val fps = FpsCounter()

    override fun analyze(image: ImageProxy) {
        val w = image.width
        val h = image.height
        var inferMs = 0f
        try {
            processor?.process(image)?.let { inferMs = it.inferMs }
        } catch (t: Throwable) {
            Log.w(TAG, "Frame processor threw: ${t.message}")
        } finally {
            image.close()
        }
        fps.tick()
        onFrame(FpsTick(fps = fps.fps(), inferMs = inferMs, width = w, height = h))
    }

    private companion object {
        const val TAG = "FrameAnalyzer"
    }
}
