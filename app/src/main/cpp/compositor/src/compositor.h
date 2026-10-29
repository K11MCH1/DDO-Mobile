/* Derived from DroidDeck waylandcomp/src/compositor_internal.h (GPL-3.0); modified for DDO Mobile, 2026-10. */
#pragma once
#include <stdint.h>
#include <wayland-server.h>
#include "ddo_log.h"
#include "vk_present.h"

#define FOURCC(a, b, c, d) \
    ((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24))
#define DRM_ARGB8888 FOURCC('A', 'R', '2', '4')
#define DRM_XRGB8888 FOURCC('X', 'R', '2', '4')
#define DRM_ABGR8888 FOURCC('A', 'B', '2', '4')
#define DRM_XBGR8888 FOURCC('X', 'B', '2', '4')
#define MAX_PLANES 4

struct dmabuf_buffer {
    int fd[MAX_PLANES];
    uint32_t offset[MAX_PLANES], stride[MAX_PLANES];
    int n_planes;
    int32_t width, height;
    uint32_t format;
    uint64_t modifier;
    struct vkp_image *img;
    int import_failed;
    int refs;
};

void dmabuf_init(struct wl_display *display);
struct dmabuf_buffer *dmabuf_from_resource(struct wl_resource *buffer);
void dmabuf_buffer_unref(struct dmabuf_buffer *b);

void single_pixel_init(struct wl_display *display);
int single_pixel_buffer_get(struct wl_resource *buffer, uint8_t bgra[4]);

int keymap_create(uint32_t *size);

int vsync_start(void (*on_vsync)(int64_t frame_time_ns));
void vsync_stop(void);

enum comp_msg_type {
    MSG_STOP, MSG_VSYNC, MSG_WINDOW, MSG_RESIZE,
    MSG_MOTION, MSG_RELATIVE, MSG_BUTTON, MSG_AXIS, MSG_KEY, MSG_TOUCH,
};

int compositor_start(const char *socket_path, int width, int height);
void compositor_stop(void);
int compositor_running(void);
void compositor_post(int type, int a, int b, float x, float y);
void compositor_resize(int width, int height);
float compositor_fps(void);
int compositor_has_client_surface(void);
/* Draws the client's cursor; off by default, since touch points by itself. */
void compositor_set_cursor_shown(int shown);
