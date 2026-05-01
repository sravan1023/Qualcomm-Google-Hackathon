package com.sightsense.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class DebugFrame(
    val fps: Float = 0f,
    val activeDelegate: String = "—",
    val lastInferMs: Float = 0f,
    val frameSize: String = "—"
)

@Composable
fun DebugOverlay(frame: DebugFrame, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = "FPS %.1f  |  delegate %s  |  infer %.1f ms  |  %s".format(
                frame.fps,
                frame.activeDelegate,
                frame.lastInferMs,
                frame.frameSize
            ),
            color = Color.White
        )
    }
}
