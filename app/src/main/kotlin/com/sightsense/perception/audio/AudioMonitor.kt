package com.sightsense.perception.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.sightsense.core.AudioDetection
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioMonitor(
    private val context: Context,
    private val onAudio: (List<AudioDetection>) -> Unit
) : AutoCloseable {

    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null

    private val classifier = runCatching { LiteRtAudioClassifier(context) }
        .onFailure { Log.w(TAG, "Audio classifier unavailable: ${it.message}") }
        .getOrNull()

    private var lastLabel: String? = null
    private var lastEmitTimeMs: Long = 0L

    fun start() {
        if (running.getAndSet(true)) return

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            running.set(false)
            return
        }

        thread(name = "SightSense-Audio", isDaemon = true) {
            val sampleRate = 16_000
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val bufferSize = maxOf(minBuffer, sampleRate)
            val buffer = ShortArray(bufferSize)

            try {
                @Suppress("MissingPermission")
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
                )

                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    running.set(false)
                    return@thread
                }

                recorder = rec
                rec.startRecording()

                while (running.get()) {
                    val read = rec.read(buffer, 0, 8000)

                    if (read > 0) {
                        val detections = classifier?.classify(buffer, read).orEmpty()
                        emitWithCooldown(detections)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Audio monitor error: ${t.message}", t)
            } finally {
                running.set(false)
                recorder?.let {
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }
                recorder = null
            }
        }
    }

    private fun emitWithCooldown(detections: List<AudioDetection>) {
        if (detections.isEmpty()) {
            onAudio(emptyList())
            return
        }

        val best = detections.maxByOrNull { it.confidence } ?: return

        if (best.label.contains("Sound_", ignoreCase = true) ||
            best.label.contains("sound_", ignoreCase = true) ||
            best.label == "unknown"
        ) {
            onAudio(emptyList())
            return
        }

        val now = System.currentTimeMillis()
        val sameAsLast = best.label == lastLabel

        if (sameAsLast && now - lastEmitTimeMs < 3000L) {
            return
        }

        lastLabel = best.label
        lastEmitTimeMs = now

        onAudio(listOf(best))
    }

    override fun close() {
        running.set(false)
        runCatching { classifier?.close() }
    }

    private companion object {
        const val TAG = "AudioMonitor"
    }
}