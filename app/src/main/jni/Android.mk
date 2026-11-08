LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := tutorial-1
LOCAL_SRC_FILES := tutorial-1.c
LOCAL_SHARED_LIBRARIES := gstreamer_android
LOCAL_LDLIBS := -llog -landroid -lGLESv2 -lEGL
include $(BUILD_SHARED_LIBRARY)

# Retrieve GSTREAMER_ROOT_ANDROID from arguments, environment, or fallback
ifeq ($(strip $(GSTREAMER_ROOT_ANDROID)),)
    GSTREAMER_ROOT_ANDROID := $(GST_DEBUG_ROOT_ANDROID)
endif

ifeq ($(strip $(GSTREAMER_ROOT_ANDROID)),)
    $(error GSTREAMER_ROOT_ANDROID is not defined!)
endif

# Map TARGET_ARCH_ABI to the respective ABI folder in the universal SDK
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    GSTREAMER_ROOT := $(wildcard $(GSTREAMER_ROOT_ANDROID)/armv7)
    ifeq ($(strip $(GSTREAMER_ROOT)),)
        GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/arm
    endif
else ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
    GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/arm64
else
    GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)
endif

# Fallback to root directory if share/gst-android exists directly in root
ifeq ($(wildcard $(GSTREAMER_ROOT)/share/gst-android/ndk-build),)
    GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)
endif

GSTREAMER_NDK_BUILD_PATH := $(GSTREAMER_ROOT)/share/gst-android/ndk-build
include $(GSTREAMER_NDK_BUILD_PATH)/plugins.mk

# Core + Playback + System + Network + H264/H265/AAC Parsers & Decoders
GSTREAMER_PLUGINS := \
    $(GSTREAMER_PLUGINS_CORE) \
    $(GSTREAMER_PLUGINS_PLAYBACK) \
    $(GSTREAMER_PLUGINS_SYS) \
    $(GSTREAMER_PLUGINS_NET) \
    $(GSTREAMER_PLUGINS_NET_RESTRICTED) \
    typefindfunctions \
    videoparsersbad \
    audioparsers \
    isomp4 \
    matroska \
    rtsp \
    rtp \
    rtpmanager \
    udp \
    tcp \
    soup \
    androidmedia \
    libav

GSTREAMER_EXTRA_DEPS := gstreamer-video-1.0
GSTREAMER_COPY_CACERTIFICATES := no
GSTREAMER_COPY_FONTS := no

# GStreamer makefile MUST be included at the end of Android.mk
include $(GSTREAMER_NDK_BUILD_PATH)/gstreamer-1.0.mk
