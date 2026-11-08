package com.example.ui

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.SurfaceHolder

data class SupportedResolution(
    val width: Int,
    val height: Int,
    val label: String,
    val isFullSensorMatch: Boolean,
    val is169: Boolean
)

data class AvailableCameraDevice(
    val deviceIndex: Int,
    val cameraId: String,
    val facing: String,
    val lensType: String,
    val focalLengthMm: Float?,
    val label: String
)

data class CameraHardwareProfile(
    val cameraId: String,
    val facing: String,
    val activeArray: Rect?,
    val sensorWidth: Int,
    val sensorHeight: Int,
    val sensorMegaPixels: Float,
    val sensorAspectRatio: Float,
    val fullSensorRatioLabel: String,
    val supportedPreviewSizes: List<SupportedResolution>,
    val optimalFullSensorSize: SupportedResolution?
)

object CameraHardwareInspector {
    private const val TAG = "CameraHardwareInspector"

    fun inspectCamera(context: Context, deviceIndex: Int): CameraHardwareProfile? {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isEmpty()) return null

            val targetIdStr = if (deviceIndex >= 0) "$deviceIndex" else "0"

            val characteristics = try {
                cameraManager.getCameraCharacteristics(targetIdStr)
            } catch (e: Exception) {
                // If the OEM hides this physical ID from Camera2 list, fall back to "0" characteristics
                val fallbackId = if (deviceIndex == 1 && cameraIds.size > 1) cameraIds[1] else cameraIds.firstOrNull() ?: "0"
                cameraManager.getCameraCharacteristics(fallbackId)
            }

            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val facingLabel = when {
                lensFacing == CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                deviceIndex == 2 -> "Back Ultra-Wide (0.6x)"
                deviceIndex == 3 -> "Back Telephoto / Macro"
                else -> "Back"
            }

            val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val sensorWidth = activeArray?.width() ?: 1920
            val sensorHeight = activeArray?.height() ?: 1440
            val sensorMp = (sensorWidth.toLong() * sensorHeight.toLong()) / 1_000_000f
            val sensorRatio = if (sensorHeight > 0) sensorWidth.toFloat() / sensorHeight else 4f / 3f

            val ratioLabel = if (Math.abs(sensorRatio - 4f / 3f) < 0.1f) {
                "4:3 Physical Sensor"
            } else {
                "${sensorWidth}:${sensorHeight}"
            }

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = map?.getOutputSizes(SurfaceHolder::class.java)
                ?: map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                ?: emptyArray()

            val resList = mutableListOf<SupportedResolution>()
            for (sz in outputSizes) {
                if (sz.width < 640 || sz.height < 480) continue
                if (sz.width == sz.height) continue

                val aspect = sz.width.toFloat() / sz.height
                val isFullMatch = Math.abs(aspect - sensorRatio) < 0.08f || Math.abs(aspect - 4f / 3f) < 0.08f
                val is169 = Math.abs(aspect - 16f / 9f) < 0.08f

                val tag = when {
                    isFullMatch -> "Full Sensor (No Crop)"
                    is169 -> "16:9 Widescreen"
                    else -> "${sz.width}:${sz.height}"
                }

                resList.add(
                    SupportedResolution(
                        width = sz.width,
                        height = sz.height,
                        label = "${sz.width}x${sz.height} ($tag)",
                        isFullSensorMatch = isFullMatch,
                        is169 = is169
                    )
                )
            }

            // Ensure standard uncropped resolutions are always present
            if (resList.none { it.width == 1440 && it.height == 1080 }) {
                resList.add(SupportedResolution(1440, 1080, "1440x1080 (Full Sensor No Crop)", true, false))
            }
            if (resList.none { it.width == 1920 && it.height == 1080 }) {
                resList.add(SupportedResolution(1920, 1080, "1920x1080 (16:9 Widescreen)", false, true))
            }

            val sortedRes = resList.distinctBy { "${it.width}x${it.height}" }
                .sortedByDescending { it.width * it.height }

            val optimal = sortedRes.firstOrNull { it.isFullSensorMatch && it.width in 1280..1920 }
                ?: sortedRes.firstOrNull { it.isFullSensorMatch }
                ?: sortedRes.firstOrNull()

            CameraHardwareProfile(
                cameraId = targetIdStr,
                facing = facingLabel,
                activeArray = activeArray,
                sensorWidth = sensorWidth,
                sensorHeight = sensorHeight,
                sensorMegaPixels = sensorMp,
                sensorAspectRatio = sensorRatio,
                fullSensorRatioLabel = ratioLabel,
                supportedPreviewSizes = sortedRes,
                optimalFullSensorSize = optimal
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting camera hardware", e)
            null
        }
    }

    fun getAvailableCameraDevices(context: Context): List<AvailableCameraDevice> {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val primaryIds = cameraManager.cameraIdList.toList()
            val allIds = mutableListOf<String>()
            allIds.addAll(primaryIds)

            // 1. Inspect physical sub-cameras backing logical cameras (Android 9+ / API 28+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                for (id in primaryIds) {
                    try {
                        val chars = cameraManager.getCameraCharacteristics(id)
                        val physicalIds = chars.physicalCameraIds
                        for (pId in physicalIds) {
                            if (!allIds.contains(pId)) {
                                allIds.add(pId)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not read physicalCameraIds for $id", e)
                    }
                }
            }

            // 2. Also probe hardware indices 2 and 3 (ultra-wide & telephoto/macro)
            for (candidate in listOf("2", "3")) {
                if (!allIds.contains(candidate)) {
                    allIds.add(candidate)
                }
            }

            val list = mutableListOf<AvailableCameraDevice>()
            for (id in allIds) {
                val devIdx = id.toIntOrNull() ?: list.size
                var facingStr = if (id == "1") "Front" else "Back"
                var focal: Float? = null
                var lensType = when (id) {
                    "0" -> "Back Main (1x)"
                    "1" -> "Front Selfie"
                    "2" -> "Ultra-Wide (0.6x)"
                    "3" -> "Telephoto / Macro"
                    else -> "Back Lens $id"
                }

                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    facingStr = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "Front" else "Back"
                    val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    focal = focals?.firstOrNull()

                    lensType = when {
                        facing == CameraCharacteristics.LENS_FACING_FRONT -> "Front Selfie"
                        focal != null && focal < 3.2f -> "Ultra-Wide (0.6x)"
                        focal != null && focal >= 6.5f -> "Telephoto (${String.format(java.util.Locale.US, "%.1fx", focal / 4.2f)})"
                        id == "2" -> "Ultra-Wide (0.6x)"
                        id == "3" -> "Telephoto / Macro"
                        id == "0" -> "Back Main (1x)"
                        else -> "Back Lens $id"
                    }
                } catch (e: Exception) {
                    if (id == "2") {
                        lensType = "Ultra-Wide (0.6x)"
                        facingStr = "Back"
                    } else if (id == "3") {
                        lensType = "Telephoto / Macro"
                        facingStr = "Back"
                    }
                }

                val label = "$lensType (dev=$id)"
                list.add(
                    AvailableCameraDevice(
                        deviceIndex = devIdx,
                        cameraId = id,
                        facing = facingStr,
                        lensType = lensType,
                        focalLengthMm = focal,
                        label = label
                    )
                )
            }

            list.distinctBy { it.deviceIndex }.sortedBy { it.deviceIndex }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating camera devices", e)
            listOf(
                AvailableCameraDevice(0, "0", "Back", "Back Main (1x)", null, "Back Main (1x) (dev=0)"),
                AvailableCameraDevice(1, "1", "Front", "Front Selfie", null, "Front Selfie (dev=1)"),
                AvailableCameraDevice(2, "2", "Back", "Ultra-Wide (0.6x)", null, "Ultra-Wide (0.6x) (dev=2)")
            )
        }
    }
}
