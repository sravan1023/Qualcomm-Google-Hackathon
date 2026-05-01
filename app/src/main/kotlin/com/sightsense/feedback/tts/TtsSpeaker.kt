package com.sightsense.feedback.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.UUID

class TtsSpeaker(context: Context) : AutoCloseable {
    @Volatile private var ready = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts?.language = Locale.US
        }
    }

    fun speak(message: String) {
        val engine = tts ?: return
        if (!ready || message.isBlank()) return
        engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    override fun close() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
