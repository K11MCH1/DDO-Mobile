#pragma once
#include <android/log.h>

#define LOG_TAG "DDOCompositor"
#define LOGI(...) ddo_log(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) ddo_log(ANDROID_LOG_WARN, __VA_ARGS__)
#define LOGE(...) ddo_log(ANDROID_LOG_ERROR, __VA_ARGS__)

/* Some vendors (vivo) drop third-party apps' logcat output, so messages also go to a file when one is open. */
void ddo_log_open(const char *path);
void ddo_log(int priority, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
