TUNNEL_ROOT := $(call my-dir)
HEV_ROOT := $(TUNNEL_ROOT)/hev-socks5-tunnel
include $(HEV_ROOT)/third-part/yaml/Android.mk
include $(HEV_ROOT)/third-part/lwip/Android.mk
include $(HEV_ROOT)/third-part/hev-task-system/Android.mk

LOCAL_PATH := $(TUNNEL_ROOT)
SRCDIR := $(HEV_ROOT)/src
include $(HEV_ROOT)/build.mk
include $(CLEAR_VARS)
LOCAL_MODULE := hev-socks5-tunnel
LOCAL_SRC_FILES := $(patsubst $(LOCAL_PATH)/%,%,$(filter-out $(SRCDIR)/hev-jni.c,$(SRCFILES))) engine-jni.c
LOCAL_C_INCLUDES := $(SRCDIR) $(SRCDIR)/misc $(SRCDIR)/core/include \
    $(HEV_ROOT)/third-part/yaml/include $(HEV_ROOT)/third-part/lwip/src/include \
    $(HEV_ROOT)/third-part/lwip/src/ports/include $(HEV_ROOT)/third-part/hev-task-system/include
LOCAL_CFLAGS := -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY $(VERSION_CFLAGS)
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDFLAGS := -Wl,--wrap=hev_task_system_run -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
