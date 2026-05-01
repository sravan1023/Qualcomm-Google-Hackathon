package com.sightsense.perception.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.sightsense.core.VisionDetection
import com.sightsense.infra.litert.AssetResolver
import com.sightsense.infra.litert.InterpreterHolder
import com.sightsense.infra.litert.LiteRtRuntime
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType

class FfnetSegmentationProcessor(
    context: Context,
    private val onVision: (List<VisionDetection>) -> Unit
) : FrameProcessor {

    private val holder: InterpreterHolder? = if (AssetResolver.exists(context.applicationContext, MODEL_ASSET)) {
        runCatching {
            LiteRtRuntime.createInterpreter(
                context = context.applicationContext,
                modelAssetPath = MODEL_ASSET,
                preferredDelegate = LiteRtRuntime.DelegatePref.CPU_ONLY,
                numThreads = 4
            )
        }.onFailure { Log.e(TAG, "Failed to create FFNet interpreter: ${it.message}") }
            .getOrNull()
    } else null

    private val outputTensor = holder?.interpreter?.getOutputTensor(0)
    private val outputShape = outputTensor?.shape() ?: intArrayOf(1, 128, 256, 19)
    private val outputQuant = outputTensor?.quantizationParams()
    private val outputZeroPoint = outputQuant?.zeroPoint ?: 0
    
    // Auto-detect channel first vs last based on dimensions
    private val isChannelFirst = outputShape.size == 4 && outputShape[1] < outputShape[2] && outputShape[1] < outputShape[3]
    private val actualH = if (isChannelFirst) outputShape[2] else outputShape[1]
    private val actualW = if (isChannelFirst) outputShape[3] else outputShape[2]
    private val numChannels = if (isChannelFirst) outputShape[1] else outputShape[3]
    
    private val inputTensor = holder?.interpreter?.getInputTensor(0)
    private val inputDataType = inputTensor?.dataType() ?: DataType.UINT8
    private val inputSize = INPUT_H * INPUT_W * INPUT_C * (if (inputDataType == DataType.FLOAT32) 4 else 1)
    private val input = ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder())

    private val output = ByteBuffer.allocateDirect(outputTensor?.numBytes() ?: 1).order(ByteOrder.nativeOrder())
    private var packedBuffer: ByteBuffer? = null

    init {
        Log.i(TAG, "FFNet initialized: shape=${outputShape.contentToString()}, channels=$numChannels, channelFirst=$isChannelFirst")
        if (numChannels != LABELS.size) {
            Log.w(TAG, "Channel mismatch: model has $numChannels but code has ${LABELS.size} labels!")
        }
    }

    override fun process(image: ImageProxy): ProcessResult {
        if (holder == null) {
            onVision(emptyList())
            return ProcessResult()
        }
        try {
            val start = SystemClock.elapsedRealtimeNanos()
            imageToModelInput(image, input)
            output.rewind()
            holder.interpreter.run(input.rewind(), output)
            val detections = parseSegmentation(output)
            onVision(detections)
            return ProcessResult(inferMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000f)
        } catch (e: Exception) {
            Log.e(TAG, "Process error: ${e.message}")
            return ProcessResult()
        }
    }

    override fun close() = holder?.close() ?: Unit

    private fun imageToModelInput(image: ImageProxy, target: ByteBuffer) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val w = image.width; val h = image.height; val rot = image.imageInfo.rotationDegrees

        val packed = if (rowStride == w * 4) { buffer.rewind(); buffer } else {
            val p = packedBuffer?.takeIf { it.capacity() >= w * h * 4 }
                ?: ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).also { packedBuffer = it }
            p.clear()
            for (y in 0 until h) {
                buffer.position(y * rowStride); buffer.limit(y * rowStride + w * 4)
                p.put(buffer); buffer.limit(buffer.capacity())
            }
            p.rewind(); p
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(packed)

        val cropped = Bitmap.createBitmap(INPUT_W, INPUT_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cropped)
        canvas.drawColor(android.graphics.Color.BLACK)
        
        val matrix = Matrix()
        val rotatedW = if (rot % 180 == 0) w else h
        val rotatedH = if (rot % 180 == 0) h else w
        // Use minOf to fit the entire camera frame into the model input (with black bars)
        // instead of maxOf which crops out significant parts of the view.
        val scale = minOf(INPUT_W.toFloat() / rotatedW, INPUT_H.toFloat() / rotatedH)
        
        matrix.postTranslate(-w / 2f, -h / 2f)
        matrix.postRotate(rot.toFloat())
        matrix.postScale(scale, scale)
        matrix.postTranslate(INPUT_W / 2f, INPUT_H / 2f)
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))

        target.rewind()
        val pixels = IntArray(INPUT_W * INPUT_H)
        cropped.getPixels(pixels, 0, INPUT_W, 0, 0, INPUT_W, INPUT_H)
        
        val quant = inputTensor?.quantizationParams()
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            
            if (inputDataType == DataType.FLOAT32) {
                target.putFloat(r / 255f)
                target.putFloat(g / 255f)
                target.putFloat(b / 255f)
            } else {
                val zp = quant?.zeroPoint ?: 0
                target.put((r + zp).toByte())
                target.put((g + zp).toByte())
                target.put((b + zp).toByte())
            }
        }
        bitmap.recycle(); cropped.recycle()
    }

    private fun parseSegmentation(buffer: ByteBuffer): List<VisionDetection> {
        buffer.rewind()
        val counts = IntArray(numChannels)
        val sumX = LongArray(numChannels); val sumY = LongArray(numChannels)
        
        val threshold = outputZeroPoint + 2

        for (y in 0 until actualH) {
            for (x in 0 until actualW) {
                var bestClass = -1
                var bestScoreRaw = -1
                
                for (c in 0 until numChannels) {
                    val scoreRaw = if (isChannelFirst) {
                        buffer.position((c * actualH * actualW + y * actualW + x))
                        buffer.get().toInt() and 0xFF
                    } else {
                        buffer.get().toInt() and 0xFF
                    }
                    if (scoreRaw > bestScoreRaw) {
                        bestScoreRaw = scoreRaw
                        bestClass = c
                    }
                }
                
                if (bestClass != -1 && bestScoreRaw > threshold) {
                    counts[bestClass]++
                    sumX[bestClass] += x.toLong()
                    sumY[bestClass] += y.toLong()
                }
            }
        }
        
        val totalPixels = actualH * actualW
        val important = listOf("person", "car", "bus", "bicycle", "building", "wall", "pole", "traffic light", "traffic sign")
        val detections = important.mapNotNull { label ->
            val idx = LABELS.indexOf(label)
            val minPixels = totalPixels / 400 
            if (idx < 0 || idx >= numChannels || counts[idx] < minPixels) return@mapNotNull null

            val cx = sumX[idx].toFloat() / counts[idx]
            val cy = sumY[idx].toFloat() / counts[idx]
            val pos = when { cx < actualW * 0.35f -> "left"; cx > actualW * 0.65f -> "right"; else -> "center" }
            val dist = when { cy > actualH * 0.70f -> "near"; cy > actualH * 0.40f -> "medium"; else -> "far" }
            
            // Map segmentation labels to descriptive ones
            val finalLabel = when(label) {
                "building", "wall", "fence" -> "obstacle"
                "pole", "traffic sign", "traffic light" -> "pole"
                else -> label
            }
            
            VisionDetection(finalLabel, pos, dist, (counts[idx] / 1000f).coerceIn(0.50f, 0.95f))
        }
        
        return detections.sortedByDescending { 
            when(it.label) {
                "person" -> 3f
                "car", "bus", "bicycle" -> 2f
                else -> 1f
            }
        }
    }

    private companion object {
        const val TAG = "FfnetProcessor"
        const val MODEL_ASSET = "vision/ffnet_40s-tflite-w8a8/ffnet_40s.tflite"
        const val INPUT_H = 1024; const val INPUT_W = 2048; const val INPUT_C = 3
        // Standard Cityscapes 19 labels used by FFNet-40S
        val LABELS = listOf("road", "sidewalk", "building", "wall", "fence", "pole", "traffic light", "traffic sign", "vegetation", "terrain", "sky", "person", "rider", "car", "truck", "bus", "train", "motorcycle", "bicycle")
    }
}
