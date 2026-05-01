package com.sightsense.ui.home

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sightsense.core.Priority
import com.sightsense.core.SightSenseSnapshot
import com.sightsense.feedback.haptics.HapticAlerter
import com.sightsense.feedback.tts.TtsSpeaker
import com.sightsense.fusion.cooldown.AlertCooldown
import com.sightsense.fusion.llm.FusionLlmEngine
import com.sightsense.infra.litert.LiteRtRuntime
import com.sightsense.infra.perm.PermissionGate
import com.sightsense.perception.audio.AudioMonitor
import com.sightsense.perception.vision.CameraSource
import com.sightsense.perception.vision.DetectorFrameProcessor
import com.sightsense.perception.vision.FrameAnalyzer
import com.sightsense.ui.debug.DebugOverlay

@Composable
fun HomeScreen() {
    val viewModel: HomeViewModel = viewModel()

    PermissionGate {
        CameraScene(viewModel)
    }
}

@Composable
private fun CameraScene(viewModel: HomeViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val debug by viewModel.debug.collectAsState()
    val snapshot by viewModel.snapshot.collectAsState()

    var dangerMode by remember { mutableStateOf(false) }
    var voiceMuted by remember { mutableStateOf(false) }

    val dangerModeState by rememberUpdatedState(dangerMode)
    val voiceMutedState by rememberUpdatedState(voiceMuted)

    val fusion = remember(context) { FusionLlmEngine(context) }
    val cooldown = remember { AlertCooldown() }
    val tts = remember(context) { TtsSpeaker(context) }
    val haptics = remember(context) { HapticAlerter(context) }

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val analyzer = remember(viewModel, fusion, cooldown, tts, haptics) {
        FrameAnalyzer { tick ->
            viewModel.updateDebug(
                fps = tick.fps,
                inferMs = tick.inferMs,
                width = tick.width,
                height = tick.height,
                delegate = LiteRtRuntime.lastActive.name
            )

            val current = viewModel.snapshot.value
            val result = fusion.generateAlert(
                visionResults = current.vision,
                audioResults = current.audio,
                dangerMode = dangerModeState
            )

            viewModel.updateAlert(result.alert, result.prompt)

            if (!voiceMutedState && cooldown.shouldEmit(result.alert)) {
                tts.speak(result.alert.message)
                haptics.vibrate(result.alert.priority)
            }
        }.apply {
            processor = DetectorFrameProcessor(context) { detections ->
                viewModel.updateVision(detections)
            }
        }
    }

    val source = remember(context, lifecycleOwner, previewView, analyzer) {
        CameraSource(context, lifecycleOwner, previewView, analyzer)
    }

    val audioMonitor = remember(context, viewModel) {
        AudioMonitor(context) { audio ->
            viewModel.updateAudio(audio)
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1200)
        if (!voiceMuted) {
            tts.speak("SightSense active. Scanning your surroundings.")
        }
    }
    LaunchedEffect(source) {
        runCatching { audioMonitor.start() }
        runCatching { source.start() }
    }

    DisposableEffect(source, analyzer, audioMonitor, tts, fusion) {
        onDispose {
            source.stop()
            analyzer.processor?.close()
            audioMonitor.close()
            tts.close()
            fusion.close()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.05f),
                            Color.Black.copy(alpha = 0.20f),
                            Color.Black.copy(alpha = 0.94f)
                        )
                    )
                )
        )

        DebugOverlay(
            frame = debug,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        BottomControlPanel(
            snapshot = snapshot,
            dangerMode = dangerMode,
            voiceMuted = voiceMuted,
            onDangerModeChange = { dangerMode = it },
            onVoiceMutedChange = { voiceMuted = it },
            onRepeatAlert = {
                if (!voiceMuted) {
                    tts.speak(snapshot.alert.message)
                    haptics.vibrate(snapshot.alert.priority)
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun BottomControlPanel(
    snapshot: SightSenseSnapshot,
    dangerMode: Boolean,
    voiceMuted: Boolean,
    onDangerModeChange: (Boolean) -> Unit,
    onVoiceMutedChange: (Boolean) -> Unit,
    onRepeatAlert: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MainAlertCard(
            snapshot = snapshot,
            dangerMode = dangerMode,
            voiceMuted = voiceMuted,
            onDangerModeChange = onDangerModeChange,
            onVoiceMutedChange = onVoiceMutedChange,
            onRepeatAlert = onRepeatAlert
        )

        DetectionSummaryCard(snapshot = snapshot)
    }
}

@Composable
private fun MainAlertCard(
    snapshot: SightSenseSnapshot,
    dangerMode: Boolean,
    voiceMuted: Boolean,
    onDangerModeChange: (Boolean) -> Unit,
    onVoiceMutedChange: (Boolean) -> Unit,
    onRepeatAlert: () -> Unit
) {
    val accent = when (snapshot.alert.priority) {
        Priority.HIGH -> Color(0xFFFF3B30)
        Priority.MEDIUM -> Color(0xFFFFB020)
        Priority.LOW -> Color(0xFF32D74B)
    }

    val title = when (snapshot.alert.priority) {
        Priority.HIGH -> "DANGER"
        Priority.MEDIUM -> "CAUTION"
        Priority.LOW -> "SAFE"
    }

    val gradient = when (snapshot.alert.priority) {
        Priority.HIGH -> listOf(Color(0xFF3B0707), Color(0xFF140202))
        Priority.MEDIUM -> listOf(Color(0xFF3B2600), Color(0xFF160E00))
        Priority.LOW -> listOf(Color(0xFF062A16), Color(0xFF020F08))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .border(2.dp, accent, RoundedCornerShape(34.dp)),
        shape = RoundedCornerShape(34.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(gradient))
                .padding(18.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text(
                            text = title,
                            color = accent,
                            fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.headlineMedium
                        )

                        Text(
                            text = if (dangerMode) "High sensitivity mode" else "Balanced safety mode",
                            color = Color.White.copy(alpha = 0.72f),
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(accent)
                            .border(2.dp, Color.White.copy(alpha = 0.80f), CircleShape)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = snapshot.alert.message,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = snapshot.alert.reason.ifBlank { "Real-time multimodal safety monitoring" },
                        color = Color.White.copy(alpha = 0.68f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ModePill(
                            title = "Danger Mode",
                            active = dangerMode,
                            activeText = "ON",
                            inactiveText = "OFF",
                            onClick = { onDangerModeChange(!dangerMode) },
                            modifier = Modifier.weight(1f)
                        )

                        ModePill(
                            title = "Voice",
                            active = !voiceMuted,
                            activeText = "ON",
                            inactiveText = "OFF",
                            onClick = { onVoiceMutedChange(!voiceMuted) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Button(
                        onClick = onRepeatAlert,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(
                            text = "Repeat Last Alert",
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModePill(
    title: String,
    active: Boolean,
    activeText: String,
    inactiveText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (active) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f)
    val border = if (active) Color.White.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.18f)

    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(18.dp),
        color = bg,
        tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.78f),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = if (active) activeText else inactiveText,
                color = Color.White,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun DetectionSummaryCard(snapshot: SightSenseSnapshot) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(138.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.82f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live perception",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = "V ${snapshot.vision.size}  •  A ${snapshot.audio.size}",
                    color = Color.White.copy(alpha = 0.60f),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            DetectionLine(
                title = "Vision",
                value = if (snapshot.vision.isEmpty()) {
                    "No object risk detected"
                } else {
                    snapshot.vision.take(2).joinToString("  •  ") {
                        "${it.label} ${it.position} ${it.distance}"
                    }
                }
            )

            DetectionLine(
                title = "Audio",
                value = if (snapshot.audio.isEmpty()) {
                    "No warning sound"
                } else {
                    snapshot.audio.take(1).joinToString("  •  ") {
                        it.label
                    }
                }
            )
        }
    }
}

@Composable
private fun DetectionLine(
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.62f),
            fontWeight = FontWeight.Black,
            modifier = Modifier.width(68.dp),
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            text = value,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}