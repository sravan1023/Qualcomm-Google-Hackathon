package com.sightsense.fusion.llm

import android.content.Context
import com.sightsense.core.AudioDetection
import com.sightsense.core.Priority
import com.sightsense.core.SafetyAlert
import com.sightsense.core.VisionDetection

class FusionLlmEngine(context: Context) : AutoCloseable {

    data class Result(
        val alert: SafetyAlert,
        val prompt: String
    )

    private val lmClient = LiteRtLmClient(context)

    private val lastDistances = mutableMapOf<String, MutableList<String>>()

    fun generateAlert(
        visionResults: List<VisionDetection>,
        audioResults: List<AudioDetection>,
        dangerMode: Boolean = true
    ): Result {
        val validVision = visionResults
            .filter { it.confidence >= 0.15f }
            .sortedByDescending { it.confidence }

        val validAudio = audioResults
            .filter { it.confidence >= 0.60f }
            .sortedByDescending { it.confidence }

        val alert = localConstrainedFallback(
            vision = validVision,
            audio = validAudio,
            dangerMode = dangerMode
        )

        return Result(
            alert = alert,
            prompt = buildPrompt(validVision, validAudio, dangerMode)
        )
    }

    private fun localConstrainedFallback(
        vision: List<VisionDetection>,
        audio: List<AudioDetection>,
        dangerMode: Boolean
    ): SafetyAlert {
        val topAudio = audio.firstOrNull()
        val highAudio = topAudio?.takeIf { it.confidence >= 0.75f }

        val movingVision = vision.firstOrNull {
            isMovableObject(it.label)
        }

        if (movingVision != null) {
            val motion = detectMotion(
                label = movingVision.label,
                position = movingVision.position,
                distance = movingVision.distance
            )

            if (motion == "approaching") {
                return SafetyAlert(
                    priority = Priority.HIGH,
                    message = "${cleanLabel(movingVision.label)} approaching ${movingVision.position}",
                    reason = "Moving object is getting closer"
                )
            }
        }

        val dangerousVision = vision.firstOrNull {
            isDangerousObject(it.label) && it.distance.equals("near", ignoreCase = true)
        }

        if (dangerousVision != null) {
            return SafetyAlert(
                priority = Priority.HIGH,
                message = "${cleanLabel(dangerousVision.label)} ${dangerousVision.position} near",
                reason = "Nearby moving or road-related hazard"
            )
        }

        if (highAudio != null) {
            return SafetyAlert(
                priority = Priority.HIGH,
                message = cleanLabel(highAudio.label),
                reason = "High-priority warning sound detected"
            )
        }

        val obstacleVision = vision.firstOrNull {
            isObstacleObject(it.label) && it.distance.equals("near", ignoreCase = true)
        }

        if (obstacleVision != null) {
            return SafetyAlert(
                priority = if (dangerMode) Priority.HIGH else Priority.MEDIUM,
                message = "${cleanLabel(obstacleVision.label)} ${obstacleVision.position} near",
                reason = if (dangerMode) {
                    "High sensitivity: nearby obstacle"
                } else {
                    "Nearby obstacle detected"
                }
            )
        }

        val staticNear = vision.firstOrNull {
            it.distance.equals("near", ignoreCase = true)
        }

        if (staticNear != null) {
            val isHarmless = isHarmlessObject(staticNear.label)

            return SafetyAlert(
                priority = when {
                    isHarmless -> Priority.LOW
                    dangerMode -> Priority.MEDIUM
                    else -> Priority.LOW
                },
                message = "${cleanLabel(staticNear.label)} ${staticNear.position} near",
                reason = when {
                    isHarmless -> "Harmless object nearby"
                    dangerMode -> "Nearby object in high sensitivity mode"
                    else -> "Nearby object detected, not immediate danger"
                }
            )
        }

        val mediumObject = vision.firstOrNull {
            it.distance.equals("medium", ignoreCase = true)
        }

        if (mediumObject != null) {
            val isHarmless = isHarmlessObject(mediumObject.label)

            return SafetyAlert(
                priority = when {
                    isHarmless -> Priority.LOW
                    dangerMode && isObstacleObject(mediumObject.label) -> Priority.MEDIUM
                    dangerMode && isDangerousObject(mediumObject.label) -> Priority.MEDIUM
                    else -> Priority.LOW
                },
                message = "${cleanLabel(mediumObject.label)} ${mediumObject.position} medium",
                reason = when {
                    isHarmless -> "Harmless object at medium distance"
                    dangerMode -> "High sensitivity: object at medium distance"
                    else -> "Object detected at medium distance"
                }
            )
        }

        if (topAudio != null) {
            return SafetyAlert(
                priority = if (dangerMode) Priority.HIGH else Priority.MEDIUM,
                message = cleanLabel(topAudio.label),
                reason = if (dangerMode) {
                    "High sensitivity: warning sound nearby"
                } else {
                    "Possible warning sound nearby"
                }
            )
        }

        return SafetyAlert(
            priority = Priority.LOW,
            message = "Environment safe",
            reason = "No urgent hazard detected"
        )
    }

    private fun detectMotion(
        label: String,
        position: String,
        distance: String
    ): String? {
        val key = "${label.lowercase()}_${position.lowercase()}"
        val normalizedDistance = distance.lowercase()

        val history = lastDistances.getOrPut(key) { mutableListOf() }

        if (history.lastOrNull() != normalizedDistance) {
            history.add(normalizedDistance)
        }

        if (history.size > 3) {
            history.removeAt(0)
        }

        if (history.size < 3) return null

        val d1 = history[0]
        val d2 = history[1]
        val d3 = history[2]

        return when {
            d1 == "far" && d2 == "medium" && d3 == "near" -> "approaching"
            d1 == "medium" && d2 == "near" && d3 == "near" -> "approaching"
            d1 == "far" && d2 == "near" -> "approaching"

            d1 == "near" && d2 == "medium" && d3 == "far" -> "moving away"
            d1 == "near" && d2 == "far" -> "moving away"

            else -> null
        }
    }

    private fun isMovableObject(label: String): Boolean {
        val l = label.lowercase()
        return l in setOf(
            "person",
            "car",
            "truck",
            "bus",
            "motorcycle",
            "bicycle",
            "train"
        )
    }

    private fun isDangerousObject(label: String): Boolean {
        val l = label.lowercase()
        return l in setOf(
            "car",
            "truck",
            "bus",
            "motorcycle",
            "bicycle",
            "train"
        )
    }

    private fun isObstacleObject(label: String): Boolean {
        val l = label.lowercase()
        return l in setOf(
            "person",
            "chair",
            "couch",
            "bench",
            "dining table",
            "potted plant",
            "stop sign",
            "traffic light"
        )
    }

    private fun isHarmlessObject(label: String): Boolean {
        val l = label.lowercase()
        return l in setOf(
            "potted plant",
            "plant",
            "bottle",
            "cup",
            "book",
            "laptop",
            "keyboard",
            "mouse",
            "tv",
            "remote",
            "cell phone",
            "clock",
            "vase",
            "teddy bear"
        )
    }

    private fun cleanLabel(label: String): String {
        return label.replaceFirstChar { it.uppercase() }
    }

    private fun buildPrompt(
        vision: List<VisionDetection>,
        audio: List<AudioDetection>,
        dangerMode: Boolean
    ): String {
        val visionText = if (vision.isEmpty()) {
            "none"
        } else {
            vision.take(5).joinToString("\n") {
                "${it.label} ${it.position} ${it.distance}, confidence=${"%.2f".format(it.confidence)}"
            }
        }

        val audioText = if (audio.isEmpty()) {
            "none"
        } else {
            audio.take(3).joinToString("\n") {
                "${it.label}, confidence=${"%.2f".format(it.confidence)}"
            }
        }

        return """
Vision:
$visionText

Audio:
$audioText

Danger mode:
$dangerMode
        """.trimIndent()
    }

    override fun close() {
        lmClient.close()
    }
}