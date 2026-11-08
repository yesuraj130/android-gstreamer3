package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.gstreamer.GStreamerBridge
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

    var cameraMode by remember { mutableStateOf(CameraEngineMode.CAMERAX_VIEWFINDER) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var isGstCameraActive by remember { mutableStateOf(false) }
    var gstAhcDevice by remember { mutableStateOf(0) } // 0 = Back, 1 = Front, -1 = Auto
    var gstRotationMethod by remember { mutableStateOf("clockwise") } // "clockwise" = 90° CW (default for portrait)

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
            .padding(12.dp)
    ) {
        // Mode Header & Permissions Badge
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = ConsoleSurface,
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Camera Viewfinder",
                        tint = ColorCmd,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Camera Viewfinder (Live Capture)",
                        color = ColorText,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Camera permission status dot
                    StatusBadge(
                        label = "CAM",
                        isGranted = hasCameraPermission
                    )
                    // Mic permission status dot
                    StatusBadge(
                        label = "MIC",
                        isGranted = hasMicPermission
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // If permissions missing -> Permission Request UI
        if (!hasCameraPermission || !hasMicPermission) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
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
                        text = "Grant camera and audio permissions to display the live viewfinder preview and prepare for RTSP streaming.",
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
                            .fillMaxWidth(0.8f)
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
            // Permissions granted -> Engine Selector Tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                            text = "GStreamer Capture (ahcsrc)",
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

            Spacer(modifier = Modifier.height(8.dp))

            // Camera Viewfinder Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .border(1.dp, ConsoleBorder, RoundedCornerShape(8.dp))
            ) {
                when (cameraMode) {
                    CameraEngineMode.CAMERAX_VIEWFINDER -> {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
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
                                .background(ColorSuccess)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (cameraMode == CameraEngineMode.CAMERAX_VIEWFINDER) "CAMERAX: LIVE VIEWFINDER" else "GSTREAMER: AHCSRC ACTIVE",
                            color = ColorSuccess,
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

            Spacer(modifier = Modifier.height(10.dp))

            // Action Bar for GStreamer ahcsrc pipeline
            if (cameraMode == CameraEngineMode.GSTREAMER_AHCSRC) {
                // Device Index Selector (Back / Front / Auto)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
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

                // Rotation Selector (90° CW, 90° CCW, 180°, 0° Native)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
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
                        label = { Text("0° (Landscape)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ConsoleSurface,
                            labelColor = ColorText,
                            selectedContainerColor = Color(0xFF1F6FEB),
                            selectedLabelColor = Color.White
                        )
                    )
                }

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
                                kotlinx.coroutines.delay(150) // Allow Android CameraManager HAL to finalize session close

                                // 3. Build zero-latency ahcsrc pipeline with leaky queue & videoflip rotation
                                val devParam = if (gstAhcDevice >= 0) "device=$gstAhcDevice " else ""
                                val flipParam = if (gstRotationMethod != "none") "! videoflip method=$gstRotationMethod " else ""
                                val pipelineStr = "ahcsrc ${devParam}! queue max-size-buffers=1 leaky=downstream ${flipParam}! videoconvert ! glimagesink sync=false"

                                GStreamerBridge.startPipeline(pipelineStr)
                                isGstCameraActive = true
                            }
                        },
                        enabled = !isGstCameraActive,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("start_gstreamer_camera_button"),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF238636),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Start Camera Pipeline",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Start ahcsrc Pipeline",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
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
                            .height(44.dp)
                            .testTag("stop_gstreamer_camera_button"),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFDA3633),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Camera Pipeline",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Stop ahcsrc Pipeline",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Info Card about RTSP Server Next Phase
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ConsoleSurface,
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Mic Ready",
                        tint = if (hasMicPermission) ColorSuccess else ColorCmd,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasMicPermission) "Audio source ready for RTSP server stream." else "Mic permission required for RTSP audio.",
                        color = ColorText,
                        fontSize = 11.sp,
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
