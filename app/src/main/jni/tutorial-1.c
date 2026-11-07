#include <string.h>
#include <jni.h>
#include <android/log.h>
#include <gst/gst.h>

#define TAG "GStreamerTutorial1"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/*
 * Java to C native binding for GStreamer Tutorial 1
 * Package: com.example.gstreamer
 * Class: GStreamerBridge
 */

JNIEXPORT jstring JNICALL
Java_com_example_gstreamer_GStreamerBridge_nativeGetGStreamerInfo(JNIEnv *env, jobject thiz) {
    char *version_utf8 = gst_version_string();
    if (version_utf8 == NULL) {
        LOGE("gst_version_string() returned NULL");
        return (*env)->NewStringUTF(env, "GStreamer (unknown version)");
    }
    LOGI("GStreamer version retrieved: %s", version_utf8);
    jstring result = (*env)->NewStringUTF(env, version_utf8);
    g_free(version_utf8);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_example_gstreamer_GStreamerBridge_nativeCheckGStreamerInit(JNIEnv *env, jobject thiz) {
    GError *error = NULL;
    gboolean is_initialized = gst_init_check(NULL, NULL, &error);
    if (error != NULL) {
        LOGE("GStreamer initialization check failed: %s", error->message);
        g_error_free(error);
        return JNI_FALSE;
    }
    LOGI("GStreamer initialization check successful (%d)", is_initialized);
    return is_initialized ? JNI_TRUE : JNI_FALSE;
}
