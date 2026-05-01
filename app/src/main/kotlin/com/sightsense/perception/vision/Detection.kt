package com.sightsense.perception.vision

import android.graphics.RectF

data class Detection(
    val label: String,
    val confidence: Float,
    val bbox: RectF,
    val classId: Int
)
