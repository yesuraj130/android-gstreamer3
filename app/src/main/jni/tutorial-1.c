#include <string.h>
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <pthread.h>
#include <gst/gst.h>
#include <gst/video/videooverlay.h>

#define TAG "GStreamerTutorial"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Global references */
static ANativeWindow *current_native_window = NULL;
static GstElement *current_pipeline = NULL;
static GstBus *current_bus = NULL;
static pthread_mutex_t pipeline_mutex = PTHREAD_MUTEX_INITIALIZER;

/*
 * Bus sync handler to capture 'prepare-window-handle' messages
 * and automatically bind the ANativeWindow to the video sink.
 */
static GstBusSyncReply bus_sync_handler(GstBus *bus, GstMessage *msg, gpointer user_data) {
    if (gst_is_video_overlay_prepare_window_handle_message(msg)) {
        pthread_mutex_lock(&pipeline_mutex);
        if (current_native_window != NULL) {
            LOGI("Received prepare-window-handle, binding ANativeWindow %p to sink", current_native_window);
            gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(GST_MESSAGE_SRC(msg)), (guintptr)current_native_window);
        } else {
            LOGW("Received prepare-window-handle, but native_window is NULL");
        }
        pthread_mutex_unlock(&pipeline_mutex);
        gst_message_unref(msg);
        return GST_BUS_DROP;
    }
    return GST_BUS_PASS;
}

/*
 * Tutorial 1: Version query
 */
JNIEXPORT jstring JNICALL
Java_com_example_gstreamer_GStreamerBridge_nativeGetGStreamerInfo(JNIEnv *env, jobject thiz) {
    char *version_utf8 = gst_version_string();
    if (version_utf8 == NULL) {
        return (*env)->NewStringUTF(env, "GStreamer (unknown version)");
    }
    jstring result = (*env)->NewStringUTF(env, version_utf8);
    g_free(version_utf8);
    return result;
}

/*
 * Tutorial 1: Initialization check
 */
JNIEXPORT jboolean JNICALL
Java_com_example_gstreamer_GStreamerBridge_nativeCheckGStreamerInit(JNIEnv *env, jobject thiz) {
    GError *error = NULL;
    gboolean is_initialized = gst_init_check(NULL, NULL, &error);
    if (error != NULL) {
        LOGE("GStreamer init error: %s", error->message);
        g_error_free(error);
        return JNI_FALSE;
    }
    return is_initialized ? JNI_TRUE : JNI_FALSE;
}

/*
 * Tutorial 3: Attach Android Surface to GStreamer
 */
JNIEXPORT void JNICALL
Java_com_example_gstreamer_GStreamerBridge_nativeSetSurface(JNIEnv *env, jobject thiz, jobject surface) {
    pthread_mutex_lock(&pipeline_mutex);
    if (current_native_window != NULL) {
        ANativeWindow_release(current_native_window);
        current_native_window = NULL;
    }

    if (surface != NULL) {
        current_native_window = ANativeWindow_fromSurface(env, surface);
        LOGI("Native window created from surface: %p", current_native_window);

        if (current_pipeline != NULL) {
            GstElement *overlay = gst_bin_get_by_interface(GST_BIN(current_pipeline), GST_TYPE_VIDEO_OVERLAY);
            if (overlay != NULL) {
                LOGI("Directly setting window handle on active pipeline");
                gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(overlay), (guintptr)current_native_window);
                gst_video_overlay_expose(GST_VIDEO_OVERLAY(overlay));
                gst_object_unref(overlay);
            }
        }
    }
    pthread_mutex_unlock(&pipeline_mutex);
}

/*
 * Tutorial 3: Detach Android Surface
 */
JNIEXPORT void JNICALL
Java_com_example_gstreamer_GStreamerBridge_nativeClearSurface(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&pipeline_mutex);
    if (current_pipeline != NULL) {
        GstElement *overlay = gst_bin_get_by_interface(GST_BIN(current_pipeline), GST_TYPE_VIDEO_OVERLAY);
        if (overlay != NULL) {
            gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(overlay), (guintptr)NULL);
            gst_object_unref(overlay);
        }
    }

    if (current_native_window != NULL) {
        ANativeWindow_release(current_native_window);
        current_native_window = NULL;
        LOGI("Native window released");
    }
    pthread_mutex_unlock(&pipeline_mutex);
}

/*
 * Tutorial 2 & 3: Stop active pipeline
 */
JNIEXPORT void JNICALL
Java_com_example_gstreamer_GStreamerBridge_nativeStopPipeline(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&pipeline_mutex);
    if (current_pipeline != NULL) {
        LOGI("Stopping current pipeline...");
        gst_element_set_state(current_pipeline, GST_STATE_NULL);

        if (current_bus != NULL) {
            gst_bus_set_sync_handler(current_bus, NULL, NULL, NULL);
            gst_object_unref(current_bus);
            current_bus = NULL;
        }

        gst_object_unref(current_pipeline);
        current_pipeline = NULL;
        LOGI("Pipeline stopped and released successfully");
    }
    pthread_mutex_unlock(&pipeline_mutex);
}

/*
 * Tutorial 2 & 3: Launch custom pipeline from user text
 * Returns NULL on success, or an error description string on failure.
 */
JNIEXPORT jstring JNICALL
Java_com_example_gstreamer_GStreamerBridge_nativeStartPipeline(JNIEnv *env, jobject thiz, jstring pipeline_desc) {
    const char *desc = (*env)->GetStringUTFChars(env, pipeline_desc, NULL);
    if (desc == NULL || strlen(desc) == 0) {
        return (*env)->NewStringUTF(env, "Pipeline description cannot be empty");
    }

    pthread_mutex_lock(&pipeline_mutex);

    /* Stop previous pipeline if running */
    if (current_pipeline != NULL) {
        gst_element_set_state(current_pipeline, GST_STATE_NULL);
        if (current_bus != NULL) {
            gst_bus_set_sync_handler(current_bus, NULL, NULL, NULL);
            gst_object_unref(current_bus);
            current_bus = NULL;
        }
        gst_object_unref(current_pipeline);
        current_pipeline = NULL;
    }

    LOGI("Launching pipeline: %s", desc);
    GError *error = NULL;
    current_pipeline = gst_parse_launch(desc, &error);
    (*env)->ReleaseStringUTFChars(env, pipeline_desc, desc);

    if (error != NULL) {
        LOGE("gst_parse_launch error: %s", error->message);
        jstring err_str = (*env)->NewStringUTF(env, error->message);
        g_error_free(error);
        if (current_pipeline != NULL) {
            gst_object_unref(current_pipeline);
            current_pipeline = NULL;
        }
        pthread_mutex_unlock(&pipeline_mutex);
        return err_str;
    }

    /* Hook bus sync handler for automatic video overlay window binding */
    current_bus = gst_pipeline_get_bus(GST_PIPELINE(current_pipeline));
    if (current_bus != NULL) {
        gst_bus_set_sync_handler(current_bus, (GstBusSyncHandler)bus_sync_handler, NULL, NULL);
    }

    /* If native window is already active, eagerly attach it */
    if (current_native_window != NULL) {
        GstElement *overlay = gst_bin_get_by_interface(GST_BIN(current_pipeline), GST_TYPE_VIDEO_OVERLAY);
        if (overlay != NULL) {
            LOGI("Eagerly binding native window before PLAYING state");
            gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(overlay), (guintptr)current_native_window);
            gst_object_unref(overlay);
        }
    }

    /* Start playback */
    GstStateChangeReturn ret = gst_element_set_state(current_pipeline, GST_STATE_PLAYING);
    if (ret == GST_STATE_CHANGE_FAILURE) {
        LOGE("Failed to set pipeline state to GST_STATE_PLAYING");
        gst_element_set_state(current_pipeline, GST_STATE_NULL);
        if (current_bus != NULL) {
            gst_bus_set_sync_handler(current_bus, NULL, NULL, NULL);
            gst_object_unref(current_bus);
            current_bus = NULL;
        }
        gst_object_unref(current_pipeline);
        current_pipeline = NULL;
        pthread_mutex_unlock(&pipeline_mutex);
        return (*env)->NewStringUTF(env, "Failed to transition pipeline to PLAYING state");
    }

    LOGI("Pipeline started successfully (state change return: %d)", ret);
    pthread_mutex_unlock(&pipeline_mutex);
    return NULL; /* Success */
}

/*
 * Returns active pipeline state: 0=NULL, 1=READY, 2=PAUSED, 3=PLAYING
 */
JNIEXPORT jint JNICALL
Java_com_example_gstreamer_GStreamerBridge_nativeGetPipelineState(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&pipeline_mutex);
    if (current_pipeline == NULL) {
        pthread_mutex_unlock(&pipeline_mutex);
        return 0;
    }
    GstState current = GST_STATE_NULL;
    GstState pending = GST_STATE_NULL;
    gst_element_get_state(current_pipeline, &current, &pending, 0);
    pthread_mutex_unlock(&pipeline_mutex);

    switch (current) {
        case GST_STATE_READY: return 1;
        case GST_STATE_PAUSED: return 2;
        case GST_STATE_PLAYING: return 3;
        default: return 0;
    }
}
