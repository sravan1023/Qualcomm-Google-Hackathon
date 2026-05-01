package com.sightsense.fusion.cooldown

import com.sightsense.core.Priority
import com.sightsense.core.SafetyAlert

class AlertCooldown {

    private var lastLowMessage: String = ""
    private var lastLowTimeMs: Long = 0L

    private var lastMediumMessage: String = ""
    private var lastMediumTimeMs: Long = 0L

    private var lastHighMessage: String = ""
    private var lastHighTimeMs: Long = 0L

    private var lastAnyTimeMs: Long = 0L

    private val lowCooldownMs = 9_000L
    private val mediumCooldownMs = 6_000L
    private val highCooldownMs = 3_000L
    private val globalGapMs = 1_500L

    fun shouldEmit(
        alert: SafetyAlert,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val message = alert.message.trim()

        if (message.isBlank()) return false
        if (message.equals("Environment safe", ignoreCase = true)) return false

        if (nowMs - lastAnyTimeMs < globalGapMs && alert.priority != Priority.HIGH) {
            return false
        }

        return when (alert.priority) {
            Priority.HIGH -> shouldEmitHigh(message, nowMs)
            Priority.MEDIUM -> shouldEmitMedium(message, nowMs)
            Priority.LOW -> shouldEmitLow(message, nowMs)
        }
    }

    private fun shouldEmitHigh(message: String, nowMs: Long): Boolean {
        val sameMessage = message == lastHighMessage

        if (sameMessage && nowMs - lastHighTimeMs < highCooldownMs) {
            return false
        }

        lastHighMessage = message
        lastHighTimeMs = nowMs
        lastAnyTimeMs = nowMs
        return true
    }

    private fun shouldEmitMedium(message: String, nowMs: Long): Boolean {
        val sameMessage = message == lastMediumMessage

        if (sameMessage && nowMs - lastMediumTimeMs < mediumCooldownMs) {
            return false
        }

        lastMediumMessage = message
        lastMediumTimeMs = nowMs
        lastAnyTimeMs = nowMs
        return true
    }

    private fun shouldEmitLow(message: String, nowMs: Long): Boolean {
        val sameMessage = message == lastLowMessage

        if (sameMessage && nowMs - lastLowTimeMs < lowCooldownMs) {
            return false
        }

        lastLowMessage = message
        lastLowTimeMs = nowMs
        lastAnyTimeMs = nowMs
        return true
    }
}