// Samples which file reads a process's threads are inside, from /proc (run as the same uid, e.g. through run-as).
//   readsampler PID SECONDS
// Prints, per thread: samples spent in read-type syscalls and the read syscall count over the window (from
// /proc/.../io); per file: samples and a histogram of requested sizes. Build: tools/build-readsampler.sh
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define MAX_TASKS 512
#define MAX_FDS 4096

struct task {
    int tid;
    char comm[32];
    long syscr0, syscr1;
    long samples, in_read;
};

static struct task tasks[MAX_TASKS];
static int ntasks;
static long fd_samples[MAX_FDS];
static long fd_sizes[MAX_FDS][24];

static double now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

static long read_syscr(int pid, int tid) {
    char path[64], line[128];
    snprintf(path, sizeof path, "/proc/%d/task/%d/io", pid, tid);
    FILE *f = fopen(path, "r");
    long v = -1;
    if (!f) return -1;
    while (fgets(line, sizeof line, f))
        if (sscanf(line, "syscr: %ld", &v) == 1) break;
    fclose(f);
    return v;
}

static void scan_tasks(int pid) {
    char path[64];
    snprintf(path, sizeof path, "/proc/%d/task", pid);
    DIR *d = opendir(path);
    struct dirent *e;
    if (!d) return;
    while ((e = readdir(d)) && ntasks < MAX_TASKS) {
        int tid = atoi(e->d_name);
        if (tid <= 0) continue;
        int known = 0;
        for (int i = 0; i < ntasks; i++) if (tasks[i].tid == tid) known = 1;
        if (known) continue;
        struct task *t = &tasks[ntasks++];
        memset(t, 0, sizeof *t);
        t->tid = tid;
        snprintf(path, sizeof path, "/proc/%d/task/%d/comm", pid, tid);
        FILE *f = fopen(path, "r");
        if (f) {
            if (fgets(t->comm, sizeof t->comm, f)) t->comm[strcspn(t->comm, "\n")] = 0;
            fclose(f);
        }
        t->syscr0 = read_syscr(pid, tid);
    }
    closedir(d);
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: readsampler PID SECONDS\n");
        return 2;
    }
    int pid = atoi(argv[1]);
    double end = now() + atof(argv[2]), start = now(), rescan = 0;
    long rounds = 0;
    while (now() < end) {
        if (now() >= rescan) {
            scan_tasks(pid);
            rescan = now() + 1;
        }
        for (int i = 0; i < ntasks; i++) {
            char path[64], buf[256];
            snprintf(path, sizeof path, "/proc/%d/task/%d/syscall", pid, tasks[i].tid);
            FILE *f = fopen(path, "r");
            if (!f) continue;
            size_t n = fread(buf, 1, sizeof buf - 1, f);
            fclose(f);
            buf[n] = 0;
            tasks[i].samples++;
            long nr;
            unsigned long fd, bufp, count;
            if (sscanf(buf, "%ld 0x%lx 0x%lx 0x%lx", &nr, &fd, &bufp, &count) != 4) continue;
            if (nr != 63 && nr != 67 && nr != 65 && nr != 69) continue;  // read, pread64, readv, preadv
            tasks[i].in_read++;
            if (fd < MAX_FDS) {
                fd_samples[fd]++;
                int b = 0;
                while ((1UL << b) < count && b < 23) b++;
                if (nr == 63 || nr == 67) fd_sizes[fd][b]++;
            }
        }
        rounds++;
    }
    double secs = now() - start;
    printf("%ld sampling rounds in %.1f s (%.0f/s)\n", rounds, secs, rounds / secs);
    printf("%-7s %-16s %8s %8s %12s\n", "tid", "thread", "samples", "in read", "reads/s");
    for (int i = 0; i < ntasks; i++) {
        struct task *t = &tasks[i];
        t->syscr1 = read_syscr(pid, t->tid);
        long rate = t->syscr0 >= 0 && t->syscr1 >= 0 ? (long)((t->syscr1 - t->syscr0) / secs) : -1;
        if (t->in_read || rate > 100)
            printf("%-7d %-16s %8ld %8ld %12ld\n", t->tid, t->comm, t->samples, t->in_read, rate);
    }
    printf("\nfile samples and requested sizes (bucket = up to 2^n bytes: count)\n");
    for (int fd = 0; fd < MAX_FDS; fd++) {
        if (!fd_samples[fd]) continue;
        char path[64], target[256];
        snprintf(path, sizeof path, "/proc/%d/fd/%d", pid, fd);
        ssize_t n = readlink(path, target, sizeof target - 1);
        target[n > 0 ? n : 0] = 0;
        printf("fd %-4d %6ld  %s\n       ", fd, fd_samples[fd], target);
        for (int b = 0; b < 24; b++) if (fd_sizes[fd][b]) printf(" 2^%d:%ld", b, fd_sizes[fd][b]);
        printf("\n");
    }
    return 0;
}
