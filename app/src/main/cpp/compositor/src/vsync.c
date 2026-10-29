#define _GNU_SOURCE
#include <pthread.h>
#include <stdatomic.h>
#include <unistd.h>
#include <sys/resource.h>
#include <android/choreographer.h>
#include <android/looper.h>
#include "compositor.h"

static pthread_t g_thread;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_cond = PTHREAD_COND_INITIALIZER;
static ALooper *g_looper;
static int g_started;
static atomic_int g_run;
static void (*g_on_vsync)(int64_t);

/* AChoreographer_postFrameCallback64 needs API 29; on arm64 the older callback's long is 64-bit already. */
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
static void on_frame(long frame_time_ns, void *data) {
    if (!atomic_load(&g_run)) return;
    g_on_vsync((int64_t)frame_time_ns);
    AChoreographer_postFrameCallback(AChoreographer_getInstance(), on_frame, NULL);
}

static void *vsync_main(void *arg) {
    pthread_setname_np(pthread_self(), "ddo-vsync");
    setpriority(PRIO_PROCESS, (id_t)gettid(), -8);
    ALooper *looper = ALooper_prepare(0);
    ALooper_acquire(looper);
    AChoreographer *choreographer = AChoreographer_getInstance();
    if (choreographer) AChoreographer_postFrameCallback(choreographer, on_frame, NULL);
    else LOGE("vsync: no AChoreographer; frames are paced by a timer");
    pthread_mutex_lock(&g_lock);
    g_looper = looper;
    pthread_cond_broadcast(&g_cond);
    pthread_mutex_unlock(&g_lock);
    while (atomic_load(&g_run)) ALooper_pollOnce(-1, NULL, NULL, NULL);
    return NULL;
}
#pragma clang diagnostic pop

int vsync_start(void (*on_vsync)(int64_t frame_time_ns)) {
    if (g_started) return 0;
    g_on_vsync = on_vsync;
    atomic_store(&g_run, 1);
    if (pthread_create(&g_thread, NULL, vsync_main, NULL) != 0) {
        atomic_store(&g_run, 0);
        return -1;
    }
    pthread_mutex_lock(&g_lock);
    while (!g_looper) pthread_cond_wait(&g_cond, &g_lock);
    pthread_mutex_unlock(&g_lock);
    g_started = 1;
    return 0;
}

void vsync_stop(void) {
    if (!g_started) return;
    atomic_store(&g_run, 0);
    ALooper_wake(g_looper);
    pthread_join(g_thread, NULL);
    ALooper_release(g_looper);
    g_looper = NULL;
    g_started = 0;
}
