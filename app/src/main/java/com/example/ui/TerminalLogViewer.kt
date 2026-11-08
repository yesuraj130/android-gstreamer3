package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gstreamer.ConsoleLog
import com.example.gstreamer.GStreamerBridge
import com.example.gstreamer.GstEngineState
import com.example.gstreamer.LogLevel
import kotlinx.coroutines.launch

private val ConsoleBackground = Color(0xFF0D1117)
private val ConsoleSurface = Color(0xFF161B22)
private val ConsoleBorder = Color(0xFF30363D)
private val ColorCmd = Color(0xFF58A6FF)
private val ColorSuccess = Color(0xFF3FB950)
private val ColorWarn = Color(0xFFD29922)
private val ColorError = Color(0xFFF85149)
private val ColorInfo = Color(0xFF8B949E)
private val ColorText = Color(0xFFC9D1D9)

@Composable
fun TerminalLogViewer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs by GStreamerBridge.logs.collectAsState()
    val engineState by GStreamerBridge.engineState.collectAsState()
    val listState = rememberLazyListState()

    var autoScroll by remember { mutableStateOf(true) }

    // Run diagnostics automatically ONCE on initial launch if engineState is Idle
    LaunchedEffect(Unit) {
        if (GStreamerBridge.engineState.value == GstEngineState.Idle) {
            GStreamerBridge.runDiagnostics(context, clearLogs = false)
        }
    }

    // Auto scroll to bottom when new logs arrive
    LaunchedEffect(logs.size, autoScroll) {
        if (autoScroll && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .background(ConsoleBackground),
        color = ConsoleBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Top Window Bar (Terminal Style)
            TerminalHeader(engineState = engineState)

            Spacer(modifier = Modifier.height(8.dp))

            // Spec Summary Card (GStreamer 1.28 + Dual ABI + 2020 TV)
            SpecBanner(engineState = engineState)

            Spacer(modifier = Modifier.height(8.dp))

            // Terminal Log Window
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF090D13))
                    .border(1.dp, ConsoleBorder, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Initializing terminal environment...",
                            color = ColorInfo,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("console_log_list")
                    ) {
                        items(logs, key = { it.id }) { log ->
                            LogItemView(log = log)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Control Bar (TV D-Pad & Mobile Touch Friendly)
            TerminalControls(
                isInspecting = engineState is GstEngineState.Inspecting,
                onRun = {
                    scope.launch {
                        GStreamerBridge.runDiagnostics(context)
                    }
                },
                onClear = {
                    GStreamerBridge.clearLogs()
                },
                onCopy = {
                    val fullText = logs.joinToString("\n") { "[${it.time}] [${it.tag}] ${it.message}" }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("GStreamer Logs", fullText))
                    Toast.makeText(context, "Terminal logs copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
private fun TerminalHeader(engineState: GstEngineState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ConsoleSurface)
            .border(1.dp, ConsoleBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Window dot indicators
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF5F56))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFBD2E))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF27C93F))
            )

            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = "Terminal Icon",
                tint = ColorCmd,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "gst-terminal@android:~#",
                color = ColorText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
        }

        // Status indicator
        val (statusText, statusColor) = when (engineState) {
            is GstEngineState.Ready -> "GST 1.28 VERIFIED" to ColorSuccess
            is GstEngineState.Inspecting -> "DIAGNOSING..." to ColorWarn
            is GstEngineState.NeedsNativeBuild -> "STANDBY (READY FOR CI)" to ColorCmd
            is GstEngineState.Failed -> "LINK ERROR" to ColorError
            GstEngineState.Idle -> "READY" to ColorInfo
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = statusText,
                color = statusColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun SpecBanner(engineState: GstEngineState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ConsoleSurface,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = "TARGET: GStreamer 1.28 (Tutorial 1)",
                    color = ColorSuccess,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "ABIs: armeabi-v7a + arm64-v8a (Dual) | MinSDK: 26 (2020+ TV & Mobile)",
                    color = ColorInfo,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (engineState is GstEngineState.Ready) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = engineState.version,
                    color = ColorCmd,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun LogItemView(log: ConsoleLog) {
    val levelColor = when (log.level) {
        LogLevel.CMD -> ColorCmd
        LogLevel.SUCCESS -> ColorSuccess
        LogLevel.WARN -> ColorWarn
        LogLevel.ERROR -> ColorError
        LogLevel.INFO -> ColorInfo
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = log.time,
            color = Color(0xFF6E7681),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.width(78.dp)
        )

        Text(
            text = "[${log.tag}]",
            color = levelColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            modifier = Modifier.width(74.dp)
        )

        Text(
            text = log.message,
            color = if (log.level == LogLevel.CMD) Color(0xFFE6EDF3) else levelColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TerminalControls(
    isInspecting: Boolean,
    onRun: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        var runFocused by remember { mutableStateOf(false) }
        var clearFocused by remember { mutableStateOf(false) }
        var copyFocused by remember { mutableStateOf(false) }

        // Primary Action: Run Check (D-pad focusable)
        Button(
            onClick = onRun,
            enabled = !isInspecting,
            modifier = Modifier
                .weight(1.3f)
                .height(44.dp)
                .testTag("run_diagnostics_button")
                .onFocusChanged { runFocused = it.isFocused }
                .border(
                    width = if (runFocused) 2.dp else 0.dp,
                    color = if (runFocused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(6.dp)
                )
                .focusable(),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF238636),
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Run",
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isInspecting) "Checking..." else "Run Diagnostics",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        // Copy button
        OutlinedButton(
            onClick = onCopy,
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .testTag("copy_logs_button")
                .onFocusChanged { copyFocused = it.isFocused }
                .border(
                    width = if (copyFocused) 2.dp else 1.dp,
                    color = if (copyFocused) Color.White else ConsoleBorder,
                    shape = RoundedCornerShape(6.dp)
                )
                .focusable(),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = ColorText
            )
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy",
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Copy Logs",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Clear button
        OutlinedButton(
            onClick = onClear,
            modifier = Modifier
                .weight(0.9f)
                .height(44.dp)
                .testTag("clear_logs_button")
                .onFocusChanged { clearFocused = it.isFocused }
                .border(
                    width = if (clearFocused) 2.dp else 1.dp,
                    color = if (clearFocused) Color.White else ConsoleBorder,
                    shape = RoundedCornerShape(6.dp)
                )
                .focusable(),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = ColorText
            )
        ) {
            Icon(
                imageVector = Icons.Default.Clear,
                contentDescription = "Clear",
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Clear",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
