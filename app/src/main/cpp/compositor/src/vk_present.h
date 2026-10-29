/* Derived from DroidDeck waylandcomp/src/vk_present.h (GPL-3.0); trimmed for DDO Mobile, 2026-10. */
#ifndef VK_PRESENT_H
#define VK_PRESENT_H
#include <stdint.h>
#include <android/native_window.h>

#define VKP_MOD_LINEAR          0x0000000000000000ULL
#define VKP_MOD_QCOM_COMPRESSED 0x0500000000000001ULL
#define VKP_MOD_INVALID         0x00ffffffffffffffULL

struct vkp_image;

struct vkp_draw {
    struct vkp_image *img;
    float sx, sy, sw, sh;
    int dx, dy, dw, dh;
    int blend;
};

void vk_present_set_driver(const char *driver_dir, const char *driver_name, const char *native_lib_dir);
void vk_present_set_window(ANativeWindow *window);
int vkp_apply_window_request(void);
int vkp_wait_window_request(int timeout_ms);
int vkp_has_window(void);
int vkp_ready(void);
void vkp_shutdown(void);
const char *vkp_gpu_name(void);

const char *vkp_modifier_name(uint64_t modifier);
int vkp_dmabuf_modifiers(uint32_t drm_format, uint64_t *out, int max);
struct vkp_image *vkp_image_from_dmabuf(int fd, uint32_t drm_format, uint64_t modifier,
                                        int w, int h, uint32_t stride, uint32_t offset);
struct vkp_image *vkp_image_create_shm(int w, int h);
void vkp_image_upload_shm(struct vkp_image *img, const void *data, int stride);
int vkp_image_width(const struct vkp_image *img);
int vkp_image_height(const struct vkp_image *img);
void vkp_image_destroy(struct vkp_image *img);

int vkp_render(int scene_w, int scene_h, const struct vkp_draw *draws, int n);

#endif
