/* Derived from DroidDeck waylandcomp/src/wl_dmabuf.c and compositor.c (GPL-3.0); trimmed for DDO Mobile, 2026-10. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <wayland-server.h>

#include "linux-dmabuf-v1-server-protocol.h"
#include "compositor.h"

#define DMABUF_NFMT 4
#define DMABUF_NMOD 4

static struct { uint32_t fmt; uint64_t mods[DMABUF_NMOD]; int n; } g_fmts[DMABUF_NFMT] = {
    {DRM_ARGB8888}, {DRM_XRGB8888}, {DRM_ABGR8888}, {DRM_XBGR8888}};
static int g_fmts_ready;

struct dmabuf_params {
    int fd[MAX_PLANES];
    uint32_t offset[MAX_PLANES], stride[MAX_PLANES];
    uint64_t modifier[MAX_PLANES];
    int n_planes;
};

void dmabuf_buffer_unref(struct dmabuf_buffer *b) {
    if (!b || --b->refs > 0) return;
    vkp_image_destroy(b->img);
    for (int i = 0; i < b->n_planes; i++)
        if (b->fd[i] >= 0) close(b->fd[i]);
    free(b);
}

static void buffer_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_buffer_interface buffer_impl = { .destroy = buffer_destroy_req };

struct dmabuf_buffer *dmabuf_from_resource(struct wl_resource *buffer) {
    if (buffer && wl_resource_instance_of(buffer, &wl_buffer_interface, &buffer_impl))
        return wl_resource_get_user_data(buffer);
    return NULL;
}

static void buffer_resource_destroy(struct wl_resource *r) {
    dmabuf_buffer_unref(wl_resource_get_user_data(r));
}

static void params_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void params_add(struct wl_client *c, struct wl_resource *r, int32_t fd, uint32_t plane,
                       uint32_t offset, uint32_t stride, uint32_t mod_hi, uint32_t mod_lo) {
    struct dmabuf_params *p = wl_resource_get_user_data(r);
    if (plane >= MAX_PLANES) {
        close(fd);
        wl_resource_post_error(r, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_IDX, "plane index %u is too high", plane);
        return;
    }
    if (p->fd[plane] >= 0) close(p->fd[plane]);
    p->fd[plane] = fd;
    p->offset[plane] = offset;
    p->stride[plane] = stride;
    p->modifier[plane] = ((uint64_t)mod_hi << 32) | mod_lo;
    if ((int)plane + 1 > p->n_planes) p->n_planes = (int)plane + 1;
}

static struct wl_resource *params_do_create(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                            int32_t w, int32_t h, uint32_t format) {
    struct dmabuf_params *p = wl_resource_get_user_data(r);
    struct dmabuf_buffer *b = calloc(1, sizeof(*b));
    if (!b) return NULL;
    b->n_planes = p->n_planes;
    b->refs = 1;
    b->width = w; b->height = h; b->format = format;
    b->modifier = p->modifier[0];
    for (int i = 0; i < MAX_PLANES; i++) b->fd[i] = -1;
    for (int i = 0; i < p->n_planes; i++) {
        b->fd[i] = p->fd[i];
        b->offset[i] = p->offset[i];
        b->stride[i] = p->stride[i];
        p->fd[i] = -1;
    }
    struct wl_resource *buf = wl_resource_create(c, &wl_buffer_interface, 1, id);
    if (!buf) {
        dmabuf_buffer_unref(b);
        wl_client_post_no_memory(c);
        return NULL;
    }
    wl_resource_set_implementation(buf, &buffer_impl, b, buffer_resource_destroy);
    return buf;
}

static void params_create(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h,
                          uint32_t format, uint32_t flags) {
    struct wl_resource *buf = params_do_create(c, r, 0, w, h, format);
    if (buf) zwp_linux_buffer_params_v1_send_created(r, buf);
    else zwp_linux_buffer_params_v1_send_failed(r);
}

static void params_create_immed(struct wl_client *c, struct wl_resource *r, uint32_t buffer_id,
                                int32_t w, int32_t h, uint32_t format, uint32_t flags) {
    params_do_create(c, r, buffer_id, w, h, format);
}

static const struct zwp_linux_buffer_params_v1_interface params_impl = {
    .destroy = params_destroy,
    .add = params_add,
    .create = params_create,
    .create_immed = params_create_immed,
};

static void params_resource_destroy(struct wl_resource *r) {
    struct dmabuf_params *p = wl_resource_get_user_data(r);
    if (!p) return;
    for (int i = 0; i < MAX_PLANES; i++)
        if (p->fd[i] >= 0) close(p->fd[i]);
    free(p);
}

static void dmabuf_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void dmabuf_create_params(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct dmabuf_params *p = calloc(1, sizeof(*p));
    if (!p) { wl_client_post_no_memory(c); return; }
    for (int i = 0; i < MAX_PLANES; i++) p->fd[i] = -1;
    struct wl_resource *pr = wl_resource_create(c, &zwp_linux_buffer_params_v1_interface,
                                                wl_resource_get_version(r), id);
    if (!pr) { free(p); wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(pr, &params_impl, p, params_resource_destroy);
}

static const struct zwp_linux_dmabuf_v1_interface dmabuf_impl = {
    .destroy = dmabuf_destroy,
    .create_params = dmabuf_create_params,
};

static void build_formats(void) {
    char line[256];
    int pos = 0;
    g_fmts_ready = 1;
    for (int f = 0; f < DMABUF_NFMT; f++) {
        uint64_t got[DMABUF_NMOD];
        int n = vkp_dmabuf_modifiers(g_fmts[f].fmt, got, DMABUF_NMOD), k = 0;
        g_fmts[f].mods[k++] = VKP_MOD_LINEAR;
        for (int i = 0; i < n && k < DMABUF_NMOD - 1; i++)
            if (got[i] != VKP_MOD_LINEAR) g_fmts[f].mods[k++] = got[i];
        g_fmts[f].mods[k++] = VKP_MOD_INVALID;
        g_fmts[f].n = k;
        const uint32_t fmt = g_fmts[f].fmt;
        pos += snprintf(line + pos, sizeof(line) - (size_t)pos, "%s%c%c%c%c", f ? ", " : "",
                        fmt & 0xff, (fmt >> 8) & 0xff, (fmt >> 16) & 0xff, (fmt >> 24) & 0xff);
        for (int i = 0; i < k - 1 && pos < (int)sizeof(line); i++)
            pos += snprintf(line + pos, sizeof(line) - (size_t)pos, "%s%s", i ? "+" : " ",
                            vkp_modifier_name(g_fmts[f].mods[i]));
        if (pos >= (int)sizeof(line)) pos = (int)sizeof(line) - 1;
    }
    LOGI("dmabuf formats: %s", line);
}

static void bind_dmabuf(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &zwp_linux_dmabuf_v1_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &dmabuf_impl, NULL, NULL);
    if (!g_fmts_ready) build_formats();
    for (int f = 0; f < DMABUF_NFMT; f++) {
        zwp_linux_dmabuf_v1_send_format(r, g_fmts[f].fmt);
        if (ver >= ZWP_LINUX_DMABUF_V1_MODIFIER_SINCE_VERSION)
            for (int m = 0; m < g_fmts[f].n; m++)
                zwp_linux_dmabuf_v1_send_modifier(r, g_fmts[f].fmt, (uint32_t)(g_fmts[f].mods[m] >> 32),
                                                  (uint32_t)(g_fmts[f].mods[m] & 0xffffffff));
    }
}

void dmabuf_init(struct wl_display *display) {
    wl_global_create(display, &zwp_linux_dmabuf_v1_interface, 3, NULL, bind_dmabuf);
}
