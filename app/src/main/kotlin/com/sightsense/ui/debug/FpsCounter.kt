package com.sightsense.ui.debug

import android.os.SystemClock

class FpsCounter(private val window: Int = 30) {
    private val timestamps = LongArray(window)
    private var head = 0
    private var count = 0

    fun tick() {
        timestamps[head] = SystemClock.elapsedRealtime()
        head = (head + 1) % window
        if (count < window) count++
    }

    fun fps(): Float {
        if (count < 2) return 0f
        val newest = if (head == 0) timestamps[window - 1] else timestamps[head - 1]
        val oldestIndex = if (count < window) 0 else head
        val oldest = timestamps[oldestIndex]
        val elapsedMs = (newest - oldest).coerceAtLeast(1L)
        return (count - 1) * 1000f / elapsedMs
    }
}
