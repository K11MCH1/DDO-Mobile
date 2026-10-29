/* Derived from DroidDeck waylandcomp/src/compositor.c (GPL-3.0); reduced to one fullscreen client for DDO Mobile, 2026-10. */
#define _GNU_SOURCE 1
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/resource.h>
#include <wayland-server.h>

#include "xdg-shell-server-protocol.h"
#include "viewporter-server-protocol.h"
#include "presentation-time-server-protocol.h"
#include "pointer-constraints-unstable-v1-server-protocol.h"
#include "relative-pointer-unstable-v1-server-protocol.h"
#include "compositor.h"

#define BTN_LEFT 0x110
#define MAX_SEAT_RESOURCES 32
#define MAX_TOUCH_POINTS 32
#define VSYNC_FRESH_NS 100000000LL

struct comp_msg { int type; int a, b; float x, y; int64_t t; };

enum surface_role { ROLE_NONE, ROLE_TOPLEVEL, ROLE_SUBSURFACE, ROLE_CURSOR };

struct surface {
    struct wl_resource *resource;
    struct wl_list link;

    struct wl_resource *pending_buffer;
    struct wl_listener pending_buffer_destroy;
    int pending_attach;
    int pending_src_set, pending_dst_set;
    float pending_src[4];
    int pending_dst[2];
    int pending_opaque[4], pending_opaque_set;
    struct wl_list pending_frames, pending_feedback;

    struct vkp_image *shm_img;
    struct wl_resource *dmabuf;
    struct dmabuf_buffer *dmabuf_buf;
    struct wl_listener dmabuf_destroy;
    int buf_w, buf_h, buf_alpha, has_buffer, has_content;
    int opaque[4];
    int src_set, dst_set;
    float src[4];
    int dst[2];
    struct wl_list frames, feedback;
    int drawn;

    enum surface_role role;
    struct wl_resource *xdg_surface, *xdg_toplevel, *viewport, *subsurface;
    int configured, mapped;
    struct wl_list toplevel_link;

    struct surface *parent;
    struct wl_list children, child_link;
    int sub_x, sub_y, sub_pending_x, sub_pending_y, sub_pending, below_parent;
};

struct region { int set, exact; int x, y, w, h; };
struct seat_resource { struct wl_resource *res; struct wl_resource *focus; };

struct constraint {
    struct wl_list link;
    struct wl_resource *resource;
    struct wl_resource *pointer;
    struct surface *surface;
    int is_lock;
    uint32_t lifetime;
    int active, defunct;
    struct region region, pending_region;
    int pending_region_set;
    int hint_set, pending_hint_set;
    double hint_x, hint_y, pending_hint_x, pending_hint_y;
};

struct relative_pointer {
    struct wl_list link;
    struct wl_resource *resource;
    struct wl_resource *pointer;
};

static pthread_mutex_t g_life_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t g_thread;
static atomic_int g_running;
static int g_pipe[2] = {-1, -1};
static atomic_int g_output_w = 1920, g_output_h = 1080;
static atomic_int g_has_toplevel, g_fps_milli;
static atomic_int g_cursor_shown;

static struct wl_display *g_display;
static int g_stopping;
static struct wl_list g_surfaces, g_toplevels, g_outputs, g_constraints, g_relative_pointers;
static int g_keymap_fd = -1;
static uint32_t g_keymap_size;

static struct wl_event_source *g_render_idle, *g_fallback_timer, *g_frame_timer, *g_stats_timer;
static int g_dirty, g_fallback_armed;
static int64_t g_tick_ns, g_rendered_tick_ns, g_last_vsync_ns, g_min_interval_ns, g_fps_since_ns;
static int g_refresh_mhz = 60000;
static int g_refresh_candidate_hz, g_refresh_candidate_seconds;
static unsigned g_frames, g_stat_frames, g_stat_dmabuf, g_stat_shm, g_stats_ticks;

static struct seat_resource g_ptrs[MAX_SEAT_RESOURCES], g_kbs[MAX_SEAT_RESOURCES], g_touches[MAX_SEAT_RESOURCES];
static int g_nptrs, g_nkbs, g_ntouches;
static uint32_t g_mods_depressed, g_mods_locked, g_mod_keys_held;
static uint8_t g_keys_down[0x300 / 8];

static struct surface *g_cursor_surface;
static int g_cursor_hx, g_cursor_hy;
static double g_ptr_x, g_ptr_y, g_raw_x, g_raw_y;
static int g_raw_valid, g_ptr_placed;
static struct { struct surface *surface; int pointer_fallback; } g_touch_points[MAX_TOUCH_POINTS];
static struct constraint *g_active_constraint;

static void schedule_render(void);
static void seat_focus(void);
static void touch_cancel_surface(struct surface *s);
static void constraints_surface_gone(struct surface *s);
static void constraints_surface_commit(struct surface *s);
static void constraints_pointer_gone(struct wl_resource *pointer);
static void constraints_focus_entered(struct wl_resource *target, struct wl_client *client);
static void relative_pointers_pointer_gone(struct wl_resource *pointer);

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static uint32_t now_ms(void) { return (uint32_t)(now_ns() / 1000000); }

static uint32_t refresh_ns(void) { return (uint32_t)(1000000000000LL / g_refresh_mhz); }

static void surface_size(const struct surface *s, int *w, int *h) {
    if (s->dst_set) { *w = s->dst[0]; *h = s->dst[1]; }
    else if (s->src_set) { *w = (int)(s->src[2] + 0.5f); *h = (int)(s->src[3] + 0.5f); }
    else { *w = s->buf_w; *h = s->buf_h; }
}

static struct surface *top_toplevel(void) {
    struct surface *s;
    if (wl_list_empty(&g_toplevels)) return NULL;
    return wl_container_of(g_toplevels.prev, s, toplevel_link);
}

static void scene_size(int *w, int *h) {
    struct surface *top = top_toplevel();
    *w = *h = 0;
    if (top) surface_size(top, w, h);
    if (*w <= 0 || *h <= 0) { *w = atomic_load(&g_output_w); *h = atomic_load(&g_output_h); }
}


static void fire_frames(struct wl_list *frames) {
    struct wl_resource *cb, *tmp;
    const uint32_t t = now_ms();
    wl_resource_for_each_safe(cb, tmp, frames) {
        wl_callback_send_done(cb, t);
        wl_resource_destroy(cb);
    }
}

static void feedback_discard_all(struct wl_list *list) {
    struct wl_resource *fb, *tmp;
    wl_resource_for_each_safe(fb, tmp, list) {
        wp_presentation_feedback_send_discarded(fb);
        wl_resource_destroy(fb);
    }
}

static void feedback_present_all(struct wl_list *list, int64_t t) {
    struct wl_resource *fb, *tmp;
    const uint64_t sec = (uint64_t)(t / 1000000000LL);
    const uint32_t nsec = (uint32_t)(t % 1000000000LL);
    wl_resource_for_each_safe(fb, tmp, list) {
        wp_presentation_feedback_send_presented(fb, (uint32_t)(sec >> 32), (uint32_t)sec, nsec, refresh_ns(), 0, 0,
                                                WP_PRESENTATION_FEEDBACK_KIND_VSYNC);
        wl_resource_destroy(fb);
    }
}

static void unlink_resource(struct wl_resource *r) { wl_list_remove(wl_resource_get_link(r)); }

static void presentation_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void presentation_feedback(struct wl_client *c, struct wl_resource *r, struct wl_resource *surface, uint32_t id) {
    struct surface *s = wl_resource_get_user_data(surface);
    struct wl_resource *fb = wl_resource_create(c, &wp_presentation_feedback_interface, wl_resource_get_version(r), id);
    if (!fb) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(fb, NULL, NULL, unlink_resource);
    wl_list_insert(s->pending_feedback.prev, wl_resource_get_link(fb));
}
static const struct wp_presentation_interface presentation_impl = {
    .destroy = presentation_destroy,
    .feedback = presentation_feedback,
};
static void bind_presentation(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wp_presentation_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &presentation_impl, NULL, NULL);
    wp_presentation_send_clock_id(r, CLOCK_MONOTONIC);
}


static void drop_dmabuf(struct surface *s) {
    if (s->dmabuf) {
        wl_list_remove(&s->dmabuf_destroy.link);
        wl_buffer_send_release(s->dmabuf);
        s->dmabuf = NULL;
    }
    dmabuf_buffer_unref(s->dmabuf_buf);
    s->dmabuf_buf = NULL;
}

static void on_dmabuf_destroyed(struct wl_listener *l, void *data) {
    struct surface *s = wl_container_of(l, s, dmabuf_destroy);
    wl_list_remove(&s->dmabuf_destroy.link);
    s->dmabuf = NULL;
}

static void on_pending_buffer_destroyed(struct wl_listener *l, void *data) {
    struct surface *s = wl_container_of(l, s, pending_buffer_destroy);
    wl_list_remove(&s->pending_buffer_destroy.link);
    wl_list_init(&s->pending_buffer_destroy.link);
    s->pending_buffer = NULL;
}

static void take_shm(struct surface *s, struct wl_shm_buffer *shm, struct wl_resource *buffer) {
    const int32_t w = wl_shm_buffer_get_width(shm), h = wl_shm_buffer_get_height(shm);
    const int32_t stride = wl_shm_buffer_get_stride(shm);
    if (s->shm_img && (vkp_image_width(s->shm_img) != w || vkp_image_height(s->shm_img) != h)) {
        vkp_image_destroy(s->shm_img);
        s->shm_img = NULL;
    }
    if (!s->shm_img) s->shm_img = vkp_image_create_shm(w, h);
    if (s->shm_img) {
        wl_shm_buffer_begin_access(shm);
        vkp_image_upload_shm(s->shm_img, wl_shm_buffer_get_data(shm), stride);
        wl_shm_buffer_end_access(shm);
    }
    wl_buffer_send_release(buffer);
    s->buf_w = w;
    s->buf_h = h;
    s->buf_alpha = wl_shm_buffer_get_format(shm) == WL_SHM_FORMAT_ARGB8888;
    s->has_content = s->shm_img != NULL;
    g_stat_shm++;
}

static void take_pixel(struct surface *s, const uint8_t bgra[4], struct wl_resource *buffer) {
    if (s->shm_img && (vkp_image_width(s->shm_img) != 1 || vkp_image_height(s->shm_img) != 1)) {
        vkp_image_destroy(s->shm_img);
        s->shm_img = NULL;
    }
    if (!s->shm_img) s->shm_img = vkp_image_create_shm(1, 1);
    if (s->shm_img) vkp_image_upload_shm(s->shm_img, bgra, 4);
    wl_buffer_send_release(buffer);
    s->buf_w = 1;
    s->buf_h = 1;
    s->buf_alpha = bgra[3] != 0xff;
    s->has_content = s->shm_img != NULL;
}

static void take_dmabuf(struct surface *s, struct dmabuf_buffer *b, struct wl_resource *buffer) {
    static int announced;
    if (s->dmabuf != buffer || s->dmabuf_buf != b) {
        drop_dmabuf(s);
        s->dmabuf = buffer;
        s->dmabuf_buf = b;
        b->refs++;
        s->dmabuf_destroy.notify = on_dmabuf_destroyed;
        wl_resource_add_destroy_listener(buffer, &s->dmabuf_destroy);
        g_stat_dmabuf++;
    }
    if (!b->img && !b->import_failed && b->n_planes >= 1) {
        b->img = vkp_image_from_dmabuf(b->fd[0], b->format, b->modifier, b->width, b->height, b->stride[0], b->offset[0]);
        if (!b->img) {
            b->import_failed = 1;
            LOGE("dmabuf import failed (%dx%d %c%c%c%c %s)", b->width, b->height, b->format & 0xff,
                 (b->format >> 8) & 0xff, (b->format >> 16) & 0xff, (b->format >> 24) & 0xff,
                 vkp_modifier_name(b->modifier));
        } else if (!announced) {
            announced = 1;
            LOGI("client presents GPU frames: %dx%d %c%c%c%c %s, imported without a copy", b->width, b->height,
                 b->format & 0xff, (b->format >> 8) & 0xff, (b->format >> 16) & 0xff, (b->format >> 24) & 0xff,
                 vkp_modifier_name(b->modifier));
        }
    }
    if (s->shm_img) { vkp_image_destroy(s->shm_img); s->shm_img = NULL; }
    s->buf_w = b->width;
    s->buf_h = b->height;
    s->buf_alpha = (b->format & 0xff) == 'A';
    s->has_content = b->img != NULL;
}


static void map_toplevel(struct surface *s) {
    int w, h;
    if (s->mapped) return;
    s->mapped = 1;
    wl_list_insert(g_toplevels.prev, &s->toplevel_link);
    atomic_store(&g_has_toplevel, 1);
    surface_size(s, &w, &h);
    LOGI("toplevel mapped %dx%d", w, h);
    seat_focus();
}

static void unmap_toplevel(struct surface *s) {
    if (!s->mapped) return;
    s->mapped = 0;
    wl_list_remove(&s->toplevel_link);
    wl_list_init(&s->toplevel_link);
    atomic_store(&g_has_toplevel, !wl_list_empty(&g_toplevels));
    LOGI("toplevel unmapped");
    if (!g_stopping) seat_focus();
}

static void surface_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void surface_attach(struct wl_client *c, struct wl_resource *r, struct wl_resource *buffer, int32_t x, int32_t y) {
    struct surface *s = wl_resource_get_user_data(r);
    if (s->pending_buffer) wl_list_remove(&s->pending_buffer_destroy.link);
    wl_list_init(&s->pending_buffer_destroy.link);
    s->pending_buffer = buffer;
    s->pending_attach = 1;
    if (buffer) {
        s->pending_buffer_destroy.notify = on_pending_buffer_destroyed;
        wl_resource_add_destroy_listener(buffer, &s->pending_buffer_destroy);
    }
}
static void surface_damage(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {}
static void surface_frame(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct surface *s = wl_resource_get_user_data(r);
    struct wl_resource *cb = wl_resource_create(c, &wl_callback_interface, 1, id);
    if (!cb) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(cb, NULL, NULL, unlink_resource);
    wl_list_insert(s->pending_frames.prev, wl_resource_get_link(cb));
}
static void surface_set_opaque(struct wl_client *c, struct wl_resource *r, struct wl_resource *region) {
    struct surface *s = wl_resource_get_user_data(r);
    struct region *rg = region ? wl_resource_get_user_data(region) : NULL;
    memset(s->pending_opaque, 0, sizeof(s->pending_opaque));
    s->pending_opaque_set = 1;
    if (rg && rg->set && rg->exact) {
        s->pending_opaque[0] = rg->x;
        s->pending_opaque[1] = rg->y;
        s->pending_opaque[2] = rg->w;
        s->pending_opaque[3] = rg->h;
    }
}
static void surface_set_input(struct wl_client *c, struct wl_resource *r, struct wl_resource *region) {}

static void send_toplevel_configure(struct surface *s) {
    struct wl_array states;
    wl_array_init(&states);
    uint32_t *st = wl_array_add(&states, sizeof(uint32_t));
    if (st) *st = XDG_TOPLEVEL_STATE_ACTIVATED;
    st = wl_array_add(&states, sizeof(uint32_t));
    if (st) *st = XDG_TOPLEVEL_STATE_FULLSCREEN;
    xdg_toplevel_send_configure(s->xdg_toplevel, atomic_load(&g_output_w), atomic_load(&g_output_h), &states);
    wl_array_release(&states);
    xdg_surface_send_configure(s->xdg_surface, wl_display_next_serial(g_display));
}

static void surface_commit(struct wl_client *c, struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    struct surface *child;

    if (s->xdg_toplevel && s->xdg_surface && !s->configured) {
        s->configured = 1;
        send_toplevel_configure(s);
    }
    if (s->pending_src_set) {
        s->src_set = s->pending_src[2] > 0;
        memcpy(s->src, s->pending_src, sizeof(s->src));
        s->pending_src_set = 0;
    }
    if (s->pending_dst_set) {
        s->dst_set = s->pending_dst[0] > 0;
        memcpy(s->dst, s->pending_dst, sizeof(s->dst));
        s->pending_dst_set = 0;
    }
    if (s->pending_opaque_set) {
        memcpy(s->opaque, s->pending_opaque, sizeof(s->opaque));
        s->pending_opaque_set = 0;
    }
    if (s->pending_attach) {
        struct wl_resource *buffer = s->pending_buffer;
        struct dmabuf_buffer *db = dmabuf_from_resource(buffer);
        struct wl_shm_buffer *shm = buffer && !db ? wl_shm_buffer_get(buffer) : NULL;
        uint8_t pixel[4];

        feedback_discard_all(&s->feedback);
        if (buffer) {
            wl_list_remove(&s->pending_buffer_destroy.link);
            wl_list_init(&s->pending_buffer_destroy.link);
        }
        s->pending_buffer = NULL;
        s->pending_attach = 0;
        s->has_buffer = buffer != NULL;
        if (db) {
            take_dmabuf(s, db, buffer);
        } else {
            drop_dmabuf(s);
            if (shm) take_shm(s, shm, buffer);
            else if (buffer && single_pixel_buffer_get(buffer, pixel)) take_pixel(s, pixel, buffer);
            else {
                s->has_content = 0;
                if (buffer) wl_buffer_send_release(buffer);
            }
        }
    }
    wl_list_for_each(child, &s->children, child_link) {
        if (!child->sub_pending) continue;
        child->sub_x = child->sub_pending_x;
        child->sub_y = child->sub_pending_y;
        child->sub_pending = 0;
    }
    wl_list_insert_list(s->frames.prev, &s->pending_frames);
    wl_list_init(&s->pending_frames);
    wl_list_insert_list(s->feedback.prev, &s->pending_feedback);
    wl_list_init(&s->pending_feedback);
    constraints_surface_commit(s);

    if (s->role == ROLE_TOPLEVEL && s->xdg_toplevel) {
        if (s->has_buffer) map_toplevel(s);
        else unmap_toplevel(s);
    }
    schedule_render();
}
static void surface_set_buffer_transform(struct wl_client *c, struct wl_resource *r, int32_t t) {}
static void surface_set_buffer_scale(struct wl_client *c, struct wl_resource *r, int32_t scale) {}
static void surface_damage_buffer(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {}
static void surface_offset(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y) {}

static const struct wl_surface_interface surface_impl = {
    .destroy = surface_destroy,
    .attach = surface_attach,
    .damage = surface_damage,
    .frame = surface_frame,
    .set_opaque_region = surface_set_opaque,
    .set_input_region = surface_set_input,
    .commit = surface_commit,
    .set_buffer_transform = surface_set_buffer_transform,
    .set_buffer_scale = surface_set_buffer_scale,
    .damage_buffer = surface_damage_buffer,
    .offset = surface_offset,
};

static void detach_from_parent(struct surface *s) {
    if (!s->parent) return;
    wl_list_remove(&s->child_link);
    wl_list_init(&s->child_link);
    s->parent = NULL;
}

static void surface_resource_destroy(struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    struct surface *child, *tmp;
    struct wl_resource *cb, *cbtmp;

    for (int i = 0; i < g_nptrs; i++) if (g_ptrs[i].focus == r) g_ptrs[i].focus = NULL;
    for (int i = 0; i < g_nkbs; i++) if (g_kbs[i].focus == r) g_kbs[i].focus = NULL;
    for (int i = 0; i < g_ntouches; i++) if (g_touches[i].focus == r) g_touches[i].focus = NULL;
    touch_cancel_surface(s);
    if (g_cursor_surface == s) g_cursor_surface = NULL;
    constraints_surface_gone(s);
    unmap_toplevel(s);
    detach_from_parent(s);
    wl_list_for_each_safe(child, tmp, &s->children, child_link) {
        wl_list_remove(&child->child_link);
        wl_list_init(&child->child_link);
        child->parent = NULL;
    }
    if (s->pending_buffer) wl_list_remove(&s->pending_buffer_destroy.link);
    drop_dmabuf(s);
    vkp_image_destroy(s->shm_img);
    wl_resource_for_each_safe(cb, cbtmp, &s->pending_frames) wl_resource_destroy(cb);
    wl_resource_for_each_safe(cb, cbtmp, &s->frames) wl_resource_destroy(cb);
    feedback_discard_all(&s->pending_feedback);
    feedback_discard_all(&s->feedback);
    if (s->viewport) wl_resource_set_user_data(s->viewport, NULL);
    if (s->subsurface) wl_resource_set_user_data(s->subsurface, NULL);
    if (s->xdg_surface) wl_resource_set_user_data(s->xdg_surface, NULL);
    if (s->xdg_toplevel) wl_resource_set_user_data(s->xdg_toplevel, NULL);
    wl_list_remove(&s->link);
    free(s);
    schedule_render();
}


static void region_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void region_add(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {
    struct region *rg = wl_resource_get_user_data(r);
    if (!rg || w <= 0 || h <= 0) return;
    if (!rg->set) { rg->x = x; rg->y = y; rg->w = w; rg->h = h; rg->set = rg->exact = 1; return; }
    long long x2 = (long long)rg->x + rg->w, y2 = (long long)rg->y + rg->h;
    long long nx2 = (long long)x + w, ny2 = (long long)y + h;
    int inside = x >= rg->x && y >= rg->y && nx2 <= x2 && ny2 <= y2;
    int around = x <= rg->x && y <= rg->y && nx2 >= x2 && ny2 >= y2;
    if (!inside && !around) rg->exact = 0;
    if (nx2 > x2) x2 = nx2;
    if (ny2 > y2) y2 = ny2;
    if (x < rg->x) rg->x = x;
    if (y < rg->y) rg->y = y;
    rg->w = (int)(x2 - rg->x > INT32_MAX ? INT32_MAX : x2 - rg->x);
    rg->h = (int)(y2 - rg->y > INT32_MAX ? INT32_MAX : y2 - rg->y);
}
static void region_subtract(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {
    struct region *rg = wl_resource_get_user_data(r);
    if (rg && w > 0 && h > 0) rg->exact = 0;
}
static void region_resource_destroy(struct wl_resource *r) { free(wl_resource_get_user_data(r)); }
static const struct wl_region_interface region_impl = {
    .destroy = region_destroy,
    .add = region_add,
    .subtract = region_subtract,
};


static void compositor_create_surface(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct surface *s = calloc(1, sizeof(*s));
    if (!s) { wl_client_post_no_memory(c); return; }
    s->resource = wl_resource_create(c, &wl_surface_interface, wl_resource_get_version(r), id);
    if (!s->resource) { free(s); wl_client_post_no_memory(c); return; }
    wl_list_init(&s->pending_frames);
    wl_list_init(&s->frames);
    wl_list_init(&s->pending_feedback);
    wl_list_init(&s->feedback);
    wl_list_init(&s->children);
    wl_list_init(&s->child_link);
    wl_list_init(&s->toplevel_link);
    wl_list_init(&s->pending_buffer_destroy.link);
    wl_list_insert(&g_surfaces, &s->link);
    wl_resource_set_implementation(s->resource, &surface_impl, s, surface_resource_destroy);
}
static void compositor_create_region(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct region *rg = calloc(1, sizeof(*rg));
    struct wl_resource *reg = rg ? wl_resource_create(c, &wl_region_interface, 1, id) : NULL;
    if (!reg) { free(rg); wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(reg, &region_impl, rg, region_resource_destroy);
}
static const struct wl_compositor_interface compositor_impl = {
    .create_surface = compositor_create_surface,
    .create_region = compositor_create_region,
};
static void bind_compositor(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_compositor_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &compositor_impl, NULL, NULL);
}


static void subsurface_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void subsurface_set_position(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    s->sub_pending_x = x;
    s->sub_pending_y = y;
    s->sub_pending = 1;
}
static void restack_child(struct surface *s, struct surface *sibling, int above) {
    if (!s || !s->parent || !sibling) return;
    if (sibling == s->parent) {
        s->below_parent = !above;
        wl_list_remove(&s->child_link);
        if (above) wl_list_insert(&s->parent->children, &s->child_link);
        else wl_list_insert(s->parent->children.prev, &s->child_link);
    } else if (sibling->parent == s->parent) {
        s->below_parent = sibling->below_parent;
        wl_list_remove(&s->child_link);
        if (above) wl_list_insert(&sibling->child_link, &s->child_link);
        else wl_list_insert(sibling->child_link.prev, &s->child_link);
    }
    schedule_render();
}
static void subsurface_place_above(struct wl_client *c, struct wl_resource *r, struct wl_resource *sibling) {
    restack_child(wl_resource_get_user_data(r), wl_resource_get_user_data(sibling), 1);
}
static void subsurface_place_below(struct wl_client *c, struct wl_resource *r, struct wl_resource *sibling) {
    restack_child(wl_resource_get_user_data(r), wl_resource_get_user_data(sibling), 0);
}
static void subsurface_set_sync(struct wl_client *c, struct wl_resource *r) {}
static void subsurface_set_desync(struct wl_client *c, struct wl_resource *r) {}
static const struct wl_subsurface_interface subsurface_impl = {
    .destroy = subsurface_destroy,
    .set_position = subsurface_set_position,
    .place_above = subsurface_place_above,
    .place_below = subsurface_place_below,
    .set_sync = subsurface_set_sync,
    .set_desync = subsurface_set_desync,
};
static void subsurface_resource_destroy(struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    detach_from_parent(s);
    s->subsurface = NULL;
    s->role = ROLE_NONE;
    schedule_render();
}

static void subcompositor_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void subcompositor_get_subsurface(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                         struct wl_resource *surface, struct wl_resource *parent) {
    struct surface *s = wl_resource_get_user_data(surface);
    struct surface *p = wl_resource_get_user_data(parent);
    struct wl_resource *sub = wl_resource_create(c, &wl_subsurface_interface, wl_resource_get_version(r), id);
    if (!sub) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(sub, &subsurface_impl, s, subsurface_resource_destroy);
    if (!s || !p || s == p) return;
    detach_from_parent(s);
    s->role = ROLE_SUBSURFACE;
    s->subsurface = sub;
    s->parent = p;
    s->below_parent = 0;
    s->sub_x = s->sub_y = 0;
    wl_list_insert(p->children.prev, &s->child_link);
}
static const struct wl_subcompositor_interface subcompositor_impl = {
    .destroy = subcompositor_destroy,
    .get_subsurface = subcompositor_get_subsurface,
};
static void bind_subcompositor(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_subcompositor_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &subcompositor_impl, NULL, NULL);
}


static void viewport_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void viewport_set_source(struct wl_client *c, struct wl_resource *r, wl_fixed_t x, wl_fixed_t y,
                                wl_fixed_t w, wl_fixed_t h) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    s->pending_src[0] = (float)wl_fixed_to_double(x);
    s->pending_src[1] = (float)wl_fixed_to_double(y);
    s->pending_src[2] = w == wl_fixed_from_int(-1) ? -1.0f : (float)wl_fixed_to_double(w);
    s->pending_src[3] = h == wl_fixed_from_int(-1) ? -1.0f : (float)wl_fixed_to_double(h);
    s->pending_src_set = 1;
}
static void viewport_set_destination(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    s->pending_dst[0] = w;
    s->pending_dst[1] = h;
    s->pending_dst_set = 1;
}
static const struct wp_viewport_interface viewport_impl = {
    .destroy = viewport_destroy,
    .set_source = viewport_set_source,
    .set_destination = viewport_set_destination,
};
static void viewport_resource_destroy(struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    s->viewport = NULL;
    s->pending_src[2] = -1.0f; s->pending_src_set = 1;
    s->pending_dst[0] = -1; s->pending_dst_set = 1;
}

static void viewporter_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void viewporter_get_viewport(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *surface) {
    struct surface *s = wl_resource_get_user_data(surface);
    struct wl_resource *vp = wl_resource_create(c, &wp_viewport_interface, wl_resource_get_version(r), id);
    if (!vp) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(vp, &viewport_impl, s, viewport_resource_destroy);
    if (s) s->viewport = vp;
}
static const struct wp_viewporter_interface viewporter_impl = {
    .destroy = viewporter_destroy,
    .get_viewport = viewporter_get_viewport,
};
static void bind_viewporter(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wp_viewporter_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &viewporter_impl, NULL, NULL);
}


static void xdg_toplevel_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void xdg_toplevel_set_parent(struct wl_client *c, struct wl_resource *r, struct wl_resource *p) {}
static void xdg_toplevel_set_title(struct wl_client *c, struct wl_resource *r, const char *title) {}
static void xdg_toplevel_set_app_id(struct wl_client *c, struct wl_resource *r, const char *id) {
    LOGI("toplevel app id: %s", id ? id : "(none)");
}
static void xdg_toplevel_show_menu(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat,
                                   uint32_t serial, int32_t x, int32_t y) {}
static void xdg_toplevel_move(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat, uint32_t serial) {}
static void xdg_toplevel_resize(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat,
                                uint32_t serial, uint32_t edges) {}
static void xdg_toplevel_set_size(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {}
static void xdg_toplevel_reconfigure(struct wl_client *c, struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    if (s && s->xdg_surface && s->configured) send_toplevel_configure(s);
}
static void xdg_toplevel_set_fullscreen(struct wl_client *c, struct wl_resource *r, struct wl_resource *output) {
    xdg_toplevel_reconfigure(c, r);
}
static void xdg_toplevel_set_minimized(struct wl_client *c, struct wl_resource *r) {}
static const struct xdg_toplevel_interface xdg_toplevel_impl = {
    .destroy = xdg_toplevel_destroy_req,
    .set_parent = xdg_toplevel_set_parent,
    .set_title = xdg_toplevel_set_title,
    .set_app_id = xdg_toplevel_set_app_id,
    .show_window_menu = xdg_toplevel_show_menu,
    .move = xdg_toplevel_move,
    .resize = xdg_toplevel_resize,
    .set_max_size = xdg_toplevel_set_size,
    .set_min_size = xdg_toplevel_set_size,
    .set_maximized = xdg_toplevel_reconfigure,
    .unset_maximized = xdg_toplevel_reconfigure,
    .set_fullscreen = xdg_toplevel_set_fullscreen,
    .unset_fullscreen = xdg_toplevel_reconfigure,
    .set_minimized = xdg_toplevel_set_minimized,
};
static void xdg_toplevel_resource_destroy(struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    s->xdg_toplevel = NULL;
    s->configured = 0;
    unmap_toplevel(s);
    schedule_render();
}

static void xdg_surface_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void xdg_surface_get_toplevel(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct surface *s = wl_resource_get_user_data(r);
    struct wl_resource *tl = wl_resource_create(c, &xdg_toplevel_interface, wl_resource_get_version(r), id);
    if (!tl) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(tl, &xdg_toplevel_impl, s, xdg_toplevel_resource_destroy);
    if (!s) return;
    s->role = ROLE_TOPLEVEL;
    s->xdg_toplevel = tl;
}
static void popup_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void popup_grab(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat, uint32_t serial) {}
static void popup_reposition(struct wl_client *c, struct wl_resource *r, struct wl_resource *positioner, uint32_t token) {}
static const struct xdg_popup_interface popup_impl = {
    .destroy = popup_destroy,
    .grab = popup_grab,
    .reposition = popup_reposition,
};
static void xdg_surface_get_popup(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                  struct wl_resource *parent, struct wl_resource *positioner) {
    struct wl_resource *p = wl_resource_create(c, &xdg_popup_interface, wl_resource_get_version(r), id);
    if (!p) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(p, &popup_impl, NULL, NULL);
    xdg_popup_send_popup_done(p);
}
static void xdg_surface_set_geometry(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {}
static void xdg_surface_ack_configure(struct wl_client *c, struct wl_resource *r, uint32_t serial) {}
static const struct xdg_surface_interface xdg_surface_impl = {
    .destroy = xdg_surface_destroy_req,
    .get_toplevel = xdg_surface_get_toplevel,
    .get_popup = xdg_surface_get_popup,
    .set_window_geometry = xdg_surface_set_geometry,
    .ack_configure = xdg_surface_ack_configure,
};
static void xdg_surface_resource_destroy(struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    if (s) s->xdg_surface = NULL;
}

static void positioner_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void positioner_set_i32x2(struct wl_client *c, struct wl_resource *r, int32_t a, int32_t b) {}
static void positioner_set_rect(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {}
static void positioner_set_u32(struct wl_client *c, struct wl_resource *r, uint32_t v) {}
static void positioner_set_reactive(struct wl_client *c, struct wl_resource *r) {}
static const struct xdg_positioner_interface positioner_impl = {
    .destroy = positioner_destroy,
    .set_size = positioner_set_i32x2,
    .set_anchor_rect = positioner_set_rect,
    .set_anchor = positioner_set_u32,
    .set_gravity = positioner_set_u32,
    .set_constraint_adjustment = positioner_set_u32,
    .set_offset = positioner_set_i32x2,
    .set_reactive = positioner_set_reactive,
    .set_parent_size = positioner_set_i32x2,
    .set_parent_configure = positioner_set_u32,
};

static void xdg_wm_base_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void xdg_wm_base_create_positioner(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *p = wl_resource_create(c, &xdg_positioner_interface, wl_resource_get_version(r), id);
    if (!p) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(p, &positioner_impl, NULL, NULL);
}
static void xdg_wm_base_get_xdg_surface(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *surf) {
    struct surface *s = wl_resource_get_user_data(surf);
    struct wl_resource *xs = wl_resource_create(c, &xdg_surface_interface, wl_resource_get_version(r), id);
    if (!xs) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(xs, &xdg_surface_impl, s, xdg_surface_resource_destroy);
    if (s) s->xdg_surface = xs;
}
static void xdg_wm_base_pong(struct wl_client *c, struct wl_resource *r, uint32_t serial) {}
static const struct xdg_wm_base_interface xdg_wm_base_impl = {
    .destroy = xdg_wm_base_destroy,
    .create_positioner = xdg_wm_base_create_positioner,
    .get_xdg_surface = xdg_wm_base_get_xdg_surface,
    .pong = xdg_wm_base_pong,
};
static void bind_xdg_wm_base(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &xdg_wm_base_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &xdg_wm_base_impl, NULL, NULL);
}


static void output_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_output_interface output_impl = { .release = output_release };

static void output_send_mode(struct wl_resource *r) {
    wl_output_send_mode(r, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED, atomic_load(&g_output_w),
                        atomic_load(&g_output_h), g_refresh_mhz);
    if (wl_resource_get_version(r) >= WL_OUTPUT_DONE_SINCE_VERSION) wl_output_send_done(r);
}

static void bind_output(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_output_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &output_impl, NULL, unlink_resource);
    wl_list_insert(&g_outputs, wl_resource_get_link(r));
    wl_output_send_geometry(r, 0, 0, 0, 0, WL_OUTPUT_SUBPIXEL_UNKNOWN, "DDO Mobile", "Android", WL_OUTPUT_TRANSFORM_NORMAL);
    if (ver >= WL_OUTPUT_SCALE_SINCE_VERSION) wl_output_send_scale(r, 1);
    if (ver >= WL_OUTPUT_NAME_SINCE_VERSION) {
        wl_output_send_name(r, "DDO-1");
        wl_output_send_description(r, "DDO Mobile screen");
    }
    output_send_mode(r);
}

static void outputs_changed(void) {
    struct wl_resource *r;
    struct surface *s;
    wl_resource_for_each(r, &g_outputs) output_send_mode(r);
    wl_list_for_each(s, &g_surfaces, link)
        if (s->xdg_toplevel && s->xdg_surface && s->configured) send_toplevel_configure(s);
    wl_display_flush_clients(g_display);
}


struct draw_list { struct vkp_draw *d; int n, cap; };

static struct vkp_image *surface_image(const struct surface *s) {
    if (!s->has_content) return NULL;
    if (s->shm_img) return s->shm_img;
    return s->dmabuf_buf ? s->dmabuf_buf->img : NULL;
}

static int surface_translucent(const struct surface *s, int w, int h) {
    const int *o = s->opaque;
    if (!s->buf_alpha) return 0;
    return !(o[2] > 0 && o[0] <= 0 && o[1] <= 0 && (long long)o[0] + o[2] >= w && (long long)o[1] + o[3] >= h);
}

static void add_draw(struct draw_list *dl, struct surface *s, int ox, int oy, int blend) {
    struct vkp_image *img = surface_image(s);
    float sx = 0, sy = 0, sw = (float)s->buf_w, sh = (float)s->buf_h;
    int dw, dh;
    if (!img) return;
    if (s->src_set) { sx = s->src[0]; sy = s->src[1]; sw = s->src[2]; sh = s->src[3]; }
    surface_size(s, &dw, &dh);
    if (dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0) return;
    if (dl->n == dl->cap) {
        int cap = dl->cap ? dl->cap * 2 : 16;
        struct vkp_draw *d = realloc(dl->d, (size_t)cap * sizeof(*d));
        if (!d) return;
        dl->d = d;
        dl->cap = cap;
    }
    dl->d[dl->n++] = (struct vkp_draw){img, sx, sy, sw, sh, ox, oy, dw, dh, blend || surface_translucent(s, dw, dh)};
    s->drawn = 1;
}

static void add_tree(struct draw_list *dl, struct surface *s, int ox, int oy, int depth) {
    struct surface *c;
    if (depth > 8) return;
    wl_list_for_each(c, &s->children, child_link)
        if (c->below_parent) add_tree(dl, c, ox + c->sub_x, oy + c->sub_y, depth + 1);
    add_draw(dl, s, ox, oy, 0);
    wl_list_for_each(c, &s->children, child_link)
        if (!c->below_parent) add_tree(dl, c, ox + c->sub_x, oy + c->sub_y, depth + 1);
}

void compositor_set_cursor_shown(int shown) { atomic_store(&g_cursor_shown, shown); }

static int cursor_visible(void) {
    if (!atomic_load(&g_cursor_shown)) return 0;
    if (!g_cursor_surface || !g_cursor_surface->has_content) return 0;
    if (g_active_constraint && g_active_constraint->is_lock) return 0;
    for (int i = 0; i < g_nptrs; i++) if (g_ptrs[i].focus) return 1;
    return 0;
}

static void fire_all_frames(void) {
    struct surface *s;
    wl_list_for_each(s, &g_surfaces, link) fire_frames(&s->frames);
}

static void pace_without_output(void) {
    struct surface *s;
    wl_list_for_each(s, &g_surfaces, link) {
        fire_frames(&s->frames);
        feedback_discard_all(&s->feedback);
    }
    wl_display_flush_clients(g_display);
}

static int on_frame_timer(void *data) {
    pace_without_output();
    if (vkp_has_window()) schedule_render();
    else wl_event_source_timer_update(g_frame_timer, 16);
    return 0;
}

static int64_t presented_ns(int64_t render_ns) {
    const int64_t refresh = refresh_ns();
    if (!g_tick_ns || render_ns - g_tick_ns > refresh * 2 || render_ns < g_tick_ns) return render_ns;
    return g_tick_ns;
}

static void render_scene(void) {
    struct draw_list dl = {0};
    struct surface *s, *top = top_toplevel();
    int w, h;
    const int64_t t = now_ns();

    g_dirty = 0;
    g_rendered_tick_ns = g_tick_ns;
    wl_list_for_each(s, &g_surfaces, link) s->drawn = 0;
    scene_size(&w, &h);
    if (top) add_tree(&dl, top, 0, 0, 0);
    if (top && cursor_visible()) add_draw(&dl, g_cursor_surface, (int)g_ptr_x - g_cursor_hx, (int)g_ptr_y - g_cursor_hy, 1);
    if (vkp_render(w, h, dl.d, dl.n) == 0) {
        const int64_t shown = presented_ns(t);
        g_frames++;
        g_stat_frames++;
        wl_list_for_each(s, &g_surfaces, link) {
            if (s->drawn) feedback_present_all(&s->feedback, shown);
            else feedback_discard_all(&s->feedback);
        }
        fire_all_frames();
    } else {
        pace_without_output();
        if (!g_frame_timer)
            g_frame_timer = wl_event_loop_add_timer(wl_display_get_event_loop(g_display), on_frame_timer, NULL);
        if (g_frame_timer) wl_event_source_timer_update(g_frame_timer, 16);
    }
    free(dl.d);
    wl_display_flush_clients(g_display);
}

static void on_render_idle(void *data) {
    g_render_idle = NULL;
    if (g_dirty && g_rendered_tick_ns != g_tick_ns) render_scene();
}

static int on_fallback_timer(void *data) {
    g_fallback_armed = 0;
    if (g_dirty) render_scene();
    return 0;
}

/* One scene per screen refresh: a commit is drawn at once unless this refresh already showed a frame. */
static void schedule_render(void) {
    g_dirty = 1;
    if (!g_display || g_stopping) return;
    struct wl_event_loop *loop = wl_display_get_event_loop(g_display);
    if (g_last_vsync_ns && now_ns() - g_last_vsync_ns < VSYNC_FRESH_NS) {
        if (!g_render_idle && g_rendered_tick_ns != g_tick_ns)
            g_render_idle = wl_event_loop_add_idle(loop, on_render_idle, NULL);
        return;
    }
    if (g_fallback_armed) return;
    if (!g_fallback_timer) g_fallback_timer = wl_event_loop_add_timer(loop, on_fallback_timer, NULL);
    if (!g_fallback_timer) return;
    g_fallback_armed = 1;
    wl_event_source_timer_update(g_fallback_timer, 8);
}

static void on_vsync(int64_t frame_ns) {
    if (g_tick_ns && frame_ns > g_tick_ns) {
        const int64_t d = frame_ns - g_tick_ns;
        if (d > 3000000LL && d < 50000000LL && (!g_min_interval_ns || d < g_min_interval_ns)) g_min_interval_ns = d;
    }
    g_last_vsync_ns = now_ns();
    g_tick_ns = frame_ns;
    if (vkp_apply_window_request()) g_dirty = 1;
    if (g_dirty) render_scene();
}


static struct seat_resource *pointer_for(struct wl_client *client) {
    for (int i = 0; i < g_nptrs; i++)
        if (wl_resource_get_client(g_ptrs[i].res) == client) return &g_ptrs[i];
    return NULL;
}
static struct seat_resource *keyboard_for(struct wl_client *client) {
    for (int i = 0; i < g_nkbs; i++)
        if (wl_resource_get_client(g_kbs[i].res) == client) return &g_kbs[i];
    return NULL;
}

#define for_each_seat_resource_of(arr, n, client, sr) \
    for (int _i = 0; _i < (n); _i++) \
        if (((sr) = &(arr)[_i]), wl_resource_get_client((sr)->res) == (client))

static void send_pointer_frame(struct wl_resource *ptr) {
    if (wl_resource_get_version(ptr) >= WL_POINTER_FRAME_SINCE_VERSION) wl_pointer_send_frame(ptr);
}

static void pointer_focus(struct wl_resource *target, wl_fixed_t fx, wl_fixed_t fy) {
    struct wl_client *client = target ? wl_resource_get_client(target) : NULL;
    struct seat_resource *sp;
    int entered = 0;
    for (int i = 0; i < g_nptrs; i++) {
        if (!g_ptrs[i].focus || g_ptrs[i].focus == target) continue;
        wl_pointer_send_leave(g_ptrs[i].res, wl_display_next_serial(g_display), g_ptrs[i].focus);
        send_pointer_frame(g_ptrs[i].res);
        g_ptrs[i].focus = NULL;
    }
    if (!target) return;
    for_each_seat_resource_of(g_ptrs, g_nptrs, client, sp) {
        if (sp->focus == target) continue;
        sp->focus = target;
        wl_pointer_send_enter(sp->res, wl_display_next_serial(g_display), target, fx, fy);
        send_pointer_frame(sp->res);
        entered = 1;
    }
    if (entered) constraints_focus_entered(target, client);
}

static void keyboard_focus(struct wl_resource *target) {
    struct wl_client *client = target ? wl_resource_get_client(target) : NULL;
    struct seat_resource *sk;
    for (int i = 0; i < g_nkbs; i++) {
        if (!g_kbs[i].focus || g_kbs[i].focus == target) continue;
        wl_keyboard_send_leave(g_kbs[i].res, wl_display_next_serial(g_display), g_kbs[i].focus);
        g_kbs[i].focus = NULL;
    }
    if (!target) return;
    struct wl_array keys;
    wl_array_init(&keys);
    for_each_seat_resource_of(g_kbs, g_nkbs, client, sk) {
        if (sk->focus == target) continue;
        sk->focus = target;
        wl_keyboard_send_enter(sk->res, wl_display_next_serial(g_display), target, &keys);
        wl_keyboard_send_modifiers(sk->res, wl_display_next_serial(g_display), g_mods_depressed, 0, g_mods_locked, 0);
    }
    wl_array_release(&keys);
}

static void seat_focus(void) {
    struct surface *top = top_toplevel();
    if (top && !g_ptr_placed) {
        int w, h;
        surface_size(top, &w, &h);
        g_ptr_x = w / 2.0;
        g_ptr_y = h / 2.0;
        g_ptr_placed = 1;
    }
    pointer_focus(top ? top->resource : NULL, wl_fixed_from_double(g_ptr_x), wl_fixed_from_double(g_ptr_y));
    keyboard_focus(top ? top->resource : NULL);
    wl_display_flush_clients(g_display);
}

static void pointer_set_cursor(struct wl_client *c, struct wl_resource *r, uint32_t serial,
                               struct wl_resource *surface, int32_t hx, int32_t hy) {
    struct surface *s = surface ? wl_resource_get_user_data(surface) : NULL;
    if (s && s->role != ROLE_NONE && s->role != ROLE_CURSOR) {
        wl_resource_post_error(r, WL_POINTER_ERROR_ROLE, "the surface already has another role");
        return;
    }
    if (s) s->role = ROLE_CURSOR;
    g_cursor_surface = s;
    g_cursor_hx = hx;
    g_cursor_hy = hy;
    schedule_render();
}
static void pointer_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_pointer_interface pointer_impl = { .set_cursor = pointer_set_cursor, .release = pointer_release };

static void seat_resource_remove(struct seat_resource *arr, int *n, struct wl_resource *r) {
    for (int i = 0; i < *n; i++)
        if (arr[i].res == r) { arr[i] = arr[--*n]; break; }
}
static void pointer_res_destroy(struct wl_resource *r) {
    constraints_pointer_gone(r);
    relative_pointers_pointer_gone(r);
    seat_resource_remove(g_ptrs, &g_nptrs, r);
}

static void keyboard_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_keyboard_interface keyboard_impl = { .release = keyboard_release };
static void keyboard_res_destroy(struct wl_resource *r) { seat_resource_remove(g_kbs, &g_nkbs, r); }

static void touch_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_touch_interface touch_impl = { .release = touch_release };
static void touch_res_destroy(struct wl_resource *r) { seat_resource_remove(g_touches, &g_ntouches, r); }

static void seat_get_pointer(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *p = wl_resource_create(c, &wl_pointer_interface, wl_resource_get_version(r), id);
    if (!p) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(p, &pointer_impl, NULL, pointer_res_destroy);
    if (g_nptrs < MAX_SEAT_RESOURCES) g_ptrs[g_nptrs++] = (struct seat_resource){p, NULL};
    seat_focus();
}
static void seat_get_keyboard(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *k = wl_resource_create(c, &wl_keyboard_interface, wl_resource_get_version(r), id);
    if (!k) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(k, &keyboard_impl, NULL, keyboard_res_destroy);
    if (g_keymap_fd >= 0) wl_keyboard_send_keymap(k, WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1, g_keymap_fd, g_keymap_size);
    if (wl_resource_get_version(k) >= WL_KEYBOARD_REPEAT_INFO_SINCE_VERSION) wl_keyboard_send_repeat_info(k, 25, 500);
    if (g_nkbs < MAX_SEAT_RESOURCES) g_kbs[g_nkbs++] = (struct seat_resource){k, NULL};
    seat_focus();
}
static void seat_get_touch(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *t = wl_resource_create(c, &wl_touch_interface, wl_resource_get_version(r), id);
    if (!t) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(t, &touch_impl, NULL, touch_res_destroy);
    if (g_ntouches < MAX_SEAT_RESOURCES) g_touches[g_ntouches++] = (struct seat_resource){t, NULL};
}
static void seat_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_seat_interface seat_impl = {
    .get_pointer = seat_get_pointer, .get_keyboard = seat_get_keyboard,
    .get_touch = seat_get_touch, .release = seat_release,
};
static void bind_seat(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_seat_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &seat_impl, NULL, NULL);
    wl_seat_send_capabilities(r, WL_SEAT_CAPABILITY_POINTER | WL_SEAT_CAPABILITY_KEYBOARD | WL_SEAT_CAPABILITY_TOUCH);
    if (ver >= WL_SEAT_NAME_SINCE_VERSION) wl_seat_send_name(r, "ddo-seat");
}


static void confine_clamp(const struct constraint *k, double *x, double *y) {
    int w, h, rx = 0, ry = 0, rw, rh;
    if (!k->surface) return;
    surface_size(k->surface, &w, &h);
    rw = w; rh = h;
    if (k->region.set) {
        int x2 = rx + rw < k->region.x + k->region.w ? rx + rw : k->region.x + k->region.w;
        int y2 = ry + rh < k->region.y + k->region.h ? ry + rh : k->region.y + k->region.h;
        if (k->region.x > rx) rx = k->region.x;
        if (k->region.y > ry) ry = k->region.y;
        rw = x2 - rx; rh = y2 - ry;
    }
    if (rw <= 0 || rh <= 0) return;
    if (*x < rx) *x = rx;
    if (*x > rx + rw - 1) *x = rx + rw - 1;
    if (*y < ry) *y = ry;
    if (*y > ry + rh - 1) *y = ry + rh - 1;
}

static void constraint_send_state(struct constraint *k, int on) {
    if (k->is_lock) {
        if (on) zwp_locked_pointer_v1_send_locked(k->resource);
        else zwp_locked_pointer_v1_send_unlocked(k->resource);
    } else {
        if (on) zwp_confined_pointer_v1_send_confined(k->resource);
        else zwp_confined_pointer_v1_send_unconfined(k->resource);
    }
}

static void constraint_end(struct constraint *k, int tell_client) {
    if (!k->active) return;
    k->active = 0;
    if (g_active_constraint == k) g_active_constraint = NULL;
    if (k->lifetime == ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_ONESHOT) k->defunct = 1;
    if (k->is_lock && k->hint_set && k->surface) {
        g_ptr_x = k->hint_x;
        g_ptr_y = k->hint_y;
    }
    g_raw_valid = 0;
    if (tell_client) constraint_send_state(k, 0);
    LOGI("pointer %s", k->is_lock ? "unlocked" : "unconfined");
    schedule_render();
}

static void constraint_activate(struct constraint *k) {
    if (k->active || k->defunct || !k->surface || !k->pointer) return;
    if (g_active_constraint && g_active_constraint != k) constraint_end(g_active_constraint, 1);
    k->active = 1;
    g_active_constraint = k;
    if (!k->is_lock) confine_clamp(k, &g_ptr_x, &g_ptr_y);
    pointer_focus(k->surface->resource, wl_fixed_from_double(g_ptr_x), wl_fixed_from_double(g_ptr_y));
    constraint_send_state(k, 1);
    LOGI("pointer %s (%s)", k->is_lock ? "locked" : "confined",
         k->lifetime == ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_ONESHOT ? "oneshot" : "persistent");
    schedule_render();
    wl_display_flush_clients(g_display);
}

static void constraints_focus_entered(struct wl_resource *target, struct wl_client *client) {
    struct constraint *k;
    if (g_active_constraint) return;
    wl_list_for_each(k, &g_constraints, link) {
        if (k->active || k->defunct || !k->surface || !k->pointer) continue;
        if (k->surface->resource != target || wl_resource_get_client(k->pointer) != client) continue;
        constraint_activate(k);
        return;
    }
}

static void constraints_surface_gone(struct surface *s) {
    struct constraint *k;
    wl_list_for_each(k, &g_constraints, link) {
        if (k->surface != s) continue;
        constraint_end(k, 1);
        k->surface = NULL;
        k->defunct = 1;
    }
}

static void constraints_pointer_gone(struct wl_resource *pointer) {
    struct constraint *k;
    wl_list_for_each(k, &g_constraints, link) {
        if (k->pointer != pointer) continue;
        constraint_end(k, 1);
        k->pointer = NULL;
        k->defunct = 1;
    }
}

static void constraints_surface_commit(struct surface *s) {
    struct constraint *k;
    wl_list_for_each(k, &g_constraints, link) {
        if (k->surface != s) continue;
        if (k->pending_hint_set) {
            k->hint_set = 1;
            k->hint_x = k->pending_hint_x;
            k->hint_y = k->pending_hint_y;
            k->pending_hint_set = 0;
            if (k->active && k->is_lock) { g_ptr_x = k->hint_x; g_ptr_y = k->hint_y; }
        }
        if (k->pending_region_set) {
            k->region = k->pending_region;
            k->pending_region_set = 0;
            if (k->active && !k->is_lock) confine_clamp(k, &g_ptr_x, &g_ptr_y);
        }
    }
}

static void constraint_resource_destroy(struct wl_resource *r) {
    struct constraint *k = wl_resource_get_user_data(r);
    if (!k) return;
    constraint_end(k, 0);
    wl_list_remove(&k->link);
    free(k);
}

static void constraint_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void locked_pointer_set_hint(struct wl_client *c, struct wl_resource *r, wl_fixed_t x, wl_fixed_t y) {
    struct constraint *k = wl_resource_get_user_data(r);
    if (!k) return;
    k->pending_hint_set = 1;
    k->pending_hint_x = wl_fixed_to_double(x);
    k->pending_hint_y = wl_fixed_to_double(y);
}
static void locked_pointer_set_region(struct wl_client *c, struct wl_resource *r, struct wl_resource *region) {}
static const struct zwp_locked_pointer_v1_interface locked_pointer_impl = {
    .destroy = constraint_destroy_req,
    .set_cursor_position_hint = locked_pointer_set_hint,
    .set_region = locked_pointer_set_region,
};
static void confined_pointer_set_region(struct wl_client *c, struct wl_resource *r, struct wl_resource *region) {
    struct constraint *k = wl_resource_get_user_data(r);
    struct region *rg = region ? wl_resource_get_user_data(region) : NULL;
    if (!k) return;
    k->pending_region_set = 1;
    if (rg) k->pending_region = *rg;
    else memset(&k->pending_region, 0, sizeof(k->pending_region));
}
static const struct zwp_confined_pointer_v1_interface confined_pointer_impl = {
    .destroy = constraint_destroy_req,
    .set_region = confined_pointer_set_region,
};

static void constraint_create(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *surface_res,
                              struct wl_resource *pointer_res, struct wl_resource *region_res, uint32_t lifetime,
                              int is_lock) {
    struct surface *s = wl_resource_get_user_data(surface_res);
    struct constraint *k;
    wl_list_for_each(k, &g_constraints, link) {
        if (k->defunct || k->surface != s || k->pointer != pointer_res) continue;
        wl_resource_post_error(r, ZWP_POINTER_CONSTRAINTS_V1_ERROR_ALREADY_CONSTRAINED,
                               "the surface already has a pointer constraint for this pointer");
        return;
    }
    struct wl_resource *res = wl_resource_create(c, is_lock ? &zwp_locked_pointer_v1_interface
                                                            : &zwp_confined_pointer_v1_interface,
                                                 wl_resource_get_version(r), id);
    if (!res || !(k = calloc(1, sizeof(*k)))) {
        if (res) wl_resource_destroy(res);
        wl_client_post_no_memory(c);
        return;
    }
    k->resource = res;
    k->pointer = pointer_res;
    k->surface = s;
    k->is_lock = is_lock;
    k->lifetime = lifetime == ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_PERSISTENT
                  ? ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_PERSISTENT : ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_ONESHOT;
    if (!is_lock && region_res) {
        struct region *rg = wl_resource_get_user_data(region_res);
        if (rg) k->region = *rg;
    }
    wl_resource_set_implementation(res, is_lock ? (const void *)&locked_pointer_impl : (const void *)&confined_pointer_impl,
                                   k, constraint_resource_destroy);
    wl_list_insert(g_constraints.prev, &k->link);
    if (!s) { k->defunct = 1; return; }
    constraint_activate(k);
}

static void pointer_constraints_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void pointer_constraints_lock(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *surface,
                                     struct wl_resource *pointer, struct wl_resource *region, uint32_t lifetime) {
    constraint_create(c, r, id, surface, pointer, region, lifetime, 1);
}
static void pointer_constraints_confine(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *surface,
                                        struct wl_resource *pointer, struct wl_resource *region, uint32_t lifetime) {
    constraint_create(c, r, id, surface, pointer, region, lifetime, 0);
}
static const struct zwp_pointer_constraints_v1_interface pointer_constraints_impl = {
    .destroy = pointer_constraints_destroy,
    .lock_pointer = pointer_constraints_lock,
    .confine_pointer = pointer_constraints_confine,
};
static void bind_pointer_constraints(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &zwp_pointer_constraints_v1_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &pointer_constraints_impl, NULL, NULL);
}


static void relative_pointer_resource_destroy(struct wl_resource *r) {
    struct relative_pointer *rp = wl_resource_get_user_data(r);
    if (!rp) return;
    wl_list_remove(&rp->link);
    free(rp);
}
static void relative_pointer_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct zwp_relative_pointer_v1_interface relative_pointer_impl = { .destroy = relative_pointer_destroy_req };

static void relative_pointer_manager_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void relative_pointer_manager_get(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *pointer) {
    struct wl_resource *res = wl_resource_create(c, &zwp_relative_pointer_v1_interface, wl_resource_get_version(r), id);
    struct relative_pointer *rp;
    if (!res || !(rp = calloc(1, sizeof(*rp)))) {
        if (res) wl_resource_destroy(res);
        wl_client_post_no_memory(c);
        return;
    }
    rp->resource = res;
    rp->pointer = pointer;
    wl_resource_set_implementation(res, &relative_pointer_impl, rp, relative_pointer_resource_destroy);
    wl_list_insert(g_relative_pointers.prev, &rp->link);
}
static const struct zwp_relative_pointer_manager_v1_interface relative_pointer_manager_impl = {
    .destroy = relative_pointer_manager_destroy,
    .get_relative_pointer = relative_pointer_manager_get,
};
static void bind_relative_pointer_manager(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &zwp_relative_pointer_manager_v1_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &relative_pointer_manager_impl, NULL, NULL);
}

static void relative_pointers_pointer_gone(struct wl_resource *pointer) {
    struct relative_pointer *rp;
    wl_list_for_each(rp, &g_relative_pointers, link)
        if (rp->pointer == pointer) rp->pointer = NULL;
}

static void send_relative_motion(struct wl_client *client, double dx, double dy) {
    struct relative_pointer *rp;
    if (dx == 0 && dy == 0) return;
    const uint64_t ut = (uint64_t)now_ns() / 1000;
    wl_list_for_each(rp, &g_relative_pointers, link) {
        if (!rp->pointer || wl_resource_get_client(rp->pointer) != client) continue;
        zwp_relative_pointer_v1_send_relative_motion(rp->resource, (uint32_t)(ut >> 32), (uint32_t)ut,
                                                     wl_fixed_from_double(dx), wl_fixed_from_double(dy),
                                                     wl_fixed_from_double(dx), wl_fixed_from_double(dy));
    }
}


static void clamp_to_scene(double *x, double *y) {
    int w, h;
    scene_size(&w, &h);
    if (*x < 0) *x = 0;
    if (*y < 0) *y = 0;
    if (*x > w - 1) *x = w - 1;
    if (*y > h - 1) *y = h - 1;
}

/* Input arrives in output pixels; the scene is fitted into the output the same way it is fitted onto the screen. */
static void output_to_scene(float ox, float oy, double *x, double *y) {
    int sw, sh;
    const int ow = atomic_load(&g_output_w), oh = atomic_load(&g_output_h);
    scene_size(&sw, &sh);
    if (ow > 0 && oh > 0 && sw > 0 && sh > 0 && (sw != ow || sh != oh)) {
        const double k = fmin((double)ow / sw, (double)oh / sh);
        *x = (ox - (ow - sw * k) / 2) / k;
        *y = (oy - (oh - sh * k) / 2) / k;
    } else {
        *x = ox;
        *y = oy;
    }
    clamp_to_scene(x, y);
}

static void pointer_input(double x, double y, int relative, uint32_t button, int pressed) {
    struct constraint *k = g_active_constraint;
    struct surface *target = k ? k->surface : top_toplevel();
    struct seat_resource *sp;
    double dx, dy;

    if (relative) {
        dx = x; dy = y;
    } else {
        dx = g_raw_valid ? x - g_raw_x : 0;
        dy = g_raw_valid ? y - g_raw_y : 0;
        g_raw_x = x; g_raw_y = y; g_raw_valid = 1;
    }
    /* Relative motion (the touch layer's camera drag) leaves the cursor where it is: until the game's grab arrived,
       a drag used to pull the visible cursor along, and the game took that as a jump of the camera once it did. */
    if (!relative && !(k && k->is_lock)) {
        if (k) confine_clamp(k, &x, &y);
        g_ptr_x = x;
        g_ptr_y = y;
        g_ptr_placed = 1;
    }
    if (!target) return;
    struct wl_client *client = wl_resource_get_client(target->resource);
    if (!pointer_for(client)) return;
    const wl_fixed_t fx = wl_fixed_from_double(g_ptr_x), fy = wl_fixed_from_double(g_ptr_y);
    const uint32_t t = now_ms();
    pointer_focus(target->resource, fx, fy);
    if (!relative && !(k && k->is_lock))
        for_each_seat_resource_of(g_ptrs, g_nptrs, client, sp) wl_pointer_send_motion(sp->res, t, fx, fy);
    send_relative_motion(client, dx, dy);
    for_each_seat_resource_of(g_ptrs, g_nptrs, client, sp) {
        if (button)
            wl_pointer_send_button(sp->res, wl_display_next_serial(g_display), t, button,
                                   pressed ? WL_POINTER_BUTTON_STATE_PRESSED : WL_POINTER_BUTTON_STATE_RELEASED);
        send_pointer_frame(sp->res);
    }
    if (cursor_visible()) schedule_render();
    wl_display_flush_clients(g_display);
}

static void pointer_button(uint32_t button, int pressed) {
    if (pressed) {
        pointer_input(0, 0, 1, button, 1);
        return;
    }
    /* Releases go to the current focus without motion: a touch-driven pointer must not jump back. */
    const uint32_t t = now_ms();
    for (int i = 0; i < g_nptrs; i++) {
        if (!g_ptrs[i].focus) continue;
        wl_pointer_send_button(g_ptrs[i].res, wl_display_next_serial(g_display), t, button, WL_POINTER_BUTTON_STATE_RELEASED);
        send_pointer_frame(g_ptrs[i].res);
    }
    wl_display_flush_clients(g_display);
}

static void send_axis(struct wl_resource *ptr, uint32_t t, uint32_t axis, int value120) {
    if (!value120) return;
    if (wl_resource_get_version(ptr) >= WL_POINTER_AXIS_VALUE120_SINCE_VERSION)
        wl_pointer_send_axis_value120(ptr, axis, value120);
    else if (wl_resource_get_version(ptr) >= WL_POINTER_AXIS_DISCRETE_SINCE_VERSION && value120 % 120 == 0)
        wl_pointer_send_axis_discrete(ptr, axis, value120 / 120);
    wl_pointer_send_axis(ptr, t, axis, wl_fixed_from_double(value120 / 12.0));
}

static void axis_event(int vertical120, int horizontal120) {
    struct surface *target = g_active_constraint ? g_active_constraint->surface : top_toplevel();
    struct seat_resource *sp;
    if (!target || (!vertical120 && !horizontal120)) return;
    struct wl_client *client = wl_resource_get_client(target->resource);
    if (!pointer_for(client)) return;
    const uint32_t t = now_ms();
    pointer_focus(target->resource, wl_fixed_from_double(g_ptr_x), wl_fixed_from_double(g_ptr_y));
    for_each_seat_resource_of(g_ptrs, g_nptrs, client, sp) {
        send_axis(sp->res, t, WL_POINTER_AXIS_VERTICAL_SCROLL, vertical120);
        send_axis(sp->res, t, WL_POINTER_AXIS_HORIZONTAL_SCROLL, horizontal120);
        send_pointer_frame(sp->res);
    }
    wl_display_flush_clients(g_display);
}

static int update_modifiers(uint32_t evdev, int pressed) {
    static const struct { uint32_t evdev, mask; } mod_keys[] = {
        { 42, 0x1 }, { 54, 0x1 },
        { 29, 0x4 }, { 97, 0x4 },
        { 56, 0x8 }, { 100, 0x8 },
        { 125, 0x40 }, { 126, 0x40 },
    };
    uint32_t depressed = g_mods_depressed, locked = g_mods_locked;
    if (evdev == 58) {
        if (pressed) locked ^= 0x2;
    } else {
        int i, n = (int)(sizeof(mod_keys) / sizeof(mod_keys[0]));
        for (i = 0; i < n && mod_keys[i].evdev != evdev; i++) {}
        if (i == n) return 0;
        if (pressed) g_mod_keys_held |= 1u << i; else g_mod_keys_held &= ~(1u << i);
        depressed = 0;
        for (int j = 0; j < n; j++) if (g_mod_keys_held & (1u << j)) depressed |= mod_keys[j].mask;
    }
    if (depressed == g_mods_depressed && locked == g_mods_locked) return 0;
    g_mods_depressed = depressed;
    g_mods_locked = locked;
    return 1;
}

static void key_event(uint32_t evdev, int pressed) {
    struct surface *target = top_toplevel();
    struct seat_resource *sk;
    if (evdev < 0x300) {
        const uint8_t bit = (uint8_t)(1u << (evdev & 7));
        if (pressed == !!(g_keys_down[evdev >> 3] & bit)) return;
        if (pressed) g_keys_down[evdev >> 3] |= bit; else g_keys_down[evdev >> 3] &= (uint8_t)~bit;
    }
    const int mods_changed = update_modifiers(evdev, pressed);
    if (!target) return;
    struct wl_client *client = wl_resource_get_client(target->resource);
    if (!keyboard_for(client)) return;
    keyboard_focus(target->resource);
    for_each_seat_resource_of(g_kbs, g_nkbs, client, sk) {
        wl_keyboard_send_key(sk->res, wl_display_next_serial(g_display), now_ms(), evdev,
                             pressed ? WL_KEYBOARD_KEY_STATE_PRESSED : WL_KEYBOARD_KEY_STATE_RELEASED);
        if (mods_changed)
            wl_keyboard_send_modifiers(sk->res, wl_display_next_serial(g_display), g_mods_depressed, 0, g_mods_locked, 0);
    }
    wl_display_flush_clients(g_display);
}


static void touch_frame(struct wl_client *client) {
    struct seat_resource *st;
    for_each_seat_resource_of(g_touches, g_ntouches, client, st)
        if (wl_resource_get_version(st->res) >= WL_TOUCH_FRAME_SINCE_VERSION) wl_touch_send_frame(st->res);
}

static void touch_cancel_all(void) {
    int release_pointer = 0;
    for (int i = 0; i < g_ntouches; i++) {
        if (g_touches[i].focus) wl_touch_send_cancel(g_touches[i].res);
        g_touches[i].focus = NULL;
    }
    for (int i = 0; i < MAX_TOUCH_POINTS; i++) release_pointer |= g_touch_points[i].pointer_fallback;
    memset(g_touch_points, 0, sizeof(g_touch_points));
    if (release_pointer) pointer_button(BTN_LEFT, 0);
    wl_display_flush_clients(g_display);
}

static void touch_cancel_surface(struct surface *s) {
    for (int i = 0; i < MAX_TOUCH_POINTS; i++)
        if (g_touch_points[i].surface == s) { touch_cancel_all(); return; }
}

/* gamescope binds no wl_touch: a client without one gets the first finger as the left button. */
static void touch_down(int id, double x, double y) {
    struct surface *target = top_toplevel();
    struct seat_resource *st;
    int has_touch = 0, active = 0;
    if (id < 0 || id >= MAX_TOUCH_POINTS || !target) return;
    if (g_touch_points[id].surface) touch_cancel_all();
    struct wl_client *client = wl_resource_get_client(target->resource);
    for_each_seat_resource_of(g_touches, g_ntouches, client, st) has_touch = 1;
    for (int i = 0; i < MAX_TOUCH_POINTS; i++) if (g_touch_points[i].surface) active = 1;
    if (!has_touch) {
        if (active) return;
        g_touch_points[id].surface = target;
        g_touch_points[id].pointer_fallback = 1;
        g_raw_valid = 0;
        pointer_input(x, y, 0, BTN_LEFT, 1);
        return;
    }
    const wl_fixed_t fx = wl_fixed_from_double(x), fy = wl_fixed_from_double(y);
    for_each_seat_resource_of(g_touches, g_ntouches, client, st) {
        st->focus = target->resource;
        wl_touch_send_down(st->res, wl_display_next_serial(g_display), now_ms(), target->resource, id, fx, fy);
    }
    g_touch_points[id].surface = target;
    touch_frame(client);
    wl_display_flush_clients(g_display);
}

static void touch_motion(int id, double x, double y) {
    struct seat_resource *st;
    if (id < 0 || id >= MAX_TOUCH_POINTS) return;
    struct surface *target = g_touch_points[id].surface;
    if (!target) return;
    if (g_touch_points[id].pointer_fallback) { pointer_input(x, y, 0, 0, 0); return; }
    struct wl_client *client = wl_resource_get_client(target->resource);
    for_each_seat_resource_of(g_touches, g_ntouches, client, st)
        if (st->focus == target->resource)
            wl_touch_send_motion(st->res, now_ms(), id, wl_fixed_from_double(x), wl_fixed_from_double(y));
    touch_frame(client);
    wl_display_flush_clients(g_display);
}

static void touch_up(int id, double x, double y) {
    struct seat_resource *st;
    int left = 0;
    if (id < 0 || id >= MAX_TOUCH_POINTS) return;
    struct surface *target = g_touch_points[id].surface;
    if (!target) return;
    if (g_touch_points[id].pointer_fallback) {
        memset(&g_touch_points[id], 0, sizeof(g_touch_points[id]));
        pointer_input(x, y, 0, BTN_LEFT, 0);
        return;
    }
    struct wl_client *client = wl_resource_get_client(target->resource);
    for_each_seat_resource_of(g_touches, g_ntouches, client, st)
        if (st->focus == target->resource)
            wl_touch_send_up(st->res, wl_display_next_serial(g_display), now_ms(), id);
    memset(&g_touch_points[id], 0, sizeof(g_touch_points[id]));
    touch_frame(client);
    for (int i = 0; i < MAX_TOUCH_POINTS; i++) if (g_touch_points[i].surface) left = 1;
    if (!left) for_each_seat_resource_of(g_touches, g_ntouches, client, st) st->focus = NULL;
    wl_display_flush_clients(g_display);
}

static void deliver_touch(int id, int action, float ox, float oy) {
    double x, y;
    output_to_scene(ox, oy, &x, &y);
    if (action == 0) touch_down(id, x, y);
    else if (action == 1) touch_motion(id, x, y);
    else if (action == 2) touch_up(id, x, y);
    else if (action == 3) touch_cancel_all();
}


static int on_msg(int fd, uint32_t mask, void *data) {
    struct comp_msg m;
    int vsync = 0;
    int64_t vsync_ns = 0;
    double x, y;
    while (read(fd, &m, sizeof(m)) == (ssize_t)sizeof(m)) {
        switch (m.type) {
        case MSG_STOP: wl_display_terminate(g_display); return 0;
        case MSG_VSYNC: vsync = 1; vsync_ns = m.t; break;
        case MSG_WINDOW: if (vkp_apply_window_request()) schedule_render(); break;
        case MSG_RESIZE: outputs_changed(); schedule_render(); break;
        case MSG_MOTION: output_to_scene(m.x, m.y, &x, &y); pointer_input(x, y, 0, 0, 0); break;
        case MSG_RELATIVE: pointer_input(m.x, m.y, 1, 0, 0); break;
        case MSG_BUTTON: pointer_button((uint32_t)m.a, m.b); break;
        case MSG_AXIS: axis_event((int)lroundf(-m.x * 120.0f), (int)lroundf(m.y * 120.0f)); break;
        case MSG_KEY: key_event((uint32_t)m.a, m.b); break;
        case MSG_TOUCH: deliver_touch(m.a, m.b, m.x, m.y); break;
        default: break;
        }
    }
    if (vsync) on_vsync(vsync_ns);
    return 0;
}

/* The panel rate a shortest vsync interval belongs to, or 0 when it is none (a late or early vsync). */
static int panel_rate_hz(int64_t interval_ns) {
    static const int rates[] = {24, 30, 48, 50, 60, 72, 75, 90, 96, 100, 120, 144, 165, 240};
    const int64_t mhz = 1000000000000LL / interval_ns;
    for (size_t i = 0; i < sizeof rates / sizeof rates[0]; i++) {
        const int64_t diff = mhz - rates[i] * 1000LL;
        if ((diff < 0 ? -diff : diff) <= rates[i] * 40LL) return rates[i];
    }
    return 0;
}

static int on_stats_timer(void *data) {
    const int64_t now = now_ns(), elapsed = now - g_fps_since_ns;
    atomic_store(&g_fps_milli, elapsed > 0 ? (int)((double)g_frames * 1e12 / (double)elapsed) : 0);
    g_frames = 0;
    g_fps_since_ns = now;
    if (g_min_interval_ns > 0) {
        /* Only a faster panel rate, measured two seconds running, is announced. Android drops the panel to 60 Hz
           whenever it can - the notification shade, for one - and each new rate made gamescope and the game rebuild
           their presentation, which flashed black. Frames keep following the panel's real vsync either way. */
        const int hz = panel_rate_hz(g_min_interval_ns);
        if (hz * 1000 <= g_refresh_mhz) {
            g_refresh_candidate_hz = 0;
        } else if (hz != g_refresh_candidate_hz) {
            g_refresh_candidate_hz = hz;
            g_refresh_candidate_seconds = 1;
        } else if (++g_refresh_candidate_seconds >= 2) {
            g_refresh_mhz = hz * 1000;
            LOGI("screen refresh %d Hz", hz);
            outputs_changed();
        }
        g_min_interval_ns = 0;
    }
    if (++g_stats_ticks % 10 == 0 && (g_stat_frames || g_stat_dmabuf || g_stat_shm)) {
        LOGI("last 10 s: %u frames shown, %u GPU buffers and %u shared-memory frames from the client",
             g_stat_frames, g_stat_dmabuf, g_stat_shm);
        g_stat_frames = g_stat_dmabuf = g_stat_shm = 0;
    }
    wl_event_source_timer_update(g_stats_timer, 1000);
    return 0;
}

static void on_client_created(struct wl_listener *l, void *data) {
    pid_t pid;
    uid_t uid;
    gid_t gid;
    wl_client_get_credentials(data, &pid, &uid, &gid);
    LOGI("client connected (pid %d)", (int)pid);
}
static struct wl_listener g_client_created = { .notify = on_client_created };


static int post_msg(const struct comp_msg *m) {
    ssize_t n;
    do { n = write(g_pipe[1], m, sizeof(*m)); } while (n < 0 && errno == EINTR);
    return n == (ssize_t)sizeof(*m) ? 0 : -1;
}

static void post_vsync(int64_t frame_time_ns) {
    struct comp_msg m = {.type = MSG_VSYNC, .t = frame_time_ns};
    post_msg(&m);
}

static void *comp_main(void *arg) {
    pthread_setname_np(pthread_self(), "ddo-compositor");
    setpriority(PRIO_PROCESS, (id_t)gettid(), -8);
    if (vkp_ready() != 0) LOGE("no Vulkan renderer: the client can connect but nothing is shown");
    if (vsync_start(post_vsync) != 0) LOGE("no vsync thread: frames are paced by a timer");
    wl_display_run(g_display);

    g_stopping = 1;
    vsync_stop();
    wl_display_destroy_clients(g_display);
    wl_display_destroy(g_display);
    g_display = NULL;
    vkp_shutdown();
    if (g_keymap_fd >= 0) close(g_keymap_fd);
    g_keymap_fd = -1;
    g_render_idle = g_fallback_timer = g_frame_timer = g_stats_timer = NULL;
    g_dirty = g_fallback_armed = 0;
    g_tick_ns = g_rendered_tick_ns = g_last_vsync_ns = g_min_interval_ns = 0;
    g_refresh_mhz = 60000;
    g_refresh_candidate_hz = g_refresh_candidate_seconds = 0;
    g_cursor_surface = NULL;
    g_active_constraint = NULL;
    g_ptr_placed = g_raw_valid = 0;
    g_mods_depressed = g_mods_locked = g_mod_keys_held = 0;
    memset(g_keys_down, 0, sizeof(g_keys_down));
    memset(g_touch_points, 0, sizeof(g_touch_points));
    g_nptrs = g_nkbs = g_ntouches = 0;
    atomic_store(&g_has_toplevel, 0);
    atomic_store(&g_fps_milli, 0);
    g_stopping = 0;
    LOGI("compositor stopped");
    return NULL;
}

int compositor_start(const char *socket_path, int width, int height) {
    struct comp_msg m;
    char lock[512];
    pthread_mutex_lock(&g_life_lock);
    if (atomic_load(&g_running)) { pthread_mutex_unlock(&g_life_lock); return 0; }
    if (g_pipe[0] < 0 && pipe2(g_pipe, O_CLOEXEC | O_NONBLOCK) != 0) {
        LOGE("message pipe: %s", strerror(errno));
        pthread_mutex_unlock(&g_life_lock);
        return -1;
    }
    while (read(g_pipe[0], &m, sizeof(m)) > 0) {}
    if (width > 0 && height > 0) { atomic_store(&g_output_w, width); atomic_store(&g_output_h, height); }
    wl_list_init(&g_surfaces);
    wl_list_init(&g_toplevels);
    wl_list_init(&g_outputs);
    wl_list_init(&g_constraints);
    wl_list_init(&g_relative_pointers);

    struct wl_display *display = wl_display_create();
    if (!display) { LOGE("wl_display_create failed"); pthread_mutex_unlock(&g_life_lock); return -1; }
    snprintf(lock, sizeof(lock), "%s.lock", socket_path);
    unlink(socket_path);
    unlink(lock);
    if (wl_display_add_socket(display, socket_path) != 0) {
        LOGE("cannot listen on %s: %s", socket_path, strerror(errno));
        wl_display_destroy(display);
        pthread_mutex_unlock(&g_life_lock);
        return -1;
    }
    g_display = display;
    g_keymap_fd = keymap_create(&g_keymap_size);
    wl_global_create(display, &wl_compositor_interface, 6, NULL, bind_compositor);
    wl_global_create(display, &wl_subcompositor_interface, 1, NULL, bind_subcompositor);
    wl_display_init_shm(display);
    dmabuf_init(display);
    wl_global_create(display, &xdg_wm_base_interface, 1, NULL, bind_xdg_wm_base);
    wl_global_create(display, &wl_seat_interface, 9, NULL, bind_seat);
    wl_global_create(display, &wl_output_interface, 4, NULL, bind_output);
    wl_global_create(display, &wp_viewporter_interface, 1, NULL, bind_viewporter);
    wl_global_create(display, &wp_presentation_interface, 2, NULL, bind_presentation);
    wl_global_create(display, &zwp_relative_pointer_manager_v1_interface, 1, NULL, bind_relative_pointer_manager);
    wl_global_create(display, &zwp_pointer_constraints_v1_interface, 1, NULL, bind_pointer_constraints);
    single_pixel_init(display);
    wl_display_add_client_created_listener(display, &g_client_created);

    struct wl_event_loop *loop = wl_display_get_event_loop(display);
    wl_event_loop_add_fd(loop, g_pipe[0], WL_EVENT_READABLE, on_msg, NULL);
    if ((g_stats_timer = wl_event_loop_add_timer(loop, on_stats_timer, NULL)))
        wl_event_source_timer_update(g_stats_timer, 1000);
    g_fps_since_ns = now_ns();

    atomic_store(&g_running, 1);
    if (pthread_create(&g_thread, NULL, comp_main, NULL) != 0) {
        LOGE("cannot start the compositor thread");
        atomic_store(&g_running, 0);
        wl_display_destroy(display);
        g_display = NULL;
        g_stats_timer = NULL;
        if (g_keymap_fd >= 0) close(g_keymap_fd);
        g_keymap_fd = -1;
        pthread_mutex_unlock(&g_life_lock);
        return -1;
    }
    LOGI("listening on %s, output %dx%d", socket_path, atomic_load(&g_output_w), atomic_load(&g_output_h));
    pthread_mutex_unlock(&g_life_lock);
    return 0;
}

void compositor_stop(void) {
    struct comp_msg m = {.type = MSG_STOP};
    pthread_mutex_lock(&g_life_lock);
    if (!atomic_load(&g_running)) { pthread_mutex_unlock(&g_life_lock); return; }
    int sent = 0;
    for (int i = 0; i < 1000 && !(sent = post_msg(&m) == 0); i++) usleep(1000);
    if (!sent) wl_display_terminate(g_display);
    pthread_join(g_thread, NULL);
    atomic_store(&g_running, 0);
    pthread_mutex_unlock(&g_life_lock);
}

int compositor_running(void) { return atomic_load(&g_running); }

void compositor_post(int type, int a, int b, float x, float y) {
    struct comp_msg m = {.type = type, .a = a, .b = b, .x = x, .y = y};
    if (!atomic_load(&g_running)) return;
    post_msg(&m);
}

void compositor_resize(int width, int height) {
    if (width <= 0 || height <= 0) return;
    atomic_store(&g_output_w, width);
    atomic_store(&g_output_h, height);
    compositor_post(MSG_RESIZE, 0, 0, 0, 0);
}

float compositor_fps(void) { return (float)atomic_load(&g_fps_milli) / 1000.0f; }

int compositor_has_client_surface(void) { return atomic_load(&g_has_toplevel); }
