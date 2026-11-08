package com.example.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.gstreamer.GStreamerBridge
import com.example.gstreamer.LogLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ConsoleSurface = Color(0xFF161B22)
private val ConsoleBorder = Color(0xFF30363D)
private val ColorSuccess = Color(0xFF3FB950)
private val ColorError = Color(0xFFF85149)
private val ColorCmd = Color(0xFF58A6FF)
private val ColorText = Color(0xFFC9D1D9)

enum class CameraEngineMode {
    CAMERAX_VIEWFINDER,
    GSTREAMER_AHCSRC
}

@Composable
fun CameraViewfinderSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
        hasMicPermission = permissions[Manifest.permission.RECORD_AUDIO] == true
    }

    var cameraMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(CameraEngineMode.CAMERAX_VIEWFINDER) }
    var lensFacing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var isGstCameraActive by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var gstAhcDevice by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) } // 0 = Back, 1 = Front, -1 = Auto
    var gstRotationMethod by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("clockwise") } // "clockwise" = 90° CW (default for portrait)

    // RTSP Server Configuration states
    var rtspPort by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("8554") }
    var rtspPath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("/live") }
    var rtspEncoderPref by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("auto") } // "auto", "hardware", "software"
    var rtspResolution by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("1440x1080") } // "1440x1080" (Full Sensor 4:3 - No Crop), "1920x1440", "1280x720", "1920x1080"
    var viewportRatioOverride by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("auto") } // "auto", "3:4", "9:16", "16:9", "4:3"
    val isRtspRunning by GStreamerBridge.isRtspServerRunning.collectAsState()
    val rtspUrl by GStreamerBridge.rtspServerUrl.collectAsState()
    val availableEncoders by GStreamerBridge.availableEncoders.collectAsState()

    LaunchedEffect(Unit) {
        GStreamerBridge.queryAvailableEncoders()
    }

    // Unbind CameraX whenever switching to GStreamer ahcsrc mode to release camera hardware lock
    LaunchedEffect(cameraMode) {
        if (cameraMode == CameraEngineMode.GSTREAMER_AHCSRC) {
            try {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
                Log.i("CameraViewfinder", "CameraX provider unbound to grant exclusive access to GStreamer ahcsrc")
            } catch (e: Exception) {
                Log.w("CameraViewfinder", "Failed to unbind CameraX provider: ${e.message}")
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Compact Top Header Banner & Permissions Status
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            color = ConsoleSurface,
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Camera Viewfinder",
                        tint = ColorCmd,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Hardware Camera & RTSP Stream",
                        color = ColorText,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusBadge(label = "CAM", isGranted = hasCameraPermission)
                    StatusBadge(label = "MIC", isGranted = hasMicPermission)
                }
            }
        }

        // If permissions missing -> Permission Request Card
        if (!hasCameraPermission || !hasMicPermission) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = ConsoleSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleBorder)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Permissions Required",
                        tint = ColorCmd,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Camera & Microphone Access Required",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Grant camera and audio permissions to display the live viewfinder preview and enable GStreamer RTSP streaming.",
                        color = ColorText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.CAMERA,
                                    Manifest.permission.RECORD_AUDIO
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(44.dp)
                            .testTag("request_camera_mic_permissions_button"),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF238636),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Grant Access",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Grant Camera & Mic Access",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            // Engine Selector Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = cameraMode == CameraEngineMode.CAMERAX_VIEWFINDER,
                    onClick = {
                        if (isGstCameraActive) {
                            scope.launch { GStreamerBridge.stopPipeline() }
                            isGstCameraActive = false
                        }
                        cameraMode = CameraEngineMode.CAMERAX_VIEWFINDER
                    },
                    label = {
                        Text(
                            text = "CameraX High-FPS Viewfinder",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = ConsoleSurface,
                        labelColor = ColorText,
                        selectedContainerColor = Color(0xFF1F6FEB),
                        selectedLabelColor = Color.White
                    ),
                    modifier = Modifier.testTag("camerax_mode_chip")
                )

                FilterChip(
                    selected = cameraMode == CameraEngineMode.GSTREAMER_AHCSRC,
                    onClick = {
                        cameraMode = CameraEngineMode.GSTREAMER_AHCSRC
                    },
                    label = {
                        Text(
                            text = "GStreamer Capture & RTSP Server",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = ConsoleSurface,
                        labelColor = ColorText,
                        selectedContainerColor = Color(0xFF1F6FEB),
                        selectedLabelColor = Color.White
                    ),
                    modifier = Modifier.testTag("gstreamer_ahcsrc_mode_chip")
                )
            }

            val isPortraitRotation = gstRotationMethod == "clockwise" || gstRotationMethod == "counterclockwise"
            val is43 = rtspResolution.contains("1440") || rtspResolution.contains("1920x1440") || rtspResolution.contains("1280x960") || rtspResolution == "full_sensor" || rtspResolution == "4:3"
            val computedRatio = if (isPortraitRotation) {
                if (is43) 3f / 4f else 9f / 16f
            } else {
                if (is43) 4f / 3f else 16f / 9f
            }
            val activeBoxRatio = when (viewportRatioOverride) {
                "3:4" -> 3f / 4f
                "9:16" -> 9f / 16f
                "16:9" -> 16f / 9f
                "4:3" -> 4f / 3f
                else -> computedRatio
            }

            // Viewport Aspect Ratio Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Viewfinder:",
                    color = ColorCmd,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )

                FilterChip(
                    selected = viewportRatioOverride == "auto",
                    onClick = { viewportRatioOverride = "auto" },
                    label = { Text("Auto (${if (isPortraitRotation) (if (is43) "3:4 Portrait" else "9:16 Portrait") else (if (is43) "4:3 Landscape" else "16:9 Landscape")})", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = ConsoleSurface,
                        labelColor = ColorText,
                        selectedContainerColor = Color(0xFF1F6FEB),
                        selectedLabelColor = Color.White
                    )
                )

                FilterChip(
                    selected = viewportRatioOverride == "3:4",
                    onClick = { viewportRatioOverride = "3:4" },
                    label = { Text("3:4 Portrait (Full Sensor)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = ConsoleSurface,
                        labelColor = ColorText,
                        selectedContainerColor = Color(0xFF1F6FEB),
                        selectedLabelColor = Color.White
                    )
                )

                FilterChip(
                    selected = viewportRatioOverride == "16:9",
                    onClick = { viewportRatioOverride = "16:9" },
                    label = { Text("16:9 Widescreen", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = ConsoleSurface,
                        labelColor = ColorText,
                        selectedContainerColor = Color(0xFF1F6FEB),
                        selectedLabelColor = Color.White
                    )
                )

                FilterChip(
                    selected = viewportRatioOverride == "4:3",
                    onClick = { viewportRatioOverride = "4:3" },
                    label = { Text("4:3 Landscape", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = ConsoleSurface,
                        labelColor = ColorText,
                        selectedContainerColor = Color(0xFF1F6FEB),
                        selectedLabelColor = Color.White
                    )
                )
            }

            // Camera Viewfinder Video Box (Adaptive Full Sensor Aspect Ratio)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(activeBoxRatio)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .border(1.dp, ConsoleBorder, RoundedCornerShape(8.dp))
            ) {
                when (cameraMode) {
                    CameraEngineMode.CAMERAX_VIEWFINDER -> {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    scaleType = PreviewView.ScaleType.FIT_CENTER
                                }
                            },
                            update = { previewView ->
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }
                                    val selector = CameraSelector.Builder()
                                        .requireLensFacing(lensFacing)
                                        .build()

                                    try {
                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            selector,
                                            preview
                                        )
                                    } catch (exc: Exception) {
                                        Log.e("CameraViewfinder", "Use case binding failed", exc)
                                    }
                                }, ContextCompat.getMainExecutor(context))
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("camerax_preview_surface")
                        )
                    }

                    CameraEngineMode.GSTREAMER_AHCSRC -> {
                        // Native SurfaceView fed by GStreamer ahcsrc pipeline
                        AndroidView(
                            factory = { ctx ->
                                android.view.SurfaceView(ctx).apply {
                                    holder.addCallback(object : android.view.SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                            GStreamerBridge.setSurface(holder.surface)
                                        }

                                        override fun surfaceChanged(
                                            holder: android.view.SurfaceHolder,
                                            format: Int,
                                            width: Int,
                                            height: Int
                                        ) {
                                            GStreamerBridge.setSurface(holder.surface)
                                        }

                                        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                                            GStreamerBridge.clearSurface()
                                        }
                                    })
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("gstreamer_ahcsrc_surface")
                        )
                    }
                }

                // Overlay Controls & Status Badges
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .padding(10.dp)
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
                                .background(if (isRtspRunning || isGstCameraActive) ColorSuccess else ColorCmd)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                cameraMode == CameraEngineMode.CAMERAX_VIEWFINDER -> "CAMERAX: LIVE VIEWFINDER"
                                isRtspRunning -> "RTSP SERVER: BROADCASTING LIVE"
                                isGstCameraActive -> "GSTREAMER: AHCSRC ACTIVE"
                                else -> "GSTREAMER: IDLE (READY)"
                            },
                            color = if (isRtspRunning || isGstCameraActive) ColorSuccess else ColorCmd,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Lens switch button (For CameraX mode)
                if (cameraMode == CameraEngineMode.CAMERAX_VIEWFINDER) {
                    var switchFocused by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                        modifier = Modifier
                            .padding(10.dp)
                            .align(Alignment.TopEnd)
                            .onFocusChanged { switchFocused = it.isFocused }
                            .border(
                                width = if (switchFocused) 2.dp else 0.dp,
                                color = if (switchFocused) Color.White else Color.Transparent,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .focusable(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.7f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Switch Camera",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (lensFacing == CameraSelector.LENS_FACING_BACK) "BACK" else "FRONT",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // GStreamer ahcsrc & RTSP Controls Section
            if (cameraMode == CameraEngineMode.GSTREAMER_AHCSRC) {
                // Device Index Selector (Back / Front / Auto)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Camera:",
                        color = ColorCmd,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    FilterChip(
                        selected = gstAhcDevice == 0,
                        onClick = { gstAhcDevice = 0 },
                        label = { Text("Back (device=0)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ConsoleSurface,
                            labelColor = ColorText,
                            selectedContainerColor = Color(0xFF1F6FEB),
                            selectedLabelColor = Color.White
                        )
                    )

                    FilterChip(
                        selected = gstAhcDevice == 1,
                        onClick = { gstAhcDevice = 1 },
                        label = { Text("Front (device=1)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ConsoleSurface,
                            labelColor = ColorText,
                            selectedContainerColor = Color(0xFF1F6FEB),
                            selectedLabelColor = Color.White
                        )
                    )

                    FilterChip(
                        selected = gstAhcDevice == -1,
                        onClick = { gstAhcDevice = -1 },
                        label = { Text("Auto", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ConsoleSurface,
                            labelColor = ColorText,
                            selectedContainerColor = Color(0xFF1F6FEB),
                            selectedLabelColor = Color.White
                        )
                    )
                }

                // Rotation Selector (90° CW, 90° CCW, 0° Native)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Rotation:",
                        color = ColorCmd,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    FilterChip(
                        selected = gstRotationMethod == "clockwise",
                        onClick = { gstRotationMethod = "clockwise" },
                        label = { Text("90° CW (Back Portrait)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ConsoleSurface,
                            labelColor = ColorText,
                            selectedContainerColor = Color(0xFF1F6FEB),
                            selectedLabelColor = Color.White
                        )
                    )

                    FilterChip(
                        selected = gstRotationMethod == "counterclockwise",
                        onClick = { gstRotationMethod = "counterclockwise" },
                        label = { Text("90° CCW (Front Portrait)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ConsoleSurface,
                            labelColor = ColorText,
                            selectedContainerColor = Color(0xFF1F6FEB),
                            selectedLabelColor = Color.White
                        )
                    )

                    FilterChip(
                        selected = gstRotationMethod == "none",
                        onClick = { gstRotationMethod = "none" },
                        label = { Text("0° (Native Landscape)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ConsoleSurface,
                            labelColor = ColorText,
                            selectedContainerColor = Color(0xFF1F6FEB),
                            selectedLabelColor = Color.White
                        )
                    )
                }

                // Local Preview Control Buttons (Start / Stop ahcsrc Preview)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                // 1. Unbind CameraX completely to release Android Camera2 hardware lock
                                try {
                                    val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                                    cameraProvider.unbindAll()
                                } catch (e: Exception) {
                                    Log.w("CameraViewfinder", "CameraX unbind failed: ${e.message}")
                                }

                                // 2. Stop any active GStreamer pipeline
                                GStreamerBridge.stopPipeline()
                                delay(150) // Allow Android CameraManager HAL to finalize session close

                                // 3. Build zero-latency ahcsrc pipeline with full sensor caps & leaky queue
                                val devParam = if (gstAhcDevice >= 0) "device=$gstAhcDevice " else ""
                                val flipParam = if (gstRotationMethod != "none") "! videoflip method=$gstRotationMethod " else ""
                                val resParts = rtspResolution.split("x")
                                val capsParam = if (resParts.size == 2 && resParts[0].toIntOrNull() != null && resParts[1].toIntOrNull() != null) {
                                    "! video/x-raw,width=${resParts[0]},height=${resParts[1]} "
                                } else if (rtspResolution == "720p") {
                                    "! video/x-raw,width=1280,height=720 "
                                } else if (rtspResolution == "1080p") {
                                    "! video/x-raw,width=1920,height=1080 "
                                } else if (rtspResolution == "480p") {
                                    "! video/x-raw,width=854,height=480 "
                                } else {
                                    "! video/x-raw,width=1440,height=1080 "
                                }
                                val pipelineStr = "ahcsrc ${devParam}${capsParam}! queue max-size-buffers=1 leaky=downstream ${flipParam}! videoconvert ! glimagesink sync=false"

                                GStreamerBridge.startPipeline(pipelineStr)
                                isGstCameraActive = true
                            }
                        },
                        enabled = !isGstCameraActive && !isRtspRunning,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .testTag("start_gstreamer_camera_button"),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1F6FEB),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Start Camera Preview",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Start Local Preview",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                GStreamerBridge.stopPipeline()
                                isGstCameraActive = false
                            }
                        },
                        enabled = isGstCameraActive,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .testTag("stop_gstreamer_camera_button"),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFDA3633),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Camera Preview",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Stop Local Preview",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }

                // Native GStreamer RTSP Server Configuration & Control Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ConsoleSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Router,
                                    contentDescription = "RTSP Server",
                                    tint = ColorCmd,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Native RTSP Broadcast Server",
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }

                            Surface(
                                color = if (isRtspRunning) ColorSuccess.copy(alpha = 0.2f) else ConsoleBorder.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(4.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isRtspRunning) ColorSuccess else ConsoleBorder
                                )
                            ) {
                                Text(
                                    text = if (isRtspRunning) "ONLINE" else "OFFLINE",
                                    color = if (isRtspRunning) ColorSuccess else ColorText,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = "Ultra-low-latency RTSP streaming with zero-latency leaky queue, MTU 1400, and dynamic H.264 encoding.",
                            color = ColorText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )

                        // Stream Resolution Selector (Full 4:3 sensor options with zero crop)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Camera Resolution (4:3 uses full sensor with ZERO cropping):",
                                color = ColorCmd,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = rtspResolution == "1440x1080",
                                    onClick = { rtspResolution = "1440x1080" },
                                    enabled = !isRtspRunning,
                                    label = { Text("⚡ Full Sensor 4:3 (1440x1080 - No Crop)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = ConsoleSurface,
                                        labelColor = ColorText,
                                        selectedContainerColor = Color(0xFF1F6FEB),
                                        selectedLabelColor = Color.White
                                    )
                                )
                                FilterChip(
                                    selected = rtspResolution == "1920x1440",
                                    onClick = { rtspResolution = "1920x1440" },
                                    enabled = !isRtspRunning,
                                    label = { Text("Full Sensor 4:3 Max (1920x1440)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = ConsoleSurface,
                                        labelColor = ColorText,
                                        selectedContainerColor = Color(0xFF1F6FEB),
                                        selectedLabelColor = Color.White
                                    )
                                )
                                FilterChip(
                                    selected = rtspResolution == "1280x960",
                                    onClick = { rtspResolution = "1280x960" },
                                    enabled = !isRtspRunning,
                                    label = { Text("Full Sensor 4:3 SD (1280x960)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = ConsoleSurface,
                                        labelColor = ColorText,
                                        selectedContainerColor = Color(0xFF1F6FEB),
                                        selectedLabelColor = Color.White
                                    )
                                )
                                FilterChip(
                                    selected = rtspResolution == "1280x720" || rtspResolution == "720p",
                                    onClick = { rtspResolution = "1280x720" },
                                    enabled = !isRtspRunning,
                                    label = { Text("16:9 Widescreen (1280x720)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = ConsoleSurface,
                                        labelColor = ColorText,
                                        selectedContainerColor = Color(0xFF1F6FEB),
                                        selectedLabelColor = Color.White
                                    )
                                )
                                FilterChip(
                                    selected = rtspResolution == "1920x1080" || rtspResolution == "1080p",
                                    onClick = { rtspResolution = "1920x1080" },
                                    enabled = !isRtspRunning,
                                    label = { Text("16:9 Widescreen (1920x1080)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = ConsoleSurface,
                                        labelColor = ColorText,
                                        selectedContainerColor = Color(0xFF1F6FEB),
                                        selectedLabelColor = Color.White
                                    )
                                )
                            }
                        }

                        // Encoder Preference Selector
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "H.264 Encoder Preference:",
                                color = ColorCmd,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = rtspEncoderPref == "auto",
                                    onClick = { rtspEncoderPref = "auto" },
                                    enabled = !isRtspRunning,
                                    label = { Text("⚡ Auto (Hardware first)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = ConsoleSurface,
                                        labelColor = ColorText,
                                        selectedContainerColor = Color(0xFF1F6FEB),
                                        selectedLabelColor = Color.White
                                    )
                                )
                                FilterChip(
                                    selected = rtspEncoderPref == "hardware",
                                    onClick = { rtspEncoderPref = "hardware" },
                                    enabled = !isRtspRunning,
                                    label = { Text("Hardware (MediaCodec)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = ConsoleSurface,
                                        labelColor = ColorText,
                                        selectedContainerColor = Color(0xFF1F6FEB),
                                        selectedLabelColor = Color.White
                                    )
                                )
                                FilterChip(
                                    selected = rtspEncoderPref == "software",
                                    onClick = { rtspEncoderPref = "software" },
                                    enabled = !isRtspRunning,
                                    label = { Text("Software (x264 Ultrafast)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = ConsoleSurface,
                                        labelColor = ColorText,
                                        selectedContainerColor = Color(0xFF1F6FEB),
                                        selectedLabelColor = Color.White
                                    )
                                )
                            }
                        }

                        // Detected System Encoders Info
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleBorder)
                        ) {
                            Text(
                                text = if (availableEncoders.isNotBlank()) "Available H.264 Encoders: $availableEncoders" else "Encoders: amcvidenc (MediaCodec) & x264enc (libgstx264)",
                                color = Color(0xFF8B949E),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }

                        // Port & Path input fields
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = rtspPort,
                                onValueChange = { rtspPort = it },
                                label = { Text("RTSP Port", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                                singleLine = true,
                                enabled = !isRtspRunning,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ColorCmd,
                                    unfocusedBorderColor = ConsoleBorder,
                                    focusedLabelColor = ColorCmd,
                                    unfocusedLabelColor = ColorText,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = ColorText
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("rtsp_port_input")
                            )

                            OutlinedTextField(
                                value = rtspPath,
                                onValueChange = { rtspPath = it },
                                label = { Text("Stream Path", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                                singleLine = true,
                                enabled = !isRtspRunning,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ColorCmd,
                                    unfocusedBorderColor = ConsoleBorder,
                                    focusedLabelColor = ColorCmd,
                                    unfocusedLabelColor = ColorText,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = ColorText
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("rtsp_path_input")
                            )
                        }

                        // Start / Stop RTSP Server Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        // 1. Unbind CameraX hardware session
                                        try {
                                            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                                            cameraProvider.unbindAll()
                                        } catch (e: Exception) {
                                            Log.w("CameraViewfinder", "CameraX unbind failed: ${e.message}")
                                        }

                                        // 2. Stop local GStreamer preview pipeline if running
                                        if (isGstCameraActive) {
                                            GStreamerBridge.stopPipeline()
                                            isGstCameraActive = false
                                            delay(150)
                                        }

                                        // 3. Start native GStreamer RTSP Server
                                        val err = GStreamerBridge.startRtspServer(
                                            port = rtspPort.ifBlank { "8554" },
                                            path = rtspPath.ifBlank { "/live" },
                                            deviceIndex = gstAhcDevice,
                                            rotationMethod = gstRotationMethod,
                                            encoderPref = rtspEncoderPref,
                                            resolution = rtspResolution
                                        )

                                        if (err != null) {
                                            Toast.makeText(context, "RTSP Server Error: $err", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                enabled = !isRtspRunning,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("start_rtsp_server_button"),
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF238636),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Start RTSP Server",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Start RTSP Server",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }

                            Button(
                                onClick = {
                                    scope.launch {
                                        GStreamerBridge.stopRtspServer()
                                    }
                                },
                                enabled = isRtspRunning,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("stop_rtsp_server_button"),
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFDA3633),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop RTSP Server",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Stop RTSP Server",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // RTSP URL Display & Copy Box
                        if (isRtspRunning && rtspUrl.isNotEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color.Black,
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, ColorSuccess)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "LIVE RTSP STREAM URL:",
                                            color = ColorSuccess,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = rtspUrl,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            try {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = ClipData.newPlainText("RTSP Stream URL", rtspUrl)
                                                clipboard.setPrimaryClip(clip)
                                                Toast.makeText(context, "Copied RTSP URL to clipboard!", Toast.LENGTH_SHORT).show()
                                                GStreamerBridge.appendLog(LogLevel.SUCCESS, "CLIPBOARD", "Copied RTSP URL: $rtspUrl")
                                            } catch (e: Exception) {
                                                Log.e("CameraViewfinder", "Clipboard copy error", e)
                                            }
                                        },
                                        modifier = Modifier.testTag("copy_rtsp_url_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy RTSP URL",
                                            tint = ColorCmd,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Instructions Card for OBS / VLC
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ConsoleSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "How to Connect Clients:",
                            color = ColorCmd,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "• OBS Studio: Add 'Media Source' -> Uncheck 'Local File' -> Paste RTSP URL into Input field.",
                            color = ColorText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                        Text(
                            text = "• VLC Player: Media -> Open Network Stream (Ctrl+N) -> Paste RTSP URL.",
                            color = ColorText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Mic info banner
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ConsoleSurface,
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Mic Ready",
                        tint = if (hasMicPermission) ColorSuccess else ColorCmd,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (hasMicPermission) "Audio source ready for RTSP stream." else "Mic permission required for RTSP audio stream.",
                        color = ColorText,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, isGranted: Boolean) {
    Surface(
        color = if (isGranted) Color(0xFF1F6FEB).copy(alpha = 0.2f) else Color(0xFFDA3633).copy(alpha = 0.2f),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isGranted) Color(0xFF58A6FF) else ColorError
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isGranted) ColorSuccess else ColorError)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$label: ${if (isGranted) "OK" else "LOCKED"}",
                color = if (isGranted) ColorText else ColorError,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
