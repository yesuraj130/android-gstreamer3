LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

# The GSTREAMER_ROOT_ANDROID environment variable or gradle property points to the GStreamer SDK
GSTREAMER_ROOT_ANDROID := $(GST_DEBUG_ROOT_ANDROID)
ifndef GSTREAMER_ROOT_ANDROID
$(error GSTREAMER_ROOT_ANDROID is not defined!)
endif

GSTREAMER_NDK_BUILD_PATH := $(GSTREAMER_ROOT_ANDROID)/share/gst-android/ndk-build
include $(GSTREAMER_NDK_BUILD_PATH)/plugins.mk

# Tutorial 1: Default core elements only
GSTREAMER_PLUGINS := $(GSTREAMER_PLUGINS_CORE)
GSTREAMER_EXTRA_DEPS := gstreamer-1.0

include $(GSTREAMER_NDK_BUILD_PATH)/gstreamer-1.0.mk

include $(CLEAR_VARS)
LOCAL_MODULE := tutorial-1
LOCAL_SRC_FILES := tutorial-1.c
LOCAL_SHARED_LIBRARIES := gstreamer_android
LOCAL_LDLIBS := -llog -landroid
include $(BUILD_SHARED_LIBRARY)
