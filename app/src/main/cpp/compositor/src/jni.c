#define _GNU_SOURCE
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/native_window_jni.h>
#include "compositor.h"

#define JNI_FN(name) Java_com_ddomobile_launcher_display_Compositor_##name

static char *dup_jstr(JNIEnv *env, jstring s) {
    if (!s) return NULL;
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    char *out = c ? strdup(c) : NULL;
    if (c) (*env)->ReleaseStringUTFChars(env, s, c);
    return out;
}

/* adrenotools loads its hook libraries from the app's extracted native library directory, which holds this one. */
static char *native_lib_dir(void) {
    Dl_info info;
    if (!dladdr((void *)native_lib_dir, &info) || !info.dli_fname) return NULL;
    char *dir = strdup(info.dli_fname);
    char *slash = dir ? strrchr(dir, '/') : NULL;
    if (!slash) { free(dir); return NULL; }
    *slash = 0;
    return dir;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeStart)(JNIEnv *env, jobject thiz, jstring runtimeDir, jstring socket,
                                               jint width, jint height, jstring driverDir, jstring driverName) {
    char *rt = dup_jstr(env, runtimeDir), *sock = dup_jstr(env, socket);
    char *dir = dup_jstr(env, driverDir), *name = dup_jstr(env, driverName);
    char path[512];
    int ok = 0;
    if (rt && sock && *sock) {
        if (sock[0] == '/') snprintf(path, sizeof(path), "%s", sock);
        else snprintf(path, sizeof(path), "%s/%s", rt, sock);
        if (!compositor_running()) {
            char *libdir = dir && *dir && name && *name ? native_lib_dir() : NULL;
            char *slashed = NULL;
            if (libdir && asprintf(&slashed, "%s%s", dir, dir[strlen(dir) - 1] == '/' ? "" : "/") < 0) slashed = NULL;
            vk_present_set_driver(slashed, slashed ? name : NULL, slashed ? libdir : NULL);
            free(slashed);
            free(libdir);
        }
        ok = compositor_start(path, width, height) == 0;
    }
    free(rt); free(sock); free(dir); free(name);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeStop)(JNIEnv *env, jobject thiz) { compositor_stop(); }

JNIEXPORT void JNICALL JNI_FN(nativeSetSurface)(JNIEnv *env, jobject thiz, jobject surface) {
    ANativeWindow *window = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    vk_present_set_window(window);
    if (!compositor_running()) return;
    compositor_post(MSG_WINDOW, 0, 0, 0, 0);
    if (!window) vkp_wait_window_request(2000);
}

JNIEXPORT void JNICALL JNI_FN(nativeResize)(JNIEnv *env, jobject thiz, jint width, jint height) {
    compositor_resize(width, height);
}

JNIEXPORT void JNICALL JNI_FN(nativePointerMotion)(JNIEnv *env, jobject thiz, jfloat x, jfloat y) {
    compositor_post(MSG_MOTION, 0, 0, x, y);
}

JNIEXPORT void JNICALL JNI_FN(nativePointerRelative)(JNIEnv *env, jobject thiz, jfloat dx, jfloat dy) {
    compositor_post(MSG_RELATIVE, 0, 0, dx, dy);
}

JNIEXPORT void JNICALL JNI_FN(nativePointerButton)(JNIEnv *env, jobject thiz, jint button, jboolean pressed) {
    compositor_post(MSG_BUTTON, button, pressed ? 1 : 0, 0, 0);
}

JNIEXPORT void JNICALL JNI_FN(nativePointerAxis)(JNIEnv *env, jobject thiz, jfloat vertical, jfloat horizontal) {
    compositor_post(MSG_AXIS, 0, 0, vertical, horizontal);
}

JNIEXPORT void JNICALL JNI_FN(nativeKey)(JNIEnv *env, jobject thiz, jint code, jboolean pressed) {
    compositor_post(MSG_KEY, code, pressed ? 1 : 0, 0, 0);
}

JNIEXPORT void JNICALL JNI_FN(nativeTouch)(JNIEnv *env, jobject thiz, jint id, jint action, jfloat x, jfloat y) {
    compositor_post(MSG_TOUCH, id, action, x, y);
}

JNIEXPORT jfloat JNICALL JNI_FN(nativeFramesPerSecond)(JNIEnv *env, jobject thiz) { return compositor_fps(); }

JNIEXPORT jboolean JNICALL JNI_FN(nativeHasClientSurface)(JNIEnv *env, jobject thiz) {
    return compositor_has_client_surface() ? JNI_TRUE : JNI_FALSE;
}
