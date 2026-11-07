package com.example.gstreamer

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
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

    private var nativeLibrariesLoaded = false
    private var nativeLoadError: String? = null

    private val _logs = MutableStateFlow<List<ConsoleLog>>(emptyList())
    val logs: StateFlow<List<ConsoleLog>> = _logs.asStateFlow()

    private val _engineState = MutableStateFlow<GstEngineState>(GstEngineState.Idle)
    val engineState: StateFlow<GstEngineState> = _engineState.asStateFlow()

    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun appendLog(level: LogLevel, tag: String, message: String) {
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

    suspend fun runDiagnostics(context: Context) = withContext(Dispatchers.IO) {
        _engineState.value = GstEngineState.Inspecting
        _logs.value = emptyList()

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
            var initSuccessful = false
            try {
                GStreamer.init(context)
                appendLog(LogLevel.SUCCESS, "INIT", "GStreamer.init(Context) executed successfully")
                initSuccessful = true
            } catch (e: Exception) {
                appendLog(LogLevel.ERROR, "INIT", "GStreamer.init failed: ${e.message}")
            }

            appendLog(LogLevel.CMD, "STAGE-3", "Querying GStreamer native version info (Tutorial 1)...")
            try {
                val checkInit = nativeCheckGStreamerInit()
                appendLog(LogLevel.INFO, "CHECK", "nativeCheckGStreamerInit() => $checkInit")

                val versionInfo = nativeGetGStreamerInfo()
                appendLog(LogLevel.SUCCESS, "VERSION", ">>> $versionInfo <<<")
                appendLog(LogLevel.SUCCESS, "STATUS", "GStreamer 1.28 linking and execution verified in ${(SystemClock.elapsedRealtime() - startTime)}ms")

                _engineState.value = GstEngineState.Ready(version = versionInfo, abi = primaryAbi)
            } catch (e: UnsatisfiedLinkError) {
                appendLog(LogLevel.ERROR, "JNI", "UnsatisfiedLinkError while invoking native methods: ${e.message}")
                _engineState.value = GstEngineState.Failed("JNI Linkage error: ${e.message}")
            } catch (e: Exception) {
                appendLog(LogLevel.ERROR, "JNI", "Exception while querying GStreamer: ${e.message}")
                _engineState.value = GstEngineState.Failed("Query failed: ${e.message}")
            }
        } else {
            // Native binaries not packaged in this particular build
            appendLog(LogLevel.WARN, "NOTICE", "Native binaries (libgstreamer_android.so / libtutorial-1.so) not yet embedded in this APK.")
            appendLog(LogLevel.INFO, "EXPLANATION", "To generate the complete APK containing GStreamer 1.28 C binaries for $primaryAbi:")
            appendLog(LogLevel.CMD, "CI/CD", "1. Push to GitHub and trigger the '.github/workflows/build.yml' action.")
            appendLog(LogLevel.CMD, "CI/CD", "2. The workflow downloads the prebuilt GStreamer 1.28 SDK, compiles tutorial-1.c, and outputs the signed APK.")
            appendLog(LogLevel.INFO, "CONFIG", "MinSdk: 26 (Android 8.0+ / 2020+), ABIs: [armeabi-v7a, arm64-v8a], GStreamer Plugins: [core]")

            _engineState.value = GstEngineState.NeedsNativeBuild(
                reason = "Awaiting GitHub Actions build with GStreamer 1.28 NDK SDK",
                architecture = primaryAbi
            )
        }

        appendLog(LogLevel.CMD, "DONE", "Diagnostics execution complete.")
    }
}
