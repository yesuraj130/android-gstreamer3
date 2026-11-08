#include <string.h>
#include <stdint.h>
#include <unistd.h>
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

/* Dedicated mutexes to prevent any lock recursion or self-deadlocks */
static pthread_mutex_t pipeline_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t window_mutex = PTHREAD_MUTEX_INITIALIZER;

/* Native window and pipeline references */
static ANativeWindow *current_native_window = NULL;
static GstElement *current_pipeline = NULL;
static GstBus *current_bus = NULL;

/* Dedicated GLib worker thread & event loop */
static pthread_t gst_worker_thread;
static GMainLoop *gst_main_loop = NULL;
static GMainContext *gst_main_context = NULL;
static gboolean worker_thread_running = FALSE;

/* JNI callback caching */
static JavaVM *java_vm = NULL;
static jclass gstreamer_bridge_class = NULL;
static jmethodID on_native_message_mid = NULL;
static jmethodID on_native_state_changed_mid = NULL;

/*
 * Helper to obtain JNIEnv on any thread
 */
static JNIEnv *get_jni_env(void) {
    if (java_vm == NULL) return NULL;
    JNIEnv *env = NULL;
    jint ret = (*java_vm)->GetEnv(java_vm, (void **)&env, JNI_VERSION_1_6);
    if (ret == JNI_EDETACHED) {
        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_6;
        args.name = "GstWorkerBusThread";
        args.group = NULL;
        if ((*java_vm)->AttachCurrentThread(java_vm, &env, &args) < 0) {
            LOGE("Failed to attach current thread to Java VM");
            return NULL;
        }
    }
    return env;
}

static void notify_java_message(const char *message, gboolean is_error) {
    if (gstreamer_bridge_class == NULL || on_native_message_mid == NULL) return;
    JNIEnv *env = get_jni_env();
    if (env == NULL) return;
    jstring jmsg = (*env)->NewStringUTF(env, message ? message : "");
    (*env)->CallStaticVoidMethod(env, gstreamer_bridge_class, on_native_message_mid, jmsg, (jboolean)is_error);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, jmsg);
}

static void notify_java_state(const char *state_name) {
    if (gstreamer_bridge_class == NULL || on_native_state_changed_mid == NULL) return;
    JNIEnv *env = get_jni_env();
    if (env == NULL) return;
    jstring jstate = (*env)->NewStringUTF(env, state_name ? state_name : "UNKNOWN");
    (*env)->CallStaticVoidMethod(env, gstreamer_bridge_class, on_native_state_changed_mid, jstate);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, jstate);
}

/*
 * Bus sync handler to capture 'prepare-window-handle' messages
 * and automatically bind the ANativeWindow to the video sink.
 * CRITICAL: MUST ONLY lock window_mutex (NEVER pipeline_mutex) to avoid self-deadlock.
 */
static GstBusSyncReply bus_sync_handler(GstBus *bus, GstMessage *msg, gpointer user_data) {
    if (gst_is_video_overlay_prepare_window_handle_message(msg)) {
        GstVideoOverlay *overlay = GST_VIDEO_OVERLAY(GST_MESSAGE_SRC(msg));
        pthread_mutex_lock(&window_mutex);
        if (current_native_window != NULL) {
            LOGI("bus_sync_handler: binding ANativeWindow %p to overlay sink", current_native_window);
            gst_video_overlay_set_window_handle(overlay, (guintptr)current_native_window);
            gst_video_overlay_expose(overlay);
        } else {
            LOGW("bus_sync_handler: prepare-window-handle received but native_window is NULL");
        }
        pthread_mutex_unlock(&window_mutex);
        gst_message_unref(msg);
        return GST_BUS_DROP;
    }
    return GST_BUS_PASS;
}

/*
 * Asynchronous bus message callbacks
 */
static void error_cb(GstBus *bus, GstMessage *msg, gpointer user_data) {
    GError *err = NULL;
    gchar *debug_info = NULL;
    gst_message_parse_error(msg, &err, &debug_info);
    gchar *msg_str = g_strdup_printf("Element %s error: %s",
        GST_OBJECT_NAME(msg->src), err ? err->message : "unknown");
    LOGE("%s (debug: %s)", msg_str, debug_info ? debug_info : "none");
    notify_java_message(msg_str, TRUE);
    g_free(msg_str);
    if (err) g_error_free(err);
    if (debug_info) g_free(debug_info);
}

static void state_changed_cb(GstBus *bus, GstMessage *msg, gpointer user_data) {
    pthread_mutex_lock(&pipeline_mutex);
    if (current_pipeline != NULL && GST_MESSAGE_SRC(msg) == GST_OBJECT(current_pipeline)) {
        GstState old_s, new_s, pend_s;
        gst_message_parse_state_changed(msg, &old_s, &new_s, &pend_s);
        const gchar *new_name = gst_element_state_get_name(new_s);
        LOGI("Pipeline state changed from %s to %s", gst_element_state_get_name(old_s), new_name);
        notify_java_state(new_name);
    }
    pthread_mutex_unlock(&pipeline_mutex);
}

static void eos_cb(GstBus *bus, GstMessage *msg, gpointer user_data) {
    LOGI("Pipeline reached end-of-stream (EOS)");
    notify_java_message("End of stream reached (EOS)", FALSE);
    notify_java_state("NULL");
}

/*
 * Worker thread routine running GLib GMainLoop
 */
static void *gst_worker_entry(void *arg) {
    gst_main_context = g_main_context_new();
    g_main_context_push_thread_default(gst_main_context);
    gst_main_loop = g_main_loop_new(gst_main_context, FALSE);
    worker_thread_running = TRUE;
    LOGI("GStreamer GLib main loop worker thread running");

    g_main_loop_run(gst_main_loop);

    LOGI("GStreamer GLib main loop worker thread exiting");
    g_main_loop_unref(gst_main_loop);
    gst_main_loop = NULL;
    g_main_context_pop_thread_default(gst_main_context);
    g_main_context_unref(gst_main_context);
    gst_main_context = NULL;
    worker_thread_running = FALSE;
    return NULL;
}

static void ensure_worker_thread(void) {
    if (!worker_thread_running) {
        pthread_create(&gst_worker_thread, NULL, gst_worker_entry, NULL);
        int attempts = 0;
        while (!worker_thread_running && attempts++ < 50) {
            usleep(10000); /* wait up to 500ms */
        }
    }
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
    ensure_worker_thread();
    return is_initialized ? JNI_TRUE : JNI_FALSE;
}

/*
 * Tutorial 3: Attach Android Surface to GStreamer
 */
JNIEXPORT void JNICALL
Java_com_example_gstreamer_GStreamerBridge_nativeSetSurface(JNIEnv *env, jobject thiz, jobject surface) {
    ANativeWindow *new_win = NULL;
    if (surface != NULL) {
        new_win = ANativeWindow_fromSurface(env, surface);
        LOGI("ANativeWindow_fromSurface returned: %p", new_win);
    }

    pthread_mutex_lock(&window_mutex);
    if (current_native_window != NULL && current_native_window != new_win) {
        ANativeWindow_release(current_native_window);
    }
    current_native_window = new_win;
    ANativeWindow *active_win = current_native_window;
    pthread_mutex_unlock(&window_mutex);

    pthread_mutex_lock(&pipeline_mutex);
    if (current_pipeline != NULL && active_win != NULL) {
        GstElement *overlay = gst_bin_get_by_interface(GST_BIN(current_pipeline), GST_TYPE_VIDEO_OVERLAY);
        if (overlay != NULL) {
            LOGI("Binding active window %p to overlay", active_win);
            gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(overlay), (guintptr)active_win);
            gst_video_overlay_expose(GST_VIDEO_OVERLAY(overlay));
            gst_object_unref(overlay);
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
    pthread_mutex_unlock(&pipeline_mutex);

    pthread_mutex_lock(&window_mutex);
    if (current_native_window != NULL) {
        ANativeWindow_release(current_native_window);
        current_native_window = NULL;
        LOGI("Native window cleared and released");
    }
    pthread_mutex_unlock(&window_mutex);
}

/*
 * Tutorial 2 & 3: Stop active pipeline
 */
JNIEXPORT void JNICALL
Java_com_example_gstreamer_GStreamerBridge_nativeStopPipeline(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&pipeline_mutex);
    if (current_pipeline != NULL) {
        LOGI("Stopping pipeline to GST_STATE_NULL...");
        gst_element_set_state(current_pipeline, GST_STATE_NULL);

        if (current_bus != NULL) {
            gst_bus_set_sync_handler(current_bus, NULL, NULL, NULL);
            gst_object_unref(current_bus);
            current_bus = NULL;
        }
        gst_object_unref(current_pipeline);
        current_pipeline = NULL;
        LOGI("Pipeline stopped successfully");
    }
    pthread_mutex_unlock(&pipeline_mutex);
    notify_java_state("NULL");
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

    ensure_worker_thread();

    pthread_mutex_lock(&pipeline_mutex);

    /* Stop previous pipeline if running */
    if (current_pipeline != NULL) {
        LOGI("Cleaning up previous pipeline...");
        gst_element_set_state(current_pipeline, GST_STATE_NULL);
        if (current_bus != NULL) {
            gst_bus_set_sync_handler(current_bus, NULL, NULL, NULL);
            gst_object_unref(current_bus);
            current_bus = NULL;
        }
        gst_object_unref(current_pipeline);
        current_pipeline = NULL;
    }

    LOGI("Parsing pipeline: %s", desc);
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

    /* Set bus sync handler for prepare-window-handle */
    current_bus = gst_pipeline_get_bus(GST_PIPELINE(current_pipeline));
    if (current_bus != NULL) {
        gst_bus_set_sync_handler(current_bus, (GstBusSyncHandler)bus_sync_handler, NULL, NULL);

        /* Attach bus watch to GLib main loop context */
        if (gst_main_context != NULL) {
            GSource *bus_source = gst_bus_create_watch(current_bus);
            g_source_set_callback(bus_source, (GSourceFunc)gst_bus_async_signal_func, NULL, NULL);
            g_source_attach(bus_source, gst_main_context);
            g_source_unref(bus_source);

            g_signal_connect(G_OBJECT(current_bus), "message::error", (GCallback)error_cb, NULL);
            g_signal_connect(G_OBJECT(current_bus), "message::state-changed", (GCallback)state_changed_cb, NULL);
            g_signal_connect(G_OBJECT(current_bus), "message::eos", (GCallback)eos_cb, NULL);
        }
    }

    /* Transition pipeline to READY to instantiate elements */
    GstStateChangeReturn ready_ret = gst_element_set_state(current_pipeline, GST_STATE_READY);
    LOGI("gst_element_set_state READY returned: %d", ready_ret);

    /* Eagerly set window handle if window is active */
    pthread_mutex_lock(&window_mutex);
    if (current_native_window != NULL) {
        GstElement *overlay = gst_bin_get_by_interface(GST_BIN(current_pipeline), GST_TYPE_VIDEO_OVERLAY);
        if (overlay != NULL) {
            LOGI("Eagerly binding window %p at READY state", current_native_window);
            gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(overlay), (guintptr)current_native_window);
            gst_object_unref(overlay);
        }
    }
    pthread_mutex_unlock(&window_mutex);

    /* Start playback */
    GstStateChangeReturn play_ret = gst_element_set_state(current_pipeline, GST_STATE_PLAYING);
    LOGI("gst_element_set_state PLAYING returned: %d", play_ret);

    if (play_ret == GST_STATE_CHANGE_FAILURE) {
        LOGE("Failed to transition pipeline to PLAYING state");
        gst_element_set_state(current_pipeline, GST_STATE_NULL);
        if (current_bus != NULL) {
            gst_bus_set_sync_handler(current_bus, NULL, NULL, NULL);
            gst_object_unref(current_bus);
            current_bus = NULL;
        }
        gst_object_unref(current_pipeline);
        current_pipeline = NULL;
        pthread_mutex_unlock(&pipeline_mutex);
        return (*env)->NewStringUTF(env, "Failed to transition pipeline to PLAYING state (GST_STATE_CHANGE_FAILURE)");
    }

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

/*
 * JNI Lifecycle hooks
 */
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    java_vm = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_OnLoad: GetEnv failed");
        return JNI_ERR;
    }

    jclass local_class = (*env)->FindClass(env, "com/example/gstreamer/GStreamerBridge");
    if (local_class != NULL) {
        gstreamer_bridge_class = (jclass)(*env)->NewGlobalRef(env, local_class);
        on_native_message_mid = (*env)->GetStaticMethodID(env, gstreamer_bridge_class, "onNativeMessage", "(Ljava/lang/String;Z)V");
        on_native_state_changed_mid = (*env)->GetStaticMethodID(env, gstreamer_bridge_class, "onNativeStateChanged", "(Ljava/lang/String;)V");
        LOGI("JNI_OnLoad: Cached GStreamerBridge class & method IDs");
    } else {
        LOGW("JNI_OnLoad: Could not find com.example.gstreamer.GStreamerBridge");
    }

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK && gstreamer_bridge_class != NULL) {
        (*env)->DeleteGlobalRef(env, gstreamer_bridge_class);
        gstreamer_bridge_class = NULL;
    }
    if (worker_thread_running && gst_main_loop != NULL) {
        g_main_loop_quit(gst_main_loop);
        pthread_join(gst_worker_thread, NULL);
    }
}
