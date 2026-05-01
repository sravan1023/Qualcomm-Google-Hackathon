package com.sightsense.perception.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.sightsense.core.ModelCatalog
import com.sightsense.core.VisionDetection
import com.sightsense.infra.litert.AssetResolver
import com.sightsense.infra.litert.InterpreterHolder
import com.sightsense.infra.litert.LiteRtRuntime
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class Detector(
    context: Context,
    private val confidenceThreshold: Float = 0.15f
) : AutoCloseable {

    private val appContext = context.applicationContext

    private val modelPath: String = AssetResolver.firstExisting(
        appContext,
        ModelCatalog.visionDetectorCandidates
    ) ?: error("No detector model found in assets")

    private val holder: InterpreterHolder = LiteRtRuntime.createInterpreter(
        context = appContext,
        modelAssetPath = modelPath,
        preferredDelegate = LiteRtRuntime.DelegatePref.CPU_ONLY,
        numThreads = 4
    )

    private val inputTensor = holder.interpreter.getInputTensor(0)
    private val inputShape = inputTensor.shape()
    private val inputH = inputShape[1]
    private val inputW = inputShape[2]
    private val inputDataType = inputTensor.dataType()
    private val inputBuffer = ByteBuffer
        .allocateDirect(inputTensor.numBytes())
        .order(ByteOrder.nativeOrder())

    private var packedBuffer: ByteBuffer? = null

    private var boxIdx = -1
    private var scoreIdx = -1
    private var classIdx = -1

    private val labels: List<String> = loadLabels()

    init {
        findOutputIndexes()

        Log.i(TAG, "Detector initialized: $modelPath")
        Log.i(TAG, "Input shape=${inputShape.contentToString()}, type=$inputDataType")

        for (i in 0 until holder.interpreter.outputTensorCount) {
            val t = holder.interpreter.getOutputTensor(i)
            Log.i(
                TAG,
                "Output[$i] shape=${t.shape().contentToString()}, type=${t.dataType()}, scale=${t.quantizationParams().scale}, zp=${t.quantizationParams().zeroPoint}"
            )
        }

        Log.i(TAG, "Output indexes: boxes=$boxIdx scores=$scoreIdx classes=$classIdx")
    }

    fun detect(image: ImageProxy): Pair<List<VisionDetection>, Float> {
        val start = SystemClock.elapsedRealtimeNanos()

        return try {
            prepareInput(image)

            val outputs = mutableMapOf<Int, Any>()

            for (i in 0 until holder.interpreter.outputTensorCount) {
                val tensor = holder.interpreter.getOutputTensor(i)
                outputs[i] = ByteBuffer
                    .allocateDirect(tensor.numBytes())
                    .order(ByteOrder.nativeOrder())
            }

            inputBuffer.rewind()
            holder.interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

            val detections = parseOutputs(outputs)
            val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000f

            detections to ms
        } catch (e: Exception) {
            Log.e(TAG, "Detector error: ${e.message}", e)
            emptyList<VisionDetection>() to 0f
        }
    }

    private fun findOutputIndexes() {
        val twoDimOutputs = mutableListOf<Int>()

        for (i in 0 until holder.interpreter.outputTensorCount) {
            val tensor = holder.interpreter.getOutputTensor(i)
            val shape = tensor.shape()

            if (shape.size == 3 && shape.last() == 4) {
                boxIdx = i
            } else if (shape.size == 2 && shape.last() > 100) {
                twoDimOutputs += i
            }
        }

        for (idx in twoDimOutputs) {
            val t = holder.interpreter.getOutputTensor(idx)

            if (t.dataType() == DataType.FLOAT32) {
                scoreIdx = idx
            } else {
                classIdx = idx
            }
        }

        if (scoreIdx == -1 && twoDimOutputs.isNotEmpty()) {
            scoreIdx = twoDimOutputs[0]
        }

        if (classIdx == -1 && twoDimOutputs.size > 1) {
            classIdx = twoDimOutputs.first { it != scoreIdx }
        }

        require(boxIdx != -1) { "Boxes output not found" }
        require(scoreIdx != -1) { "Scores output not found" }
        require(classIdx != -1) { "Class output not found" }
    }

    private fun prepareInput(image: ImageProxy) {
        val plane = image.planes[0]
        val srcBuffer = plane.buffer
        val rowStride = plane.rowStride

        val srcW = image.width
        val srcH = image.height
        val rotation = image.imageInfo.rotationDegrees

        val packed = if (rowStride == srcW * 4) {
            srcBuffer.rewind()
            srcBuffer
        } else {
            val p = packedBuffer?.takeIf { it.capacity() >= srcW * srcH * 4 }
                ?: ByteBuffer.allocateDirect(srcW * srcH * 4).order(ByteOrder.nativeOrder()).also {
                    packedBuffer = it
                }

            p.clear()

            for (y in 0 until srcH) {
                srcBuffer.position(y * rowStride)
                srcBuffer.limit(y * rowStride + srcW * 4)
                p.put(srcBuffer)
                srcBuffer.limit(srcBuffer.capacity())
            }

            p.rewind()
            p
        }

        val bitmap = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(packed)

        val resized = Bitmap.createBitmap(inputW, inputH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resized)
        canvas.drawColor(android.graphics.Color.BLACK)

        val rotatedW = if (rotation % 180 == 0) srcW else srcH
        val rotatedH = if (rotation % 180 == 0) srcH else srcW

        val scale = minOf(inputW.toFloat() / rotatedW, inputH.toFloat() / rotatedH)
        val dx = (inputW - rotatedW * scale) / 2f
        val dy = (inputH - rotatedH * scale) / 2f

        val matrix = Matrix().apply {
            postTranslate(-srcW / 2f, -srcH / 2f)
            postRotate(rotation.toFloat())
            postTranslate(rotatedW / 2f, rotatedH / 2f)
            postScale(scale, scale)
            postTranslate(dx, dy)
        }

        canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))

        val pixels = IntArray(inputW * inputH)
        resized.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)

        inputBuffer.rewind()
        val q = inputTensor.quantizationParams()

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            if (inputDataType == DataType.FLOAT32) {
                inputBuffer.putFloat(r / 255f)
                inputBuffer.putFloat(g / 255f)
                inputBuffer.putFloat(b / 255f)
            } else {
                inputBuffer.put(((r / q.scale) + q.zeroPoint).roundToInt().coerceIn(0, 255).toByte())
                inputBuffer.put(((g / q.scale) + q.zeroPoint).roundToInt().coerceIn(0, 255).toByte())
                inputBuffer.put(((b / q.scale) + q.zeroPoint).roundToInt().coerceIn(0, 255).toByte())
            }
        }

        bitmap.recycle()
        resized.recycle()
    }

    private fun parseOutputs(outputs: Map<Int, Any>): List<VisionDetection> {
        val boxesBuffer = outputs[boxIdx] as ByteBuffer
        val scoresBuffer = outputs[scoreIdx] as ByteBuffer
        val classBuffer = outputs[classIdx] as ByteBuffer

        boxesBuffer.rewind()
        scoresBuffer.rewind()
        classBuffer.rewind()

        val numBoxes = holder.interpreter.getOutputTensor(scoreIdx).shape().last()

        val detections = mutableListOf<VisionDetection>()

        for (i in 0 until numBoxes) {
            val score = getFloat(scoresBuffer, i, scoreIdx)

            if (score < confidenceThreshold) continue

            val cls = getClassId(classBuffer, i, classIdx)
            val label = labels.getOrElse(cls) { "object_$cls" }

            val boxOffset = i * 4

            val x1Raw = getFloat(boxesBuffer, boxOffset, boxIdx)
            val y1Raw = getFloat(boxesBuffer, boxOffset + 1, boxIdx)
            val x2Raw = getFloat(boxesBuffer, boxOffset + 2, boxIdx)
            val y2Raw = getFloat(boxesBuffer, boxOffset + 3, boxIdx)

            val x1 = normalizeCoord(x1Raw, inputW)
            val y1 = normalizeCoord(y1Raw, inputH)
            val x2 = normalizeCoord(x2Raw, inputW)
            val y2 = normalizeCoord(y2Raw, inputH)

            val cx = ((x1 + x2) / 2f).coerceIn(0f, 1f)
            val cy = ((y1 + y2) / 2f).coerceIn(0f, 1f)

            val w = kotlin.math.abs(x2 - x1).coerceIn(0f, 1f)
            val h = kotlin.math.abs(y2 - y1).coerceIn(0f, 1f)
            val area = w * h

            val position = when {
                cx < 0.35f -> "left"
                cx > 0.65f -> "right"
                else -> "center"
            }

            val distance = when {
                area > 0.18f || cy > 0.70f -> "near"
                area > 0.06f || cy > 0.42f -> "medium"
                else -> "far"
            }

            detections += VisionDetection(
                label = label,
                position = position,
                distance = distance,
                confidence = score
            )
        }

        return detections
            .sortedByDescending { it.confidence }
            .distinctBy { "${it.label}_${it.position}_${it.distance}" }
            .take(5)
    }

    private fun normalizeCoord(value: Float, size: Int): Float {
        return if (value > 1.5f) {
            value / size.toFloat()
        } else {
            value
        }.coerceIn(0f, 1f)
    }

    private fun getFloat(buffer: ByteBuffer, elementIndex: Int, tensorIndex: Int): Float {
        val t = holder.interpreter.getOutputTensor(tensorIndex)
        val q = t.quantizationParams()

        return when (t.dataType()) {
            DataType.FLOAT32 -> {
                buffer.position(elementIndex * 4)
                buffer.float
            }

            DataType.INT32 -> {
                buffer.position(elementIndex * 4)
                buffer.int.toFloat()
            }

            DataType.UINT8 -> {
                buffer.position(elementIndex)
                ((buffer.get().toInt() and 0xFF) - q.zeroPoint) * q.scale
            }

            DataType.INT8 -> {
                buffer.position(elementIndex)
                (buffer.get().toInt() - q.zeroPoint) * q.scale
            }

            else -> 0f
        }
    }

    private fun getClassId(buffer: ByteBuffer, elementIndex: Int, tensorIndex: Int): Int {
        val t = holder.interpreter.getOutputTensor(tensorIndex)

        return when (t.dataType()) {
            DataType.FLOAT32 -> {
                buffer.position(elementIndex * 4)
                buffer.float.roundToInt()
            }

            DataType.INT32 -> {
                buffer.position(elementIndex * 4)
                buffer.int
            }

            DataType.UINT8 -> {
                buffer.position(elementIndex)
                buffer.get().toInt() and 0xFF
            }

            DataType.INT8 -> {
                buffer.position(elementIndex)
                buffer.get().toInt()
            }

            else -> -1
        }
    }

    private fun loadLabels(): List<String> {
        return if (AssetResolver.exists(appContext, ModelCatalog.visionLabelsAsset)) {
            appContext.assets.open(ModelCatalog.visionLabelsAsset).bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            }
        } else {
            DEFAULT_COCO_LABELS
        }
    }

    override fun close() {
        holder.close()
    }

    private companion object {
        const val TAG = "Detector"

        val DEFAULT_COCO_LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
            "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
            "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
        )
    }
}