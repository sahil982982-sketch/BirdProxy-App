LOCAL_PATH := $(call my-dir)
HEV_CORE := $(LOCAL_PATH)/hev

ifeq (,$(wildcard $(HEV_CORE)/Android.mk))
$(error Official Hev native source is missing. Run FETCH_NATIVE_CORE.sh or FETCH_NATIVE_CORE.bat)
endif

include $(HEV_CORE)/Android.mk
