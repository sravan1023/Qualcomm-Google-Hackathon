package com.sightsense.perception.vision

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.sightsense.core.ModelCatalog
import com.sightsense.infra.litert.AssetResolver
import com.sightsense.infra.litert.LiteRtRuntime
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

object SegmentationSmokeTest {
    private const val TAG = "SegSmoke"
    // The FFNet asset path — only present after you copy the model into assets/
    private const val FFNET_ASSET = "vision/ffnet_40s-tflite-w8a8/ffnet_40s.tflite"

    fun run(context: Context) {
        // Pick FFNet if present, otherwise fall back to whatever detector model exists
        val modelPath = when {
            AssetResolver.exists(context, FFNET_ASSET) -> FFNET_ASSET
            else -> AssetResolver.firstExisting(context, ModelCatalog.visionDetectorCandidates)
        }
        if (modelPath == null) {
            Log.i(TAG, "No vision model in assets — smoke test skipped. " +
                    "Copy a .tflite into app/src/main/assets/vision/ to enable pre-warming.")
            return
        }

        Log.i(TAG, "Starting smoke test with: $modelPath")
        val tStart = SystemClock.elapsedRealtime()
        val holder = try {
            LiteRtRuntime.createInterpreter(
                context = context,
                modelAssetPath = modelPath,
                preferredDelegate = LiteRtRuntime.DelegatePref.QNN_FIRST
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Smoke test failed at interpreter creation: ${e.message}", e)
            return
        }
        val tCreated = SystemClock.elapsedRealtime()
        Log.i(TAG, "Interpreter created in ${tCreated - tStart} ms; delegate=${LiteRtRuntime.lastActive}")

        try {
            val inputShape = holder.interpreter.getInputTensor(0).shape()
            val outputShape = holder.interpreter.getOutputTensor(0).shape()
            val input = TensorBuffer.createFixedSize(inputShape, DataType.UINT8)
            val output = TensorBuffer.createFixedSize(outputShape, DataType.UINT8)
            val tInfer = SystemClock.elapsedRealtime()
            holder.interpreter.run(input.buffer.rewind(), output.buffer.rewind())
            Log.i(TAG, "Smoke test PASS: delegate=${LiteRtRuntime.lastActive}, " +
                    "infer_ms=${SystemClock.elapsedRealtime() - tInfer}")
        } catch (e: Throwable) {
            Log.e(TAG, "Smoke test failed during inference: ${e.message}", e)
        } finally {
            holder.close()
        }
    }
}