package com.sightsense.perception.audio

import android.content.Context
import android.util.Log
import com.sightsense.core.AudioDetection
import kotlin.math.sqrt

class LiteRtAudioClassifier(context: Context) : AutoCloseable {

    fun classify(buffer: ShortArray, read: Int): List<AudioDetection> {
        if (read <= 0) return emptyList()

        var sumSquares = 0.0
        var maxAmp = 0

        for (i in 0 until read) {
            val sample = buffer[i].toInt()
            maxAmp = maxOf(maxAmp, kotlin.math.abs(sample))

            val x = sample / 32768.0
            sumSquares += x * x
        }

        val rms = sqrt(sumSquares / read)
        Log.d("AudioClassifier", "read=$read rms=$rms maxAmp=$maxAmp")

        return when {
            maxAmp > 22000 || rms > 0.16 -> {
                listOf(AudioDetection("very loud warning sound nearby", confidence = 0.90f))
            }

            maxAmp > 14000 || rms > 0.10 -> {
                listOf(AudioDetection("loud warning sound nearby", confidence = 0.78f))
            }

            maxAmp > 8000 || rms > 0.055 -> {
                listOf(AudioDetection("possible warning sound nearby", confidence = 0.62f))
            }

            else -> emptyList()
        }
    }

    override fun close() {
        // Nothing to close in volume-based audio mode.
    }
}