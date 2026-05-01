package com.sightsense.infra.litert

import android.content.Context
import android.util.Log
import java.nio.MappedByteBuffer
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter

object LiteRtRuntime {
    private const val TAG = "LiteRtRuntime"

    enum class DelegatePref { QNN_FIRST, GPU_FIRST, CPU_ONLY }
    enum class ActiveDelegate { QNN, GPU, CPU }

    @Volatile
    var lastActive: ActiveDelegate = ActiveDelegate.CPU
        private set

    fun createInterpreter(
        context: Context,
        modelAssetPath: String,
        preferredDelegate: DelegatePref = DelegatePref.QNN_FIRST,
        numThreads: Int = 4
    ): InterpreterHolder {
        val model = ModelLoader.loadAsByteBuffer(context, modelAssetPath)

        val attempts: List<(MappedByteBuffer, Int, String, Context) -> InterpreterHolder?> = when (preferredDelegate) {
            DelegatePref.QNN_FIRST -> listOf(::tryQnn, ::tryGpu, ::cpu)
            DelegatePref.GPU_FIRST -> listOf(::tryGpu, ::cpu)
            DelegatePref.CPU_ONLY -> listOf(::cpu)
        }
        for (attempt in attempts) {
            attempt(model, numThreads, modelAssetPath, context)?.let { return it }
        }
        error("Failed to create LiteRT interpreter for $modelAssetPath")
    }

    private fun tryQnn(
        model: MappedByteBuffer,
        @Suppress("UNUSED_PARAMETER") threads: Int,
        path: String,
        context: Context
    ): InterpreterHolder? {
        val delegate = constructQnnDelegate(context) ?: return null
        return runCatching {
            val opts = Interpreter.Options().apply { addDelegate(delegate) }
            val interp = Interpreter(model, opts)
            lastActive = ActiveDelegate.QNN
            Log.i(TAG, "Interpreter[QNN]: $path")
            InterpreterHolder(interp, listOf(delegate))
        }.onFailure { e ->
            Log.w(TAG, "QNN interpreter creation failed: ${e.message}")
            tryClose(delegate)
        }.getOrNull()
    }

    private fun tryGpu(
        model: MappedByteBuffer,
        @Suppress("UNUSED_PARAMETER") threads: Int,
        path: String,
        @Suppress("UNUSED_PARAMETER") context: Context
    ): InterpreterHolder? {
        val delegate = constructGpuDelegate() ?: return null
        return runCatching {
            val opts = Interpreter.Options().apply { addDelegate(delegate) }
            val interp = Interpreter(model, opts)
            lastActive = ActiveDelegate.GPU
            Log.i(TAG, "Interpreter[GPU]: $path")
            InterpreterHolder(interp, listOf(delegate))
        }.onFailure { e ->
            Log.w(TAG, "GPU interpreter creation failed: ${e.message}")
            tryClose(delegate)
        }.getOrNull()
    }

    private fun cpu(
        model: MappedByteBuffer,
        threads: Int,
        path: String,
        @Suppress("UNUSED_PARAMETER") context: Context
    ): InterpreterHolder? {
        return runCatching {
            val opts = Interpreter.Options().apply { setNumThreads(threads) }
            val interp = Interpreter(model, opts)
            lastActive = ActiveDelegate.CPU
            Log.i(TAG, "Interpreter[CPU x$threads]: $path")
            InterpreterHolder(interp, emptyList())
        }.onFailure { e ->
            Log.e(TAG, "CPU interpreter creation failed: ${e.message}")
        }.getOrNull()
    }

    private fun constructQnnDelegate(context: Context): Delegate? {
        val candidateClasses = listOf(
            "com.qualcomm.qti.QnnDelegate",
            "com.qualcomm.qti.qnn.tflite.QnnDelegate"
        )
        for (className in candidateClasses) {
            val cls = try {
                Class.forName(className)
            } catch (_: ClassNotFoundException) {
                continue
            }
            // Pattern: nested Options class + ctor(Options)
            try {
                val optsCls = Class.forName("$className\$Options")
                val opts = optsCls.getDeclaredConstructor().newInstance()
                return cls.getDeclaredConstructor(optsCls).newInstance(opts) as Delegate
            } catch (_: Throwable) { /* try next pattern */ }
            // Pattern: ctor(Context)
            try {
                val contextClass = Class.forName("android.content.Context")
                return cls.getDeclaredConstructor(contextClass).newInstance(context) as Delegate
            } catch (_: Throwable) { /* try next pattern */ }
            // Pattern: no-arg ctor
            try {
                return cls.getDeclaredConstructor().newInstance() as Delegate
            } catch (_: Throwable) { /* fall through */ }
            Log.w(TAG, "Found $className but no compatible constructor.")
        }
        Log.i(TAG, "QNN delegate not on classpath — drop AAR into app/libs/ to enable NPU.")
        return null
    }

    private fun constructGpuDelegate(): Delegate? {
        return try {
            Class.forName("org.tensorflow.lite.gpu.GpuDelegate")
                .getDeclaredConstructor()
                .newInstance() as Delegate
        } catch (_: ClassNotFoundException) {
            Log.w(TAG, "GPU delegate class missing — check litert-gpu dependency.")
            null
        } catch (e: Throwable) {
            Log.w(TAG, "GPU delegate construction failed: ${e.message}")
            null
        }
    }

    private fun tryClose(delegate: Delegate) {
        try {
            delegate.javaClass.getMethod("close").invoke(delegate)
        } catch (_: Throwable) { /* best effort */ }
    }
}

class InterpreterHolder(
    val interpreter: Interpreter,
    private val delegates: List<Delegate>
) : AutoCloseable {
    override fun close() {
        try { interpreter.close() } catch (_: Throwable) {}
        delegates.forEach { d ->
            try { d.javaClass.getMethod("close").invoke(d) } catch (_: Throwable) {}
        }
    }
}
