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
#include <gst/rtsp-server/rtsp-server.h>

#define TAG "GStreamerTutorial"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Dedicated mutexes to prevent any lock recursion or self-deadlocks */
static pthread_mutex_t pipeline_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t window_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t rtsp_mutex = PTHREAD_MUTEX_INITIALIZER;

/* Native RTSP Server instance & state */
static GstRTSPServer *rtsp_server = NULL;
static guint rtsp_server_source_id = 0;
static gboolean rtsp_server_running = FALSE;

/* Native window and pipeline references */
static ANativeWindow *current_native_window = NULL;
static GstElement *current_pipeline = NULL;
static GstBus *current_bus = NULL;

/* Debug level state (default: GST_LEVEL_DEBUG = 5 for verbose logging) */
static GstDebugLevel current_debug_level = GST_LEVEL_DEBUG;

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
 * GStreamer native debug log function -> routes GStreamer internal diagnostics directly to UI terminal
 */
static void gst_native_log_handler(GstDebugCategory *category, GstDebugLevel level,
    const gchar *file, const gchar *function, gint line,
    GObject *object, GstDebugMessage *message, gpointer user_data) {
    if (level <= current_debug_level) {
        const gchar *cat_name = category ? gst_debug_category_get_name(category) : "gst";
        const gchar *msg_text = message ? gst_debug_message_get(message) : NULL;
        if (msg_text != NULL && strlen(msg_text) > 0) {
            /* Filter out noisy GLib context spam unless TRACE level */
            if (current_debug_level < GST_LEVEL_LOG) {
                if (strstr(msg_text, "g_main_context") != NULL || strstr(msg_text, "g_source") != NULL) {
                    return;
                }
            }
            gchar *full_msg = g_strdup_printf("[%s] %s", cat_name ? cat_name : "gst", msg_text);
            LOGI("[GST-LOG] %s", full_msg);
            notify_java_message(full_msg, (level <= GST_LEVEL_WARNING));
            g_free(full_msg);
        }
    }
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
    gchar *msg_str = g_strdup_printf("ERROR [%s]: %s (%s)",
        GST_OBJECT_NAME(msg->src), err ? err->message : "unknown", debug_info ? debug_info : "no debug");
    LOGE("%s", msg_str);
    notify_java_message(msg_str, TRUE);
    g_free(msg_str);
    if (err) g_error_free(err);
    if (debug_info) g_free(debug_info);
}

static void warning_cb(GstBus *bus, GstMessage *msg, gpointer user_data) {
    GError *err = NULL;
    gchar *debug_info = NULL;
    gst_message_parse_warning(msg, &err, &debug_info);
    gchar *msg_str = g_strdup_printf("WARNING [%s]: %s (%s)",
        GST_OBJECT_NAME(msg->src), err ? err->message : "unknown", debug_info ? debug_info : "no debug");
    LOGW("%s", msg_str);
    notify_java_message(msg_str, FALSE);
    g_free(msg_str);
    if (err) g_error_free(err);
    if (debug_info) g_free(debug_info);
}

static void info_cb(GstBus *bus, GstMessage *msg, gpointer user_data) {
    GError *err = NULL;
    gchar *debug_info = NULL;
    gst_message_parse_info(msg, &err, &debug_info);
    gchar *msg_str = g_strdup_printf("INFO [%s]: %s (%s)",
        GST_OBJECT_NAME(msg->src), err ? err->message : "unknown", debug_info ? debug_info : "no debug");
    LOGI("%s", msg_str);
    notify_java_message(msg_str, FALSE);
    g_free(msg_str);
    if (err) g_error_free(err);
    if (debug_info) g_free(debug_info);
}

static void buffering_cb(GstBus *bus, GstMessage *msg, gpointer user_data) {
    gint percent = 0;
    gst_message_parse_buffering(msg, &percent);
    gchar *msg_str = g_strdup_printf("Buffering: %d%%", percent);
    LOGI("%s", msg_str);
    notify_java_message(msg_str, FALSE);
    g_free(msg_str);
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
 * Set dynamic GStreamer debug log level (2=WARN, 3=INFO, 4=INFO_VERBOSE, 5=DEBUG, 6=LOG)
 */
JNIEXPORT void JNICALL
Java_com_example_gstreamer_GStreamerBridge_nativeSetDebugLevel(JNIEnv *env, jobject thiz, jint level) {
    current_debug_level = (GstDebugLevel)level;
    gst_debug_set_default_threshold((GstDebugLevel)level);
    LOGI("Native GStreamer log threshold updated to level %d", level);
    gchar *msg = g_strdup_printf("GStreamer native debug verbosity set to level %d", level);
    notify_java_message(msg, FALSE);
    g_free(msg);
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
    
    /* Enable GStreamer native debug log routing to UI terminal */
    gst_debug_remove_log_function(NULL);
    gst_debug_add_log_function((GstLogFunction)gst_native_log_handler, NULL, NULL);
    gst_debug_set_default_threshold(current_debug_level);

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
            g_signal_connect(G_OBJECT(current_bus), "message::warning", (GCallback)warning_cb, NULL);
            g_signal_connect(G_OBJECT(current_bus), "message::info", (GCallback)info_cb, NULL);
            g_signal_connect(G_OBJECT(current_bus), "message::buffering", (GCallback)buffering_cb, NULL);
            g_signal_connect(G_OBJECT(current_bus), "message::state-changed", (GCallback)state_changed_cb, NULL);
            g_signal_connect(G_OBJECT(current_bus), "message::eos", (GCallback)eos_cb, NULL);
        }
    }

    /* Set pipeline directly to PLAYING state (matches official GStreamer Tutorial 3) */
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

static char current_active_encoder_name[128] = "Auto";

static char *get_h264_encoder_pipeline_fragment(const char *pref, char **out_name, gboolean *out_is_hw) {
    gboolean prefer_sw = (pref != NULL && strcmp(pref, "software") == 0);
    gboolean prefer_hw = (pref != NULL && strcmp(pref, "hardware") == 0);

    char *hw_encoder = NULL;

    if (!prefer_sw) {
        GList *list = gst_element_factory_list_get_elements(
            GST_ELEMENT_FACTORY_TYPE_ENCODER | GST_ELEMENT_FACTORY_TYPE_MEDIA_VIDEO,
            GST_RANK_MARGINAL);
        GstCaps *h264_caps = gst_caps_from_string("video/x-h264");

        for (GList *l = list; l != NULL; l = l->next) {
            GstElementFactory *factory = GST_ELEMENT_FACTORY(l->data);
            const gchar *name = gst_plugin_feature_get_name(GST_PLUGIN_FEATURE(factory));
            if (h264_caps != NULL && gst_element_factory_can_src_any_caps(factory, h264_caps)) {
                if (g_str_has_prefix(name, "amcvidenc") || g_str_has_prefix(name, "amcvideoenc")) {
                    if (hw_encoder == NULL) {
                        hw_encoder = g_strdup(name);
                    }
                }
            }
        }
        if (h264_caps) gst_caps_unref(h264_caps);
        gst_plugin_feature_list_free(list);
    }

    if (hw_encoder != NULL) {
        LOGI("Selected Hardware MediaCodec H264 Encoder: %s", hw_encoder);
        if (out_name) *out_name = g_strdup(hw_encoder);
        if (out_is_hw) *out_is_hw = TRUE;
        /* Hardware low-latency encoder pipeline fragment */
        char *fragment = g_strdup_printf("videoconvert ! video/x-raw,format=NV12 ! %s i-frame-interval=1 bitrate=2500000", hw_encoder);
        g_free(hw_encoder);
        return fragment;
    }

    if (prefer_hw) {
        LOGW("Hardware encoder requested but not found in GStreamer registry, falling back to x264enc");
    }

    /* Fall back to software x264enc (ultrafast, zerolatency, intra-refresh) */
    if (gst_element_factory_find("x264enc") != NULL) {
        LOGI("Selected Software x264enc (ultrafast zerolatency intra-refresh)");
        if (out_name) *out_name = g_strdup("x264enc");
        if (out_is_hw) *out_is_hw = FALSE;
        return g_strdup("videoconvert ! video/x-raw,format=I420 ! x264enc tune=zerolatency speed-preset=ultrafast bitrate=2048 key-int-max=15 bframes=0 rc-lookahead=0 sliced-threads=true");
    }

    /* Fall back to openh264enc */
    if (gst_element_factory_find("openh264enc") != NULL) {
        LOGI("Selected openh264enc");
        if (out_name) *out_name = g_strdup("openh264enc");
        if (out_is_hw) *out_is_hw = FALSE;
        return g_strdup("videoconvert ! openh264enc");
    }

    if (out_name) *out_name = g_strdup("x264enc");
    if (out_is_hw) *out_is_hw = FALSE;
    return g_strdup("videoconvert ! x264enc tune=zerolatency speed-preset=ultrafast bitrate=2048 key-int-max=15");
}

static void media_configure_cb(GstRTSPMediaFactory *factory, GstRTSPMedia *media, gpointer user_data) {
    LOGI("RTSP MediaFactory media-configure: attaching RTSP worker thread to JavaVM...");
    get_jni_env();

    GstElement *pipeline = gst_rtsp_media_get_element(media);
    if (pipeline != NULL) {
        GstBus *bus = gst_pipeline_get_bus(GST_PIPELINE(pipeline));
        if (bus != NULL) {
            gst_bus_add_signal_watch(bus);
            g_signal_connect(G_OBJECT(bus), "message::error", (GCallback)error_cb, NULL);
            g_signal_connect(G_OBJECT(bus), "message::warning", (GCallback)warning_cb, NULL);
            g_signal_connect(G_OBJECT(bus), "message::info", (GCallback)info_cb, NULL);
            gst_object_unref(bus);
        }
        gst_object_unref(pipeline);
    }
}

/*
 * Query available H.264 video encoders
 */
JNIEXPORT jstring JNICALL
Java_com_example_gstreamer_GStreamerBridge_nativeGetAvailableH264Encoders(JNIEnv *env, jobject thiz) {
    GList *list = gst_element_factory_list_get_elements(
        GST_ELEMENT_FACTORY_TYPE_ENCODER | GST_ELEMENT_FACTORY_TYPE_MEDIA_VIDEO,
        GST_RANK_MARGINAL);
    GstCaps *h264_caps = gst_caps_from_string("video/x-h264");
    GString *str = g_string_new("");

    for (GList *l = list; l != NULL; l = l->next) {
        GstElementFactory *factory = GST_ELEMENT_FACTORY(l->data);
        const gchar *name = gst_plugin_feature_get_name(GST_PLUGIN_FEATURE(factory));
        if (h264_caps != NULL && gst_element_factory_can_src_any_caps(factory, h264_caps)) {
            if (str->len > 0) g_string_append(str, ", ");
            g_string_append(str, name);
        }
    }
    if (h264_caps) gst_caps_unref(h264_caps);
    gst_plugin_feature_list_free(list);

    jstring res = (*env)->NewStringUTF(env, str->str);
    g_string_free(str, TRUE);
    return res;
}

/*
 * Native RTSP Server implementation with latency, resolution, and encoder controls
 */
JNIEXPORT jstring JNICALL
Java_com_example_gstreamer_GStreamerBridge_nativeStartRtspServer(JNIEnv *env, jobject thiz,
    jstring jport, jstring jpath, jint device_index, jstring jrotation,
    jstring jencoder_pref, jstring jresolution) {

    const char *port = (*env)->GetStringUTFChars(env, jport, NULL);
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    const char *rotation = (*env)->GetStringUTFChars(env, jrotation, NULL);
    const char *encoder_pref = jencoder_pref ? (*env)->GetStringUTFChars(env, jencoder_pref, NULL) : NULL;
    const char *resolution = jresolution ? (*env)->GetStringUTFChars(env, jresolution, NULL) : NULL;

    ensure_worker_thread();

    pthread_mutex_lock(&rtsp_mutex);

    /* Stop previous RTSP server instance if active */
    if (rtsp_server_running) {
        LOGI("Stopping existing RTSP server...");
        if (rtsp_server_source_id > 0) {
            g_source_remove(rtsp_server_source_id);
            rtsp_server_source_id = 0;
        }
        if (rtsp_server != NULL) {
            g_object_unref(rtsp_server);
            rtsp_server = NULL;
        }
        rtsp_server_running = FALSE;
    }

    char device_str[64] = "";
    if (device_index >= 0) {
        snprintf(device_str, sizeof(device_str), "device=%d", device_index);
    }

    char rotation_str[128] = "";
    if (rotation != NULL && strlen(rotation) > 0 && strcmp(rotation, "none") != 0) {
        snprintf(rotation_str, sizeof(rotation_str), "! videoflip method=%s ", rotation);
    }

    /* Resolution caps filter: enforce 4:3 full sensor dimensions (e.g. 1440x1080) to prevent square cropping */
    char res_caps[128] = "";
    int rw = 0, rh = 0;
    if (resolution != NULL && sscanf(resolution, "%dx%d", &rw, &rh) == 2 && rw > 0 && rh > 0) {
        snprintf(res_caps, sizeof(res_caps), "! video/x-raw,width=%d,height=%d,framerate=30/1 ", rw, rh);
    } else if (resolution != NULL) {
        if (strcmp(resolution, "full_sensor") == 0 || strcmp(resolution, "4:3") == 0 || strcmp(resolution, "native") == 0) {
            /* Full Sensor 4:3 native dimensions - 1440x1080 avoids square crop on all devices */
            snprintf(res_caps, sizeof(res_caps), "! video/x-raw,width=1440,height=1080,framerate=30/1 ");
        } else if (strcmp(resolution, "720p") == 0) {
            snprintf(res_caps, sizeof(res_caps), "! video/x-raw,width=1280,height=720,framerate=30/1 ");
        } else if (strcmp(resolution, "1080p") == 0) {
            snprintf(res_caps, sizeof(res_caps), "! video/x-raw,width=1920,height=1080,framerate=30/1 ");
        } else if (strcmp(resolution, "480p") == 0) {
            snprintf(res_caps, sizeof(res_caps), "! video/x-raw,width=854,height=480,framerate=30/1 ");
        }
    } else {
        /* Default: Full 4:3 Sensor 1440x1080 (no crop) */
        snprintf(res_caps, sizeof(res_caps), "! video/x-raw,width=1440,height=1080,framerate=30/1 ");
    }

    char *chosen_name = NULL;
    gboolean is_hw = FALSE;
    char *enc_fragment = get_h264_encoder_pipeline_fragment(encoder_pref, &chosen_name, &is_hw);

    if (chosen_name != NULL) {
        snprintf(current_active_encoder_name, sizeof(current_active_encoder_name), "%s (%s)",
            chosen_name, is_hw ? "Hardware MediaCodec" : "Software x264");
    } else {
        snprintf(current_active_encoder_name, sizeof(current_active_encoder_name), "x264enc (Software)");
    }

    /* Build factory pipeline string with 0-latency leaky queue, MTU 1400, and full sensor caps:
     * ( ahcsrc device=0 ! <res_caps> ! queue max-size-buffers=1 max-size-time=0 max-size-bytes=0 leaky=downstream <rotation_str> ! <enc_fragment> ! h264parse ! rtph264pay name=pay0 pt=96 config-interval=1 mtu=1400 )
     */
    char launch_str[1024];
    snprintf(launch_str, sizeof(launch_str),
        "( ahcsrc %s %s! queue max-size-buffers=1 max-size-time=0 max-size-bytes=0 leaky=downstream %s! %s ! h264parse ! rtph264pay name=pay0 pt=96 config-interval=1 mtu=1400 )",
        device_str, res_caps, rotation_str,
        enc_fragment ? enc_fragment : "videoconvert ! x264enc tune=zerolatency speed-preset=ultrafast");

    if (enc_fragment) g_free(enc_fragment);

    LOGI("Creating GstRTSPServer on port %s, path %s, pipeline: %s", port ? port : "8554", path ? path : "/live", launch_str);

    rtsp_server = gst_rtsp_server_new();
    if (rtsp_server == NULL) {
        pthread_mutex_unlock(&rtsp_mutex);
        if (port) (*env)->ReleaseStringUTFChars(env, jport, port);
        if (path) (*env)->ReleaseStringUTFChars(env, jpath, path);
        if (rotation) (*env)->ReleaseStringUTFChars(env, jrotation, rotation);
        if (encoder_pref) (*env)->ReleaseStringUTFChars(env, jencoder_pref, encoder_pref);
        if (resolution) (*env)->ReleaseStringUTFChars(env, jresolution, resolution);
        if (chosen_name) g_free(chosen_name);
        return (*env)->NewStringUTF(env, "Failed to create GstRTSPServer instance");
    }

    /* Bind explicitly to IPv4 wildcard 0.0.0.0 */
    gst_rtsp_server_set_address(rtsp_server, "0.0.0.0");

    if (port != NULL && strlen(port) > 0) {
        gst_rtsp_server_set_service(rtsp_server, port);
    }

    GstRTSPMountPoints *mounts = gst_rtsp_server_get_mount_points(rtsp_server);
    GstRTSPMediaFactory *factory = gst_rtsp_media_factory_new();

    gst_rtsp_media_factory_set_launch(factory, launch_str);
    gst_rtsp_media_factory_set_shared(factory, TRUE);
    gst_rtsp_media_factory_set_protocols(factory, GST_RTSP_LOWER_TRANS_TCP | GST_RTSP_LOWER_TRANS_UDP);

    g_signal_connect(factory, "media-configure", G_CALLBACK(media_configure_cb), NULL);

    const char *mount_path = (path != NULL && strlen(path) > 0) ? path : "/live";
    gst_rtsp_mount_points_add_factory(mounts, mount_path, factory);
    g_object_unref(mounts);

    rtsp_server_source_id = gst_rtsp_server_attach(rtsp_server, gst_main_context);
    if (rtsp_server_source_id == 0) {
        LOGE("Failed to attach GstRTSPServer to port %s", port ? port : "8554");
        g_object_unref(rtsp_server);
        rtsp_server = NULL;
        rtsp_server_running = FALSE;
        pthread_mutex_unlock(&rtsp_mutex);

        if (port) (*env)->ReleaseStringUTFChars(env, jport, port);
        if (path) (*env)->ReleaseStringUTFChars(env, jpath, path);
        if (rotation) (*env)->ReleaseStringUTFChars(env, jrotation, rotation);
        if (encoder_pref) (*env)->ReleaseStringUTFChars(env, jencoder_pref, encoder_pref);
        if (resolution) (*env)->ReleaseStringUTFChars(env, jresolution, resolution);
        if (chosen_name) g_free(chosen_name);
        return (*env)->NewStringUTF(env, "Failed to attach GstRTSPServer to socket port");
    }

    rtsp_server_running = TRUE;
    pthread_mutex_unlock(&rtsp_mutex);

    LOGI("GstRTSPServer attached successfully on port %s mount %s, active encoder: %s",
        port ? port : "8554", mount_path, current_active_encoder_name);

    gchar *log_msg = g_strdup_printf("RTSP Server online on port %s%s | Encoder: %s | Res: %s",
        port ? port : "8554", mount_path, current_active_encoder_name, resolution ? resolution : "Native");
    notify_java_message(log_msg, FALSE);
    g_free(log_msg);

    if (port) (*env)->ReleaseStringUTFChars(env, jport, port);
    if (path) (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (rotation) (*env)->ReleaseStringUTFChars(env, jrotation, rotation);
    if (encoder_pref) (*env)->ReleaseStringUTFChars(env, jencoder_pref, encoder_pref);
    if (resolution) (*env)->ReleaseStringUTFChars(env, jresolution, resolution);
    if (chosen_name) g_free(chosen_name);

    return NULL; /* Success */
}

JNIEXPORT void JNICALL
Java_com_example_gstreamer_GStreamerBridge_nativeStopRtspServer(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&rtsp_mutex);
    if (rtsp_server_running) {
        LOGI("Stopping GstRTSPServer...");
        if (rtsp_server_source_id > 0) {
            g_source_remove(rtsp_server_source_id);
            rtsp_server_source_id = 0;
        }
        if (rtsp_server != NULL) {
            g_object_unref(rtsp_server);
            rtsp_server = NULL;
        }
        rtsp_server_running = FALSE;
        LOGI("GstRTSPServer stopped");
        notify_java_message("RTSP Server stopped", FALSE);
    }
    pthread_mutex_unlock(&rtsp_mutex);
}

JNIEXPORT jboolean JNICALL
Java_com_example_gstreamer_GStreamerBridge_nativeIsRtspServerRunning(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&rtsp_mutex);
    jboolean running = rtsp_server_running ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock(&rtsp_mutex);
    return running;
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

