LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := loggerx_crash
LOCAL_SRC_FILES := loggerx_crash.c
LOCAL_LDLIBS := -llog -ldl
include $(BUILD_SHARED_LIBRARY)
