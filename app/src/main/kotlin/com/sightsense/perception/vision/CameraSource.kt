package com.sightsense.perception.vision

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class CameraSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val analyzer: ImageAnalysis.Analyzer
) {
    private var provider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null

    suspend fun start() {
        try {
            val provider = awaitProvider()
            this.provider = provider

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val executor = Executors.newSingleThreadExecutor().also { analysisExecutor = it }

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .apply { setAnalyzer(executor, analyzer) }

            provider.unbindAll()
            // Bind to BACK camera for world-facing perception, fallback to FRONT if needed
            val cameraSelector = when {
                provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                else -> throw IllegalStateException("No camera available on this device")
            }
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e("CameraSource", "Lifecycle binding failed: ${e.message}", e)
        }
    }

    fun stop() {
        try {
            provider?.unbindAll()
            analysisExecutor?.shutdown()
        } catch (e: Exception) {
            Log.w("CameraSource", "Stop error: ${e.message}")
        } finally {
            analysisExecutor = null
            provider = null
        }
    }

    private suspend fun awaitProvider(): ProcessCameraProvider {
        val future: ListenableFuture<ProcessCameraProvider> =
            ProcessCameraProvider.getInstance(context)
        return suspendCancellableCoroutine { cont ->
            future.addListener(
                {
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }
    }
}
