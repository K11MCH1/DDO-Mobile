#include "ddo_log.h"

#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <time.h>

static FILE *g_file;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

void ddo_log_open(const char *path) {
    pthread_mutex_lock(&g_lock);
    if (g_file) fclose(g_file);
    g_file = path ? fopen(path, "a") : NULL;
    if (g_file) setvbuf(g_file, NULL, _IOLBF, 0);
    pthread_mutex_unlock(&g_lock);
}

void ddo_log(int priority, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(priority, LOG_TAG, fmt, ap);
    va_end(ap);
    pthread_mutex_lock(&g_lock);
    if (g_file) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        struct tm tm;
        localtime_r(&ts.tv_sec, &tm);
        fprintf(g_file, "%02d:%02d:%02d.%03ld %c ", tm.tm_hour, tm.tm_min, tm.tm_sec, ts.tv_nsec / 1000000,
                priority >= ANDROID_LOG_ERROR ? 'E' : priority >= ANDROID_LOG_WARN ? 'W' : 'I');
        va_start(ap, fmt);
        vfprintf(g_file, fmt, ap);
        va_end(ap);
        fputc('\n', g_file);
    }
    pthread_mutex_unlock(&g_lock);
}
