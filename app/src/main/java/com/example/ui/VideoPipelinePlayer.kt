package com.example.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.gstreamer.GStreamerBridge
import com.example.gstreamer.LogLevel
import kotlinx.coroutines.launch

private val ConsoleSurface = Color(0xFF161B22)
private val ConsoleBorder = Color(0xFF30363D)
private val ColorSuccess = Color(0xFF3FB950)
private val ColorError = Color(0xFFF85149)
private val ColorCmd = Color(0xFF58A6FF)
private val ColorText = Color(0xFFC9D1D9)

data class PipelinePreset(
    val title: String,
    val pipeline: String
)

val DEFAULT_PRESETS = listOf(
    PipelinePreset("Color Bars", "videotestsrc ! videoconvert ! autovideosink"),
    PipelinePreset("Bouncing Ball", "videotestsrc pattern=ball ! videoconvert ! autovideosink"),
    PipelinePreset("RTSP (rtspsrc + TCP)", "rtspsrc location=rtsp://rtspstream.com/pattern protocols=tcp latency=100 ! rtph264depay ! h264parse ! decodebin ! videoconvert ! autovideosink"),
    PipelinePreset("RTSP (uridecodebin + TCP)", "uridecodebin uri=rtsp://rtspstream.com/pattern source::protocols=tcp ! videoconvert ! autovideosink"),
    PipelinePreset("RTSP (playbin + TCP)", "playbin uri=rtsp://rtspstream.com/pattern source::protocols=tcp"),
    PipelinePreset("HTTP MP4", "souphttpsrc location=http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4 user-agent=\"Mozilla/5.0\" ssl-strict=false ! qtdemux ! h264parse ! decodebin ! videoconvert ! autovideosink"),
    PipelinePreset("Snow Noise", "videotestsrc pattern=snow ! videoconvert ! autovideosink")
)

@Composable
fun VideoPipelineSection(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val isPlaying by GStreamerBridge.isPlaying.collectAsState()
    val currentDebugLevel by GStreamerBridge.debugLevel.collectAsState()
    var pipelineText by remember { mutableStateOf("videotestsrc ! videoconvert ! autovideosink") }

    Column(modifier = modifier.fillMaxWidth()) {
        // Video Viewport Box (SurfaceView)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .border(1.dp, if (isPlaying) ColorSuccess else ConsoleBorder, RoundedCornerShape(8.dp))
        ) {
            // Native SurfaceView
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("gstreamer_surface_view"),
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                GStreamerBridge.setSurface(holder.surface)
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                GStreamerBridge.appendLog(
                                    LogLevel.INFO,
                                    "SURFACE",
                                    "Surface format changed: ${width}x${height} format=$format"
                                )
                                GStreamerBridge.setSurface(holder.surface)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                GStreamerBridge.clearSurface()
                            }
                        })
                    }
                }
            )

            // Status Indicator Overlay
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.TopStart)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isPlaying) ColorSuccess else Color(0xFF8B949E))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isPlaying) "OUTPUT: LIVE" else "OUTPUT: STOPPED",
                        color = if (isPlaying) ColorSuccess else ColorText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Fallback guide overlay if not playing
            if (!isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = "Video Surface",
                            tint = Color(0xFF484F58),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "SurfaceView Ready. Select preset & hit Play.",
                            color = Color(0xFF6E7681),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Preset Chips Horizontal Scroll
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DEFAULT_PRESETS.forEach { preset ->
                val isSelected = pipelineText == preset.pipeline
                var chipFocused by remember { mutableStateOf(false) }

                FilterChip(
                    selected = isSelected,
                    onClick = {
                        pipelineText = preset.pipeline
                        if (isPlaying) {
                            scope.launch {
                                GStreamerBridge.startPipeline(preset.pipeline)
                            }
                        }
                    },
                    label = {
                        Text(
                            text = preset.title,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    },
                    modifier = Modifier
                        .testTag("preset_${preset.title.replace(" ", "_").replace("(", "").replace(")", "").replace("+", "")}")
                        .onFocusChanged { chipFocused = it.isFocused }
                        .border(
                            width = if (chipFocused) 2.dp else 0.dp,
                            color = if (chipFocused) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .focusable(),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = ConsoleSurface,
                        labelColor = ColorText,
                        selectedContainerColor = Color(0xFF1F6FEB),
                        selectedLabelColor = Color.White
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = ConsoleBorder,
                        selectedBorderColor = Color(0xFF58A6FF)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Log Verbosity Level Selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "GST Log Verbosity:",
                color = ColorCmd,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val levels = listOf(
                    2 to "WARN (2)",
                    4 to "INFO (4)",
                    5 to "DEBUG (5)",
                    6 to "LOG (6)"
                )
                levels.forEach { (levelVal, label) ->
                    val isLvlSelected = currentDebugLevel == levelVal
                    FilterChip(
                        selected = isLvlSelected,
                        onClick = { GStreamerBridge.setDebugLevel(levelVal) },
                        label = {
                            Text(
                                text = label,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ConsoleSurface,
                            labelColor = ColorText,
                            selectedContainerColor = Color(0xFF238636),
                            selectedLabelColor = Color.White
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isLvlSelected,
                            borderColor = ConsoleBorder,
                            selectedBorderColor = ColorSuccess
                        ),
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Editable Pipeline Textbox
        OutlinedTextField(
            value = pipelineText,
            onValueChange = { pipelineText = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pipeline_input_field"),
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color.White
            ),
            label = {
                Text(
                    text = "GStreamer Pipeline String (gst_parse_launch)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = ColorCmd
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF090D13),
                unfocusedContainerColor = Color(0xFF090D13),
                focusedBorderColor = ColorCmd,
                unfocusedBorderColor = ConsoleBorder,
                cursorColor = ColorSuccess
            ),
            shape = RoundedCornerShape(6.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Play / Stop Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            var playFocused by remember { mutableStateOf(false) }
            var stopFocused by remember { mutableStateOf(false) }

            // Play Button
            Button(
                onClick = {
                    scope.launch {
                        GStreamerBridge.startPipeline(pipelineText)
                    }
                },
                enabled = !isPlaying,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("play_pipeline_button")
                    .onFocusChanged { playFocused = it.isFocused }
                    .border(
                        width = if (playFocused) 2.dp else 0.dp,
                        color = if (playFocused) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .focusable(),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF238636),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF238636).copy(alpha = 0.4f)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play Pipeline",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Play",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            // Stop Button
            Button(
                onClick = {
                    scope.launch {
                        GStreamerBridge.stopPipeline()
                    }
                },
                enabled = isPlaying,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("stop_pipeline_button")
                    .onFocusChanged { stopFocused = it.isFocused }
                    .border(
                        width = if (stopFocused) 2.dp else 0.dp,
                        color = if (stopFocused) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .focusable(),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFDA3633),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFDA3633).copy(alpha = 0.4f)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop Pipeline",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Stop",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}
