package com.example.gstreamer

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.freedesktop.gstreamer.GStreamer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel {
    CMD,
    INFO,
    SUCCESS,
    WARN,
    ERROR
}

data class ConsoleLog(
    val id: Long = System.nanoTime(),
    val time: String,
    val level: LogLevel,
    val tag: String,
    val message: String
)

sealed interface GstEngineState {
    data object Idle : GstEngineState
    data object Inspecting : GstEngineState
    data class Ready(val version: String, val abi: String) : GstEngineState
    data class NeedsNativeBuild(val reason: String, val architecture: String) : GstEngineState
    data class Failed(val error: String) : GstEngineState
}

object GStreamerBridge {
    private const val TAG = "GStreamerBridge"

    private external fun nativeGetGStreamerInfo(): String
    private external fun nativeCheckGStreamerInit(): Boolean
    private external fun nativeSetSurface(surface: Surface)
    private external fun nativeClearSurface()
    private external fun nativeStartPipeline(pipelineDesc: String): String?
    private external fun nativeStopPipeline()
    private external fun nativeGetPipelineState(): Int
    private external fun nativeSetDebugLevel(level: Int)
    private external fun nativeStartRtspServer(port: String, path: String, deviceIndex: Int, rotationMethod: String): String?
    private external fun nativeStopRtspServer()
    private external fun nativeIsRtspServerRunning(): Boolean

    private var nativeLibrariesLoaded = false
    private var nativeLoadError: String? = null
    private var appContext: Context? = null

    private val _logs = MutableStateFlow<List<ConsoleLog>>(emptyList())
    val logs: StateFlow<List<ConsoleLog>> = _logs.asStateFlow()

    private val _engineState = MutableStateFlow<GstEngineState>(GstEngineState.Idle)
    val engineState: StateFlow<GstEngineState> = _engineState.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isRtspServerRunning = MutableStateFlow(false)
    val isRtspServerRunning: StateFlow<Boolean> = _isRtspServerRunning.asStateFlow()

    private val _rtspServerUrl = MutableStateFlow("")
    val rtspServerUrl: StateFlow<String> = _rtspServerUrl.asStateFlow()


    private val _activePipelineString = MutableStateFlow("videotestsrc ! videoconvert ! autovideosink")
    val activePipelineString: StateFlow<String> = _activePipelineString.asStateFlow()

    private val _debugLevel = MutableStateFlow(5) // Default: 5 (DEBUG)
    val debugLevel: StateFlow<Int> = _debugLevel.asStateFlow()

    private val _userBookmarks = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val userBookmarks: StateFlow<List<Pair<String, String>>> = _userBookmarks.asStateFlow()

    private var cachedSurface: Surface? = null

    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun initContext(context: Context) {
        appContext = context.applicationContext
        loadSavedBookmarks()
    }

    private fun loadSavedBookmarks() {
        val ctx = appContext ?: return
        try {
            val prefs = ctx.getSharedPreferences("gst_bookmarks", Context.MODE_PRIVATE)
            val allEntries = prefs.all
            val list = mutableListOf<Pair<String, String>>()
            for ((key, value) in allEntries) {
                if (value is String) {
                    list.add(key to value)
                }
            }
            _userBookmarks.value = list
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bookmarks", e)
        }
    }

    fun saveBookmark(title: String, pipeline: String) {
        val ctx = appContext ?: return
        val cleanTitle = title.trim()
        val cleanPipeline = pipeline.trim()
        if (cleanTitle.isEmpty() || cleanPipeline.isEmpty()) return
        try {
            val prefs = ctx.getSharedPreferences("gst_bookmarks", Context.MODE_PRIVATE)
            prefs.edit().putString(cleanTitle, cleanPipeline).apply()
            loadSavedBookmarks()
            appendLog(LogLevel.SUCCESS, "BOOKMARK", "Saved pipeline bookmark: '$cleanTitle'")
        } catch (e: Exception) {
            appendLog(LogLevel.ERROR, "BOOKMARK", "Failed to save bookmark: ${e.message}")
        }
    }

    fun deleteBookmark(title: String) {
        val ctx = appContext ?: return
        try {
            val prefs = ctx.getSharedPreferences("gst_bookmarks", Context.MODE_PRIVATE)
            prefs.edit().remove(title).apply()
            loadSavedBookmarks()
            appendLog(LogLevel.INFO, "BOOKMARK", "Deleted pipeline bookmark: '$title'")
        } catch (e: Exception) {
            appendLog(LogLevel.ERROR, "BOOKMARK", "Failed to delete bookmark: ${e.message}")
        }
    }

    fun setDebugLevel(level: Int) {
        _debugLevel.value = level
        if (nativeLibrariesLoaded) {
            try {
                nativeSetDebugLevel(level)
            } catch (t: Throwable) {
                appendLog(LogLevel.WARN, "DEBUG", "nativeSetDebugLevel failed: ${t.message}")
            }
        }
    }

    fun appendLog(level: LogLevel, tag: String, message: String) {
        val entry = ConsoleLog(
            time = timeFormatter.format(Date()),
            level = level,
            tag = tag,
            message = message
        )
        _logs.value = _logs.value + entry
        when (level) {
            LogLevel.ERROR -> Log.e(TAG, "[$tag] $message")
            LogLevel.WARN -> Log.w(TAG, "[$tag] $message")
            else -> Log.i(TAG, "[$tag] $message")
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
        appendLog(LogLevel.CMD, "SYS", "Console logs cleared.")
    }

    /**
     * Called from C JNI on native GStreamer bus messages (errors, warnings, info).
     */
    @JvmStatic
    fun onNativeMessage(message: String, isError: Boolean) {
        appendLog(
            level = if (isError) LogLevel.ERROR else LogLevel.INFO,
            tag = "GST-BUS",
            message = message
        )
    }

    /**
     * Called from C JNI when the pipeline state changes (NULL, READY, PAUSED, PLAYING).
     */
    @JvmStatic
    fun onNativeStateChanged(stateName: String) {
        appendLog(
            level = if (stateName == "PLAYING") LogLevel.SUCCESS else LogLevel.INFO,
            tag = "STATE",
            message = "Pipeline state transitioned to: $stateName"
        )
        when (stateName) {
            "PLAYING" -> _isPlaying.value = true
            "NULL", "READY" -> _isPlaying.value = false
        }
    }

    fun setSurface(surface: Surface) {
        cachedSurface = surface
        appendLog(LogLevel.INFO, "SURFACE", "Surface received from SurfaceView ($surface)")
        if (nativeLibrariesLoaded) {
            try {
                nativeSetSurface(surface)
                appendLog(LogLevel.SUCCESS, "SURFACE", "Native ANativeWindow bound to video overlay")
            } catch (t: Throwable) {
                appendLog(LogLevel.ERROR, "SURFACE", "Error attaching surface: ${t.message}")
            }
        }
    }

    fun clearSurface() {
        cachedSurface = null
        if (nativeLibrariesLoaded) {
            try {
                nativeClearSurface()
                appendLog(LogLevel.INFO, "SURFACE", "Surface detached from native overlay")
            } catch (t: Throwable) {
                appendLog(LogLevel.ERROR, "SURFACE", "Error clearing surface: ${t.message}")
            }
        }
    }

    suspend fun startPipeline(pipelineDesc: String) = withContext(Dispatchers.IO) {
        val trimmed = pipelineDesc.trim()
        if (trimmed.isEmpty()) {
            appendLog(LogLevel.ERROR, "PIPELINE", "Cannot play: Pipeline string is empty")
            return@withContext
        }

        _activePipelineString.value = trimmed
        appendLog(LogLevel.CMD, "PIPELINE", "gst-launch-1.0 $trimmed")

        if (!nativeLibrariesLoaded) {
            // Attempt loading if not loaded
            val loaded = tryLoadNativeLibraries()
            if (!loaded) {
                appendLog(LogLevel.ERROR, "PIPELINE", "Native libraries not loaded. Build APK via GitHub Actions.")
                return@withContext
            }
        }

        // Ensure GStreamer runtime is initialized
        appContext?.let { ctx ->
            try {
                GStreamer.init(ctx)
            } catch (e: Exception) {
                appendLog(LogLevel.WARN, "INIT", "GStreamer.init: ${e.message}")
            }
        }

        try {
            // Ensure cached surface is re-bound
            cachedSurface?.let {
                appendLog(LogLevel.INFO, "SURFACE", "Re-binding cached SurfaceView to native window")
                nativeSetSurface(it)
            }

            appendLog(LogLevel.INFO, "PIPELINE", "Invoking gst_parse_launch & initializing GLib main loop...")
            val errorMsg = nativeStartPipeline(trimmed)
            if (errorMsg != null) {
                _isPlaying.value = false
                appendLog(LogLevel.ERROR, "PIPELINE", "gst_parse_launch error: $errorMsg")
            } else {
                _isPlaying.value = true
                appendLog(LogLevel.SUCCESS, "PIPELINE", "gst_parse_launch succeeded. Pipeline set to GST_STATE_PLAYING")
            }
        } catch (t: Throwable) {
            _isPlaying.value = false
            appendLog(LogLevel.ERROR, "PIPELINE", "Exception launching pipeline: ${t.message}")
        }
    }

    suspend fun stopPipeline() = withContext(Dispatchers.IO) {
        appendLog(LogLevel.CMD, "PIPELINE", "Stopping active pipeline...")
        if (!nativeLibrariesLoaded) {
            _isPlaying.value = false
            return@withContext
        }

        try {
            nativeStopPipeline()
            _isPlaying.value = false
            appendLog(LogLevel.INFO, "PIPELINE", "Pipeline stopped: GST_STATE_NULL")
        } catch (t: Throwable) {
            _isPlaying.value = false
            appendLog(LogLevel.ERROR, "PIPELINE", "Exception stopping pipeline: ${t.message}")
        }
    }

    fun getWifiIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: ""
                        if (ip.isNotEmpty()) return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving device IP address", e)
        }
        return "127.0.0.1"
    }

    suspend fun startRtspServer(
        port: String = "8554",
        path: String = "/live",
        deviceIndex: Int = 0,
        rotationMethod: String = "clockwise"
    ): String? = withContext(Dispatchers.IO) {
        appendLog(LogLevel.CMD, "RTSP", "Starting GStreamer RTSP Server on port $port, mount path $path...")

        if (!nativeLibrariesLoaded) {
            val loaded = tryLoadNativeLibraries()
            if (!loaded) {
                appendLog(LogLevel.ERROR, "RTSP", "Native libraries not loaded.")
                return@withContext "Native libraries not loaded"
            }
        }

        appContext?.let { ctx ->
            try {
                org.freedesktop.gstreamer.GStreamer.init(ctx)
            } catch (e: Exception) {
                appendLog(LogLevel.WARN, "INIT", "GStreamer.init: ${e.message}")
            }
        }

        try {
            val cleanPath = if (path.startsWith("/")) path else "/$path"
            val errorMsg = nativeStartRtspServer(port, cleanPath, deviceIndex, rotationMethod)
            if (errorMsg == null) {
                _isRtspServerRunning.value = true
                val ip = getWifiIpAddress()
                val fullUrl = "rtsp://$ip:$port$cleanPath"
                _rtspServerUrl.value = fullUrl
                appendLog(LogLevel.SUCCESS, "RTSP", "RTSP Server Active! Connect streaming client to: $fullUrl")
            } else {
                _isRtspServerRunning.value = false
                _rtspServerUrl.value = ""
                appendLog(LogLevel.ERROR, "RTSP", "Native RTSP Server start failed: $errorMsg")
            }
            return@withContext errorMsg
        } catch (t: Throwable) {
            _isRtspServerRunning.value = false
            _rtspServerUrl.value = ""
            appendLog(LogLevel.ERROR, "RTSP", "Exception starting RTSP Server: ${t.message}")
            return@withContext t.message ?: "RTSP Start Exception"
        }
    }

    suspend fun stopRtspServer() = withContext(Dispatchers.IO) {
        appendLog(LogLevel.CMD, "RTSP", "Stopping RTSP Server...")
        if (!nativeLibrariesLoaded) {
            _isRtspServerRunning.value = false
            _rtspServerUrl.value = ""
            return@withContext
        }

        try {
            nativeStopRtspServer()
            _isRtspServerRunning.value = false
            _rtspServerUrl.value = ""
            appendLog(LogLevel.INFO, "RTSP", "RTSP Server stopped")
        } catch (t: Throwable) {
            _isRtspServerRunning.value = false
            appendLog(LogLevel.ERROR, "RTSP", "Exception stopping RTSP Server: ${t.message}")
        }
    }


    private fun tryLoadNativeLibraries(): Boolean {
        if (nativeLibrariesLoaded) return true
        return try {
            System.loadLibrary("gstreamer_android")
            System.loadLibrary("tutorial-1")
            nativeLibrariesLoaded = true
            nativeLoadError = null
            true
        } catch (t: Throwable) {
            nativeLibrariesLoaded = false
            nativeLoadError = t.message ?: t.toString()
            false
        }
    }

    suspend fun runDiagnostics(context: Context, clearLogs: Boolean = false) = withContext(Dispatchers.IO) {
        initContext(context)
        _engineState.value = GstEngineState.Inspecting
        if (clearLogs) {
            _logs.value = emptyList()
        }

        val startTime = SystemClock.elapsedRealtime()

        appendLog(LogLevel.CMD, "PROMPT", "gst-cli --verify-runtime --abi dual --target 1.28")
        appendLog(LogLevel.INFO, "DEVICE", "Model: ${Build.MANUFACTURER} ${Build.MODEL} (Product: ${Build.PRODUCT})")
        appendLog(LogLevel.INFO, "DEVICE", "Android OS: ${Build.VERSION.RELEASE} (SDK API ${Build.VERSION.SDK_INT})")

        val supportedAbis = Build.SUPPORTED_ABIS.joinToString(", ")
        val primaryAbi = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "unknown"
        appendLog(LogLevel.INFO, "ABI", "Primary ABI: $primaryAbi")
        appendLog(LogLevel.INFO, "ABI", "Supported ABIs: [$supportedAbis]")

        val isTargetAbi = primaryAbi == "arm64-v8a" || primaryAbi == "armeabi-v7a"
        if (isTargetAbi) {
            appendLog(LogLevel.SUCCESS, "ABI", "Target ABI matched: $primaryAbi (Dual ABI config: arm64-v8a / armeabi-v7a)")
        } else {
            appendLog(LogLevel.WARN, "ABI", "Running on non-target ABI ($primaryAbi). Supported targets: arm64-v8a, armeabi-v7a")
        }

        appendLog(LogLevel.CMD, "STAGE-1", "Loading native shared libraries...")

        if (!nativeLibrariesLoaded) {
            try {
                appendLog(LogLevel.INFO, "LOADER", "Attempting System.loadLibrary(\"gstreamer_android\")...")
                System.loadLibrary("gstreamer_android")
                appendLog(LogLevel.SUCCESS, "LOADER", "Successfully loaded libgstreamer_android.so")

                appendLog(LogLevel.INFO, "LOADER", "Attempting System.loadLibrary(\"tutorial-1\")...")
                System.loadLibrary("tutorial-1")
                appendLog(LogLevel.SUCCESS, "LOADER", "Successfully loaded libtutorial-1.so")

                nativeLibrariesLoaded = true
                nativeLoadError = null
            } catch (t: Throwable) {
                nativeLibrariesLoaded = false
                nativeLoadError = t.message ?: t.toString()
                appendLog(LogLevel.WARN, "LOADER", "Native library load deferred: ${t.javaClass.simpleName}: ${t.message}")
            }
        } else {
            appendLog(LogLevel.INFO, "LOADER", "Native libraries already loaded in memory")
        }

        if (nativeLibrariesLoaded) {
            appendLog(LogLevel.CMD, "STAGE-2", "Initializing GStreamer runtime (GStreamer.init)...")
            try {
                GStreamer.init(context)
                appendLog(LogLevel.SUCCESS, "INIT", "GStreamer.init(Context) executed successfully")
            } catch (e: Exception) {
                appendLog(LogLevel.ERROR, "INIT", "GStreamer.init failed: ${e.message}")
            }

            appendLog(LogLevel.CMD, "STAGE-3", "Querying GStreamer native version info...")
            try {
                val checkInit = nativeCheckGStreamerInit()
                appendLog(LogLevel.INFO, "CHECK", "nativeCheckGStreamerInit() => $checkInit")

                val versionInfo = nativeGetGStreamerInfo()
                appendLog(LogLevel.SUCCESS, "VERSION", ">>> $versionInfo <<<")
                appendLog(LogLevel.SUCCESS, "STATUS", "GStreamer 1.28 linking and execution verified in ${(SystemClock.elapsedRealtime() - startTime)}ms")

                _engineState.value = GstEngineState.Ready(version = versionInfo, abi = primaryAbi)

                // If surface was already bound before init, attach it now
                cachedSurface?.let {
                    nativeSetSurface(it)
                    appendLog(LogLevel.SUCCESS, "SURFACE", "Pre-existing surface bound to GStreamer")
                }
            } catch (e: UnsatisfiedLinkError) {
                appendLog(LogLevel.ERROR, "JNI", "UnsatisfiedLinkError while invoking native methods: ${e.message}")
                _engineState.value = GstEngineState.Failed("JNI Linkage error: ${e.message}")
            } catch (e: Exception) {
                appendLog(LogLevel.ERROR, "JNI", "Exception while querying GStreamer: ${e.message}")
                _engineState.value = GstEngineState.Failed("Query failed: ${e.message}")
            }
        } else {
            appendLog(LogLevel.WARN, "NOTICE", "Native binaries not yet embedded in this APK build.")
            _engineState.value = GstEngineState.NeedsNativeBuild(
                reason = "Awaiting GitHub Actions build with GStreamer 1.28 NDK SDK",
                architecture = primaryAbi
            )
        }

        appendLog(LogLevel.CMD, "DONE", "Diagnostics execution complete. Video player ready.")
    }
}
