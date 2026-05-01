package com.sightsense.core

data class VisionDetection(
    val label: String,
    val position: String,
    val distance: String,
    val confidence: Float
)

data class AudioDetection(
    val label: String,
    val direction: String = "unknown",
    val confidence: Float
)

data class SafetyAlert(
    val priority: Priority,
    val message: String,
    val reason: String
)

enum class Priority { HIGH, MEDIUM, LOW }

data class SightSenseSnapshot(
    val vision: List<VisionDetection> = emptyList(),
    val audio: List<AudioDetection> = emptyList(),
    val alert: SafetyAlert = SafetyAlert(
        priority = Priority.LOW,
        message = "No urgent danger detected.",
        reason = "Waiting for live camera and microphone signals."
    ),
    val llmPrompt: String = ""
)
