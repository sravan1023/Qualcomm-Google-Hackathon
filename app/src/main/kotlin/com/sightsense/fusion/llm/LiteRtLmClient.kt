package com.sightsense.fusion.llm

import android.content.Context
import android.util.Log
import com.sightsense.core.ModelCatalog
import com.sightsense.infra.litert.AssetResolver

/**
 * Client for LiteRT-LM (Qualcomm NPU compiled models).
 * Loads lazily and handles failures gracefully to avoid crashing the pipeline.
 */
class LiteRtLmClient(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val modelPath = AssetResolver.firstExisting(appContext, ModelCatalog.llmCandidates)
    
    private var engine: Any? = null
    private var generateMethod: java.lang.reflect.Method? = null
    private var initialized = false

    fun isReady(): Boolean {
        if (!initialized) loadEngine()
        return engine != null
    }

    private fun loadEngine() {
        synchronized(this) {
            if (initialized) return
            initialized = true
            
            val path = modelPath ?: return
            Log.i("LiteRtLmClient", "Attempting to load LLM from $path")
            
            val candidates = listOf(
                "com.google.ai.edge.litert.samples.compiledmodelapi.qualcomm.llm_chatbot_npu.LlmEngine",
                "com.qualcomm.qti.ai.llm.LlmEngine"
            )
            for (className in candidates) {
                try {
                    val cls = Class.forName(className)
                    val ctor = cls.constructors.firstOrNull { 
                        it.parameterTypes.size == 2 && it.parameterTypes[0] == Context::class.java && it.parameterTypes[1] == String::class.java
                    } ?: continue
                    
                    engine = ctor.newInstance(appContext, path)
                    generateMethod = cls.methods.firstOrNull { it.name == "generate" && it.parameterTypes.size == 1 }
                    
                    if (engine != null && generateMethod != null) {
                        Log.i("LiteRtLmClient", "Successfully loaded LLM engine: $className")
                        break
                    }
                } catch (e: Throwable) {
                    Log.w("LiteRtLmClient", "Failed to load $className: ${e.message}")
                }
            }
        }
    }

    fun generate(prompt: String): String? {
        if (!isReady()) return null
        val e = engine ?: return null
        val m = generateMethod ?: return null
        return try {
            m.invoke(e, prompt) as? String
        } catch (t: Throwable) {
            Log.e("LiteRtLmClient", "LLM generation failed: ${t.message}")
            null
        }
    }

    override fun close() {
        val e = engine ?: return
        try {
            e.javaClass.getMethod("close").invoke(e)
        } catch (_: Throwable) {}
        engine = null
        generateMethod = null
    }
}
