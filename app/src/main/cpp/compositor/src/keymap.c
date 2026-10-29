#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <linux/memfd.h>
#include "compositor.h"

#ifndef F_ADD_SEALS
#define F_ADD_SEALS 1033
#define F_SEAL_SEAL 0x0001
#define F_SEAL_SHRINK 0x0002
#define F_SEAL_GROW 0x0004
#define F_SEAL_WRITE 0x0008
#endif

/* The compiled keymap from DroidDeck's assets/wayland/keymap.xkb, NUL-terminated as xkb_keymap_new_from_string expects. */
__asm__(".section .rodata\n"
        ".balign 16\n"
        ".hidden ddo_keymap\n"
        ".global ddo_keymap\n"
        "ddo_keymap:\n"
        ".incbin \"" DDO_KEYMAP_PATH "\"\n"
        ".byte 0\n"
        ".hidden ddo_keymap_end\n"
        ".global ddo_keymap_end\n"
        "ddo_keymap_end:\n"
        ".previous\n");
extern const char ddo_keymap[], ddo_keymap_end[];

int keymap_create(uint32_t *size) {
    const size_t n = (size_t)(ddo_keymap_end - ddo_keymap);
    int fd = (int)syscall(__NR_memfd_create, "ddo-keymap", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (fd < 0) { LOGE("keymap: memfd_create failed (%s)", strerror(errno)); return -1; }
    size_t done = 0;
    while (done < n) {
        ssize_t w = write(fd, ddo_keymap + done, n - done);
        if (w < 0 && errno == EINTR) continue;
        if (w <= 0) { LOGE("keymap: write failed (%s)", strerror(errno)); close(fd); return -1; }
        done += (size_t)w;
    }
    fcntl(fd, F_ADD_SEALS, F_SEAL_SEAL | F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_WRITE);
    *size = (uint32_t)n;
    return fd;
}
