package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.example.gstreamer.GStreamerBridge
import com.example.gstreamer.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class Camera2AppSrcManager(private val context: Context) {
    private val TAG = "Camera2AppSrcManager"
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun start(cameraId: String, width: Int = 1280, height: Int = 720, onPipelineReady: (String) -> Unit) {
        stop()
        isRunning = true

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val pipelineDesc = "appsrc name=mysource format=time is-live=true do-timestamp=true block=true ! video/x-raw,format=I420,width=$width,height=$height,framerate=30/1 ! videoconvert ! x264enc tune=zerolatency speed-preset=ultrafast bitrate=2048 key-int-max=15 ! rtph264pay name=pay0 pt=96 config-interval=1 mtu=1400"
            GStreamerBridge.appendLog(LogLevel.CMD, "APPSRC", "Starting Camera2 appsrc pipeline: $pipelineDesc")
            
            GlobalScope.launch(Dispatchers.IO) {
                GStreamerBridge.startPipeline(pipelineDesc)
            }

            imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (isRunning) {
                        val i420Bytes = convertYuv420888ToI420(image)
                        if (i420Bytes != null) {
                            GStreamerBridge.pushAppSrcBuffer(i420Bytes)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing camera frame", e)
                } finally {
                    image.close()
                }
            }, handler)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera, imageReader?.surface)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    isRunning = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    isRunning = false
                    GStreamerBridge.appendLog(LogLevel.ERROR, "APPSRC", "Camera2 error: $error")
                }
            }, handler)

            onPipelineReady(pipelineDesc)
        } catch (e: Exception) {
            GStreamerBridge.appendLog(LogLevel.ERROR, "APPSRC", "Failed to start Camera2 appsrc: ${e.message}")
            stop()
        }
    }

    private fun createCaptureSession(camera: CameraDevice, surface: Surface?) {
        if (surface == null) return
        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }

            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(requestBuilder.build(), null, handler)
                            GStreamerBridge.appendLog(LogLevel.SUCCESS, "APPSRC", "Camera2 capture session active on lens")
                        } catch (e: Exception) {
                            GStreamerBridge.appendLog(LogLevel.ERROR, "APPSRC", "Failed to start repeating request: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        GStreamerBridge.appendLog(LogLevel.ERROR, "APPSRC", "Camera2 capture session configuration failed")
                    }
                },
                handler
            )
        } catch (e: Exception) {
            GStreamerBridge.appendLog(LogLevel.ERROR, "APPSRC", "Error creating capture session: ${e.message}")
        }
    }

    private fun convertYuv420888ToI420(image: Image): ByteArray? {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = ySize / 2
        val i420 = ByteArray(ySize + uvSize)

        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        var rowStride = yPlane.rowStride
        var pixelStride = yPlane.pixelStride
        if (pixelStride == 1) {
            yBuffer.get(i420, 0, ySize)
        } else {
            var pos = 0
            for (r in 0 until height) {
                yBuffer.position(r * rowStride)
                for (c in 0 until width) {
                    i420[pos++] = yBuffer.get()
                }
            }
        }

        val uPos = ySize
        val vPos = ySize + uvSize / 2

        val chromaWidth = width / 2
        val chromaHeight = height / 2

        rowStride = uPlane.rowStride
        pixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        var uOffset = uPos
        var vOffset = vPos

        for (r in 0 until chromaHeight) {
            uBuffer.position(r * rowStride)
            vBuffer.position(r * vRowStride)
            for (c in 0 until chromaWidth) {
                i420[uOffset++] = uBuffer.get()
                i420[vOffset++] = vBuffer.get()
                if (pixelStride > 1) {
                    for (i in 0 until pixelStride - 1) uBuffer.get()
                }
                if (vPixelStride > 1) {
                    for (i in 0 until vPixelStride - 1) vBuffer.get()
                }
            }
        }

        return i420
    }

    fun stop() {
        isRunning = false
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            GlobalScope.launch(Dispatchers.IO) {
                GStreamerBridge.stopPipeline()
            }
            GStreamerBridge.appendLog(LogLevel.INFO, "APPSRC", "Camera2 appsrc session stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Camera2 appsrc", e)
        }
    }
}
