/* Derived from DroidDeck waylandcomp/src/vk_present.c (GPL-3.0); trimmed for DDO Mobile, 2026-10. */
#define _POSIX_C_SOURCE 200809L
#include "vk_present.h"
#include "vk_loader.h"
#include "blend_pass.h"
#include "ddo_log.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <pthread.h>

struct vkp_image {
    VkImage image;
    VkDeviceMemory mem;
    int w, h;
    int dmabuf;
    void *map;
    VkDeviceSize offset, row_pitch;
    int in_general;
    VkFormat fmt;
    int sampled;
    VkFilter filter;
};

static char *g_driver_dir, *g_driver_name, *g_native_lib_dir;
static int g_dev_state;
static VkInstance g_inst;
static VkPhysicalDevice g_pd;
static VkDevice g_dev;
static VkQueue g_queue;
static uint32_t g_qfam;
static uint32_t g_foreign_qfam = VK_QUEUE_FAMILY_EXTERNAL;
static int g_dmabuf_import, g_modifiers;
static VkCommandPool g_pool;
static VkCommandBuffer g_cmd;
static VkSemaphore g_acq, g_rnd;
static VkFence g_fence;
static VkPhysicalDeviceMemoryProperties g_memprops;
static char g_gpu_name[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE];

static ANativeWindow *g_window;
static VkSurfaceKHR g_surface;
static VkSwapchainKHR g_swapchain;
static VkSurfaceTransformFlagBitsKHR g_surface_transform;
static VkFormat g_swap_fmt;
static VkImage *g_images;
static uint32_t g_nimg;
static VkExtent2D g_extent, g_caps_extent;
static int64_t g_surface_checked_ns;
static int64_t g_swap_retry_at_ns;
static int g_swap_fail_logged;

#define BLACK_DIM 16
static VkImage g_black_img;
static VkDeviceMemory g_black_mem;
static int g_black_ok;

static pthread_mutex_t g_req_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_req_condition = PTHREAD_COND_INITIALIZER;
static ANativeWindow *g_req_window;
static int g_req_pending, g_req_detached;
static uint64_t g_req_generation, g_req_applied;

static struct {
    int scene_w, scene_h, out_w, out_h;
    float k, off_x, off_y;
    int valid;
} g_map;

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

const char *vkp_gpu_name(void) { return g_gpu_name; }

static const char *vk_result_name(VkResult r) {
    switch (r) {
    case VK_ERROR_OUT_OF_DATE_KHR: return "OUT_OF_DATE";
    case VK_SUBOPTIMAL_KHR: return "SUBOPTIMAL";
    case VK_ERROR_SURFACE_LOST_KHR: return "SURFACE_LOST";
    case VK_ERROR_DEVICE_LOST: return "DEVICE_LOST";
    case VK_TIMEOUT: return "TIMEOUT";
    case VK_NOT_READY: return "NOT_READY";
    case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "OUT_OF_DEVICE_MEMORY";
    case VK_ERROR_OUT_OF_HOST_MEMORY: return "OUT_OF_HOST_MEMORY";
    case VK_ERROR_FORMAT_NOT_SUPPORTED: return "FORMAT_NOT_SUPPORTED";
    case VK_ERROR_INVALID_EXTERNAL_HANDLE: return "INVALID_EXTERNAL_HANDLE";
    case VK_ERROR_INVALID_DRM_FORMAT_MODIFIER_PLANE_LAYOUT_EXT: return "INVALID_DRM_FORMAT_MODIFIER_PLANE_LAYOUT";
    default: {
        static char other[24];
        snprintf(other, sizeof(other), "error %d", (int)r);
        return other;
    }
    }
}

static VkFormat drm_to_vk(uint32_t drm) {
    switch (drm) {
    case 0x34324241:
    case 0x34324258:
        return VK_FORMAT_R8G8B8A8_UNORM;
    case 0x30334241:
    case 0x30334258:
        return VK_FORMAT_A2B10G10R10_UNORM_PACK32;
    case 0x30335241:
    case 0x30335258:
        return VK_FORMAT_A2R10G10B10_UNORM_PACK32;
    case 0x48344241:
    case 0x48344258:
        return VK_FORMAT_R16G16B16A16_SFLOAT;
    default:
        return VK_FORMAT_B8G8R8A8_UNORM;
    }
}

void vk_present_set_driver(const char *driver_dir, const char *driver_name, const char *native_lib_dir) {
    free(g_driver_dir); free(g_driver_name); free(g_native_lib_dir);
    g_driver_dir = driver_dir ? strdup(driver_dir) : NULL;
    g_driver_name = driver_name ? strdup(driver_name) : NULL;
    g_native_lib_dir = native_lib_dir ? strdup(native_lib_dir) : NULL;
}

static void reset_sync(void) {
    VkSemaphoreCreateInfo semci = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    if (g_acq) g_vk.DestroySemaphore(g_dev, g_acq, NULL);
    if (g_rnd) g_vk.DestroySemaphore(g_dev, g_rnd, NULL);
    g_vk.CreateSemaphore(g_dev, &semci, NULL, &g_acq);
    g_vk.CreateSemaphore(g_dev, &semci, NULL, &g_rnd);
}

static void destroy_swapchain(void) {
    if (g_dev_state != 1) return;
    g_vk.DeviceWaitIdle(g_dev);
    reset_sync();
    for (uint32_t i = 0; i < g_nimg; i++) blendp_forget_image(g_images[i]);
    if (g_swapchain) g_vk.DestroySwapchainKHR(g_dev, g_swapchain, NULL);
    g_swapchain = VK_NULL_HANDLE;
    if (g_surface) g_vk.DestroySurfaceKHR(g_inst, g_surface, NULL);
    g_surface = VK_NULL_HANDLE;
    free(g_images);
    g_images = NULL;
    g_nimg = 0;
}

void vk_present_set_window(ANativeWindow *window) {
    ANativeWindow *superseded = NULL;
    pthread_mutex_lock(&g_req_lock);
    if (g_req_pending && g_req_window && g_req_window != window) superseded = g_req_window;
    g_req_window = window;
    g_req_pending = 1;
    g_req_generation++;
    if (!window) g_req_detached = 1;
    pthread_mutex_unlock(&g_req_lock);
    if (superseded) ANativeWindow_release(superseded);
}

static void window_request_applied(uint64_t generation) {
    pthread_mutex_lock(&g_req_lock);
    g_req_applied = generation;
    pthread_cond_broadcast(&g_req_condition);
    pthread_mutex_unlock(&g_req_lock);
}

int vkp_wait_window_request(int timeout_ms) {
    if (timeout_ms <= 0 || timeout_ms > 10000) return 0;
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += timeout_ms / 1000;
    deadline.tv_nsec += (timeout_ms % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) { deadline.tv_sec++; deadline.tv_nsec -= 1000000000L; }
    pthread_mutex_lock(&g_req_lock);
    const uint64_t generation = g_req_generation;
    int error = 0;
    while (g_req_applied < generation && error == 0)
        error = pthread_cond_timedwait(&g_req_condition, &g_req_lock, &deadline);
    int applied = g_req_applied >= generation;
    pthread_mutex_unlock(&g_req_lock);
    return applied;
}

int vkp_apply_window_request(void) {
    ANativeWindow *w;
    pthread_mutex_lock(&g_req_lock);
    if (!g_req_pending) { pthread_mutex_unlock(&g_req_lock); return 0; }
    w = g_req_window;
    const int detached = g_req_detached;
    const uint64_t generation = g_req_generation;
    g_req_window = NULL;
    g_req_pending = 0;
    g_req_detached = 0;
    pthread_mutex_unlock(&g_req_lock);
    if (w == g_window) {
        if (w) ANativeWindow_release(w);
        if (!w || !detached) { window_request_applied(generation); return 0; }
        destroy_swapchain();
        window_request_applied(generation);
        LOGI("screen surface rebound: rebuilding the swapchain");
        return 1;
    }
    destroy_swapchain();
    if (g_window) ANativeWindow_release(g_window);
    g_window = w;
    g_swap_retry_at_ns = 0;
    g_swap_fail_logged = 0;
    window_request_applied(generation);
    LOGI(w ? "screen surface attached" : "screen surface gone: presenting paused");
    return 1;
}

int vkp_has_window(void) {
    int r;
    pthread_mutex_lock(&g_req_lock);
    r = g_req_pending ? g_req_window != NULL : g_window != NULL;
    pthread_mutex_unlock(&g_req_lock);
    return r;
}

void vkp_shutdown(void) {
    vkp_apply_window_request();
    destroy_swapchain();
    g_map.valid = 0;
}

static void update_map(int scene_w, int scene_h) {
    int W = (int)g_extent.width, H = (int)g_extent.height;
    if (g_map.valid && g_map.scene_w == scene_w && g_map.scene_h == scene_h && g_map.out_w == W && g_map.out_h == H)
        return;
    g_map.scene_w = scene_w; g_map.scene_h = scene_h; g_map.out_w = W; g_map.out_h = H;
    float sx = (float)W / scene_w, sy = (float)H / scene_h;
    g_map.k = sx < sy ? sx : sy;
    g_map.off_x = (float)(int)((W - scene_w * g_map.k) * 0.5f);
    g_map.off_y = (float)(int)((H - scene_h * g_map.k) * 0.5f);
    g_map.valid = 1;
    LOGI("%dx%d scene shown %dx%d at %d,%d on the %dx%d screen", scene_w, scene_h,
         (int)(scene_w * g_map.k + 0.5f), (int)(scene_h * g_map.k + 0.5f), (int)g_map.off_x, (int)g_map.off_y, W, H);
}

static int has_ext(const VkExtensionProperties *e, uint32_t n, const char *name) {
    for (uint32_t i = 0; i < n; i++)
        if (!strcmp(e[i].extensionName, name)) return 1;
    return 0;
}

static int memory_type(uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < g_memprops.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (g_memprops.memoryTypes[i].propertyFlags & want) == want)
            return (int)i;
    return -1;
}

static void init_black_image(void) {
    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_R8G8B8A8_UNORM, .extent = {BLACK_DIM, BLACK_DIM, 1}, .mipLevels = 1, .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
    if (g_vk.CreateImage(g_dev, &ici, NULL, &g_black_img) != VK_SUCCESS) { g_black_img = VK_NULL_HANDLE; return; }
    VkMemoryRequirements req;
    g_vk.GetImageMemoryRequirements(g_dev, g_black_img, &req);
    int idx = memory_type(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (idx < 0) idx = memory_type(req.memoryTypeBits, 0);
    VkMemoryAllocateInfo mai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = req.size,
                                .memoryTypeIndex = (uint32_t)(idx < 0 ? 0 : idx)};
    if (idx < 0 || g_vk.AllocateMemory(g_dev, &mai, NULL, &g_black_mem) != VK_SUCCESS) {
        g_vk.DestroyImage(g_dev, g_black_img, NULL);
        g_black_img = VK_NULL_HANDLE; g_black_mem = VK_NULL_HANDLE;
        return;
    }
    g_vk.BindImageMemory(g_dev, g_black_img, g_black_mem, 0);

    VkCommandBuffer cmd = g_cmd;
    g_vk.ResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo bi = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                   .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    g_vk.BeginCommandBuffer(cmd, &bi);
    VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageMemoryBarrier to_dst = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .image = g_black_img, .subresourceRange = range,
        .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT};
    g_vk.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                            0, 0, NULL, 0, NULL, 1, &to_dst);
    VkClearColorValue black = {.float32 = {0.0f, 0.0f, 0.0f, 1.0f}};
    g_vk.CmdClearColorImage(cmd, g_black_img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &black, 1, &range);
    VkImageMemoryBarrier to_src = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .image = g_black_img, .subresourceRange = range,
        .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT, .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT};
    g_vk.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                            0, 0, NULL, 0, NULL, 1, &to_src);
    g_vk.EndCommandBuffer(cmd);
    VkSubmitInfo si = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &cmd};
    g_vk.ResetFences(g_dev, 1, &g_fence);
    g_black_ok = g_vk.QueueSubmit(g_queue, 1, &si, g_fence) == VK_SUCCESS &&
                 g_vk.WaitForFences(g_dev, 1, &g_fence, VK_TRUE, 1000000000ULL) == VK_SUCCESS;
}

static int dev_init(void) {
    if (g_dev_state != 0) return g_dev_state == 1 ? 0 : -1;
    g_dev_state = -1;

    if (vk_loader_open(g_driver_dir, g_driver_name, g_native_lib_dir) != 0) return -1;
    const char *inst_exts[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                             .pApplicationName = "ddo-compositor", .apiVersion = VK_API_VERSION_1_3};
    VkInstanceCreateInfo ici = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app,
                                .enabledExtensionCount = 2, .ppEnabledExtensionNames = inst_exts};
    if (g_vk.CreateInstance(&ici, NULL, &g_inst) != VK_SUCCESS) { LOGE("vkCreateInstance failed"); return -1; }
    vk_loader_load_instance(g_inst);

    uint32_t npd = 0;
    g_vk.EnumeratePhysicalDevices(g_inst, &npd, NULL);
    if (!npd) { LOGE("no Vulkan physical devices"); return -1; }
    VkPhysicalDevice pds[8];
    if (npd > 8) npd = 8;
    g_vk.EnumeratePhysicalDevices(g_inst, &npd, pds);
    g_pd = VK_NULL_HANDLE;
    for (uint32_t i = 0; i < npd && g_pd == VK_NULL_HANDLE; i++) {
        uint32_t nq = 0;
        g_vk.GetPhysicalDeviceQueueFamilyProperties(pds[i], &nq, NULL);
        VkQueueFamilyProperties qs[16];
        if (nq > 16) nq = 16;
        g_vk.GetPhysicalDeviceQueueFamilyProperties(pds[i], &nq, qs);
        for (uint32_t q = 0; q < nq; q++)
            if (qs[q].queueFlags & VK_QUEUE_GRAPHICS_BIT) { g_pd = pds[i]; g_qfam = q; break; }
    }
    if (g_pd == VK_NULL_HANDLE) { LOGE("no Vulkan graphics queue"); return -1; }
    VkPhysicalDeviceProperties props;
    g_vk.GetPhysicalDeviceProperties(g_pd, &props);
    snprintf(g_gpu_name, sizeof(g_gpu_name), "%s", props.deviceName);
    g_vk.GetPhysicalDeviceMemoryProperties(g_pd, &g_memprops);

    static const char *const import_exts[] = {"VK_KHR_external_memory_fd", "VK_EXT_external_memory_dma_buf",
                                              "VK_EXT_image_drm_format_modifier", "VK_KHR_image_format_list"};
    const char *dev_exts[8] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    uint32_t n_dev_exts = 1, ne = 0, n_import = 0;
    g_vk.EnumerateDeviceExtensionProperties(g_pd, NULL, &ne, NULL);
    VkExtensionProperties *exts = calloc(ne ? ne : 1, sizeof(*exts));
    g_vk.EnumerateDeviceExtensionProperties(g_pd, NULL, &ne, exts);
    for (unsigned i = 0; i < sizeof(import_exts) / sizeof(import_exts[0]); i++) {
        if (has_ext(exts, ne, import_exts[i])) { dev_exts[n_dev_exts++] = import_exts[i]; n_import++; }
        else LOGE("driver lacks %s: dma-buf frames cannot be imported", import_exts[i]);
    }
    g_dmabuf_import = n_import == sizeof(import_exts) / sizeof(import_exts[0]);
    g_modifiers = has_ext(exts, ne, "VK_EXT_image_drm_format_modifier");
    if (has_ext(exts, ne, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)) {
        dev_exts[n_dev_exts++] = VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME;
        g_foreign_qfam = VK_QUEUE_FAMILY_FOREIGN_EXT;
    }
    free(exts);

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                   .queueFamilyIndex = g_qfam, .queueCount = 1, .pQueuePriorities = &prio};
    VkDeviceCreateInfo dci = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount = 1,
                              .pQueueCreateInfos = &qci, .enabledExtensionCount = n_dev_exts,
                              .ppEnabledExtensionNames = dev_exts};
    VkResult dr = g_vk.CreateDevice(g_pd, &dci, NULL, &g_dev);
    if (dr != VK_SUCCESS) { LOGE("vkCreateDevice failed (%s)", vk_result_name(dr)); return -1; }
    vk_loader_load_device(g_dev);
    g_vk.GetDeviceQueue(g_dev, g_qfam, 0, &g_queue);

    VkCommandPoolCreateInfo pci = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                   .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
                                   .queueFamilyIndex = g_qfam};
    g_vk.CreateCommandPool(g_dev, &pci, NULL, &g_pool);
    VkCommandBufferAllocateInfo cai = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, .commandPool = g_pool,
                                       .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
    g_vk.AllocateCommandBuffers(g_dev, &cai, &g_cmd);
    VkFenceCreateInfo fci = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    g_vk.CreateFence(g_dev, &fci, NULL, &g_fence);
    blendp_bind_device(g_dev);
    g_dev_state = 1;
    reset_sync();
    init_black_image();
    LOGI("compositor renders on %s with %s%s", g_gpu_name, g_driver_name ? g_driver_name : "the system Vulkan driver",
         g_dmabuf_import ? "" : " (no dma-buf import: only shared-memory frames can be shown)");
    return 0;
}

int vkp_ready(void) { return dev_init(); }

static int swap_init_locked(void) {
    VkAndroidSurfaceCreateInfoKHR aci = {.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR, .window = g_window};
    if (g_vk.CreateAndroidSurfaceKHR(g_inst, &aci, NULL, &g_surface) != VK_SUCCESS) {
        if (!g_swap_fail_logged) LOGE("vkCreateAndroidSurfaceKHR failed");
        return -1;
    }
    VkBool32 sup = VK_FALSE;
    g_vk.GetPhysicalDeviceSurfaceSupportKHR(g_pd, g_qfam, g_surface, &sup);
    if (!sup) { if (!g_swap_fail_logged) LOGE("the queue cannot present to the window"); return -1; }

    VkSurfaceCapabilitiesKHR caps;
    g_vk.GetPhysicalDeviceSurfaceCapabilitiesKHR(g_pd, g_surface, &caps);
    g_surface_transform = caps.currentTransform;
    g_caps_extent = caps.currentExtent;
    g_surface_checked_ns = now_ns();
    uint32_t nfmt = 0;
    g_vk.GetPhysicalDeviceSurfaceFormatsKHR(g_pd, g_surface, &nfmt, NULL);
    VkSurfaceFormatKHR *fmts = nfmt ? calloc(nfmt, sizeof(*fmts)) : NULL;
    if (!fmts || g_vk.GetPhysicalDeviceSurfaceFormatsKHR(g_pd, g_surface, &nfmt, fmts) < 0 || !nfmt) {
        free(fmts);
        if (!g_swap_fail_logged) LOGE("the window lists no surface formats");
        return -1;
    }
    VkSurfaceFormatKHR chosen = fmts[0];
    for (uint32_t i = 0; i < nfmt; i++)
        if (fmts[i].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR &&
            (fmts[i].format == VK_FORMAT_R8G8B8A8_UNORM || fmts[i].format == VK_FORMAT_B8G8R8A8_UNORM)) {
            chosen = fmts[i];
            break;
        }
    free(fmts);
    g_swap_fmt = chosen.format;

    g_extent = caps.currentExtent;
    if (g_extent.width == 0xFFFFFFFF) {
        g_extent.width = (uint32_t)ANativeWindow_getWidth(g_window);
        g_extent.height = (uint32_t)ANativeWindow_getHeight(g_window);
    }
    uint32_t want = caps.minImageCount + 1;
    if (caps.maxImageCount && want > caps.maxImageCount) want = caps.maxImageCount;
    VkSurfaceTransformFlagBitsKHR pretrans = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                                                 ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
    VkCompositeAlphaFlagBitsKHR alpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                                            ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    VkSwapchainCreateInfoKHR sci = {
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR, .surface = g_surface,
        .minImageCount = want, .imageFormat = chosen.format, .imageColorSpace = chosen.colorSpace,
        .imageExtent = g_extent, .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE, .preTransform = pretrans,
        .compositeAlpha = alpha, .presentMode = VK_PRESENT_MODE_FIFO_KHR, .clipped = VK_TRUE};
    VkResult cr = g_vk.CreateSwapchainKHR(g_dev, &sci, NULL, &g_swapchain);
    if (cr != VK_SUCCESS) {
        g_swapchain = VK_NULL_HANDLE;
        if (!g_swap_fail_logged) LOGE("vkCreateSwapchainKHR failed (%s)", vk_result_name(cr));
        return -1;
    }
    g_vk.GetSwapchainImagesKHR(g_dev, g_swapchain, &g_nimg, NULL);
    g_images = calloc(g_nimg, sizeof(VkImage));
    g_vk.GetSwapchainImagesKHR(g_dev, g_swapchain, &g_nimg, g_images);
    g_map.valid = 0;
    LOGI("screen swapchain %ux%u, %u buffers, format %d, vsync", g_extent.width, g_extent.height, g_nimg,
         (int)g_swap_fmt);
    return 0;
}

static int swap_init(void) {
    if (!g_window) return -1;
    const int64_t now = now_ns();
    if (now < g_swap_retry_at_ns) return -1;
    if (swap_init_locked() == 0) {
        g_swap_retry_at_ns = 0;
        g_swap_fail_logged = 0;
        return 0;
    }
    destroy_swapchain();
    g_swap_retry_at_ns = now + 500000000LL;
    if (!g_swap_fail_logged) {
        g_swap_fail_logged = 1;
        LOGE("no swapchain on the screen surface; retrying every 0.5 s");
    }
    return -1;
}

static int record_bars_clear(VkCommandBuffer cmd, VkImage img, const VkImageBlit *covered) {
    if (!g_black_ok || !covered) return 0;
    const int W = (int)g_extent.width, H = (int)g_extent.height;
    int x0 = covered->dstOffsets[0].x, x1 = covered->dstOffsets[1].x;
    int y0 = covered->dstOffsets[0].y, y1 = covered->dstOffsets[1].y;
    if (x0 > x1) { int t = x0; x0 = x1; x1 = t; }
    if (y0 > y1) { int t = y0; y0 = y1; y1 = t; }
    if (x0 < 0) x0 = 0;
    if (y0 < 0) y0 = 0;
    if (x1 > W) x1 = W;
    if (y1 > H) y1 = H;
    if (x1 <= x0 || y1 <= y0) return 0;
    int rects[4][4], n = 0;
    if (y0 > 0) { rects[n][0] = 0;  rects[n][1] = 0;  rects[n][2] = W;  rects[n][3] = y0; n++; }
    if (y1 < H) { rects[n][0] = 0;  rects[n][1] = y1; rects[n][2] = W;  rects[n][3] = H;  n++; }
    if (x0 > 0) { rects[n][0] = 0;  rects[n][1] = y0; rects[n][2] = x0; rects[n][3] = y1; n++; }
    if (x1 < W) { rects[n][0] = x1; rects[n][1] = y0; rects[n][2] = W;  rects[n][3] = y1; n++; }
    if (!n) return 1;
    VkImageBlit blits[4];
    for (int i = 0; i < n; i++)
        blits[i] = (VkImageBlit){.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                                 .srcOffsets = {{0, 0, 0}, {BLACK_DIM, BLACK_DIM, 1}},
                                 .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                                 .dstOffsets = {{rects[i][0], rects[i][1], 0}, {rects[i][2], rects[i][3], 1}}};
    g_vk.CmdBlitImage(cmd, g_black_img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, img,
                      VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, (uint32_t)n, blits, VK_FILTER_NEAREST);
    return 1;
}

const char *vkp_modifier_name(uint64_t modifier) {
    static char other[40];
    if (modifier == VKP_MOD_LINEAR) return "linear";
    if (modifier == VKP_MOD_QCOM_COMPRESSED) return "qcom_compressed";
    if (modifier == VKP_MOD_INVALID) return "implicit";
    snprintf(other, sizeof(other), "modifier %#llx", (unsigned long long)modifier);
    return other;
}

static int modifier_importable(VkFormat fmt, uint64_t modifier, VkImageUsageFlags usage) {
    if (!g_vk.GetPhysicalDeviceImageFormatProperties2) return 1;
    VkPhysicalDeviceExternalImageFormatInfo ext = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO,
        .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkPhysicalDeviceImageDrmFormatModifierInfoEXT mod = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_DRM_FORMAT_MODIFIER_INFO_EXT, .pNext = &ext,
        .drmFormatModifier = modifier, .sharingMode = VK_SHARING_MODE_EXCLUSIVE};
    VkPhysicalDeviceImageFormatInfo2 info = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2, .pNext = &mod,
        .format = fmt, .type = VK_IMAGE_TYPE_2D, .tiling = VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT, .usage = usage};
    VkExternalImageFormatProperties extp = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES};
    VkImageFormatProperties2 props = {.sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2, .pNext = &extp};
    if (g_vk.GetPhysicalDeviceImageFormatProperties2(g_pd, &info, &props) != VK_SUCCESS) return 0;
    return (extp.externalMemoryProperties.externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) != 0;
}

static VkFormatFeatureFlags modifier_features(VkFormat fmt, uint64_t modifier) {
    VkDrmFormatModifierPropertiesListEXT list = {.sType = VK_STRUCTURE_TYPE_DRM_FORMAT_MODIFIER_PROPERTIES_LIST_EXT};
    VkFormatProperties2 fp = {.sType = VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_2, .pNext = &list};
    VkFormatFeatureFlags features = 0;
    if (!g_modifiers || !g_vk.GetPhysicalDeviceFormatProperties2) return 0;
    g_vk.GetPhysicalDeviceFormatProperties2(g_pd, fmt, &fp);
    if (!list.drmFormatModifierCount) return 0;
    VkDrmFormatModifierPropertiesEXT *props = calloc(list.drmFormatModifierCount, sizeof(*props));
    if (!props) return 0;
    list.pDrmFormatModifierProperties = props;
    g_vk.GetPhysicalDeviceFormatProperties2(g_pd, fmt, &fp);
    for (uint32_t i = 0; i < list.drmFormatModifierCount; i++)
        if (props[i].drmFormatModifier == modifier && props[i].drmFormatModifierPlaneCount == 1)
            features = props[i].drmFormatModifierTilingFeatures;
    free(props);
    return features;
}

int vkp_dmabuf_modifiers(uint32_t drm_format, uint64_t *out, int max) {
    if (max <= 0 || dev_init() != 0 || !g_dmabuf_import) return 0;
    const VkFormat fmt = drm_to_vk(drm_format);
    static const uint64_t known[] = {VKP_MOD_LINEAR, VKP_MOD_QCOM_COMPRESSED};
    int n = 0;
    for (unsigned i = 0; i < sizeof(known) / sizeof(known[0]) && n < max; i++) {
        const VkFormatFeatureFlags f = modifier_features(fmt, known[i]);
        if ((f & VK_FORMAT_FEATURE_BLIT_SRC_BIT) && modifier_importable(fmt, known[i], VK_IMAGE_USAGE_TRANSFER_SRC_BIT))
            out[n++] = known[i];
    }
    return n;
}

struct vkp_image *vkp_image_from_dmabuf(int fd, uint32_t drm_format, uint64_t modifier, int w, int h,
                                        uint32_t stride, uint32_t offset) {
    if (w <= 0 || h <= 0 || dev_init() != 0 || !g_dmabuf_import) return NULL;
    if (modifier == VKP_MOD_INVALID) {
        static int said;
        if (!said) { said = 1; LOGW("dma-buf without a modifier: importing it as linear"); }
        modifier = VKP_MOD_LINEAR;
    }
    struct vkp_image *img = calloc(1, sizeof(*img));
    if (!img) return NULL;
    img->w = w; img->h = h; img->dmabuf = 1;
    img->fmt = drm_to_vk(drm_format);
    const VkFormatFeatureFlags features = modifier_features(img->fmt, modifier);
    img->filter = (features & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
    const VkImageUsageFlags sampled_usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    img->sampled = (features & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) && modifier_importable(img->fmt, modifier, sampled_usage);

    VkSubresourceLayout plane = {.offset = offset, .rowPitch = stride};
    VkImageDrmFormatModifierExplicitCreateInfoEXT modInfo = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT,
        .drmFormatModifier = modifier, .drmFormatModifierPlaneCount = 1, .pPlaneLayouts = &plane};
    VkExternalMemoryImageCreateInfo extImg = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, .pNext = &modInfo,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .pNext = &extImg,
        .imageType = VK_IMAGE_TYPE_2D, .format = img->fmt, .extent = {(uint32_t)w, (uint32_t)h, 1},
        .mipLevels = 1, .arrayLayers = 1, .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT,
        .usage = img->sampled ? sampled_usage : VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
    VkResult cr = g_vk.CreateImage(g_dev, &ici, NULL, &img->image);
    if (cr != VK_SUCCESS) {
        LOGE("dmabuf: vkCreateImage(%s, %dx%d, pitch %u, offset %u) -> %s", vkp_modifier_name(modifier), w, h,
             stride, offset, vk_result_name(cr));
        free(img);
        return NULL;
    }

    int dupfd = dup(fd);
    uint32_t allowed = 0xffffffff;
    if (g_vk.GetMemoryFdPropertiesKHR) {
        VkMemoryFdPropertiesKHR fp = {.sType = VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
        if (g_vk.GetMemoryFdPropertiesKHR(g_dev, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, dupfd, &fp) == VK_SUCCESS)
            allowed = fp.memoryTypeBits;
    }
    VkMemoryRequirements req;
    g_vk.GetImageMemoryRequirements(g_dev, img->image, &req);
    uint32_t bits = req.memoryTypeBits & allowed;
    int idx = -1;
    for (int i = 0; i < 32; i++) if (bits & (1u << i)) { idx = i; break; }
    if (idx < 0) {
        LOGE("dmabuf: no memory type can import the %s dma-buf (image types %#x, fd types %#x)",
             vkp_modifier_name(modifier), req.memoryTypeBits, allowed);
        g_vk.DestroyImage(g_dev, img->image, NULL); close(dupfd); free(img);
        return NULL;
    }
    VkImportMemoryFdInfoKHR imp = {.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR,
                                   .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, .fd = dupfd};
    VkMemoryDedicatedAllocateInfo ded = {.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
                                         .pNext = &imp, .image = img->image};
    VkMemoryAllocateInfo mai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &ded,
                                .allocationSize = req.size, .memoryTypeIndex = (uint32_t)idx};
    VkResult mr = g_vk.AllocateMemory(g_dev, &mai, NULL, &img->mem);
    if (mr != VK_SUCCESS) {
        LOGE("dmabuf: importing the %s dma-buf (%dx%d, %llu bytes) -> %s", vkp_modifier_name(modifier), w, h,
             (unsigned long long)req.size, vk_result_name(mr));
        g_vk.DestroyImage(g_dev, img->image, NULL); close(dupfd); free(img);
        return NULL;
    }
    VkResult br = g_vk.BindImageMemory(g_dev, img->image, img->mem, 0);
    if (br != VK_SUCCESS) {
        LOGE("dmabuf: vkBindImageMemory(%s) -> %s", vkp_modifier_name(modifier), vk_result_name(br));
        g_vk.FreeMemory(g_dev, img->mem, NULL); g_vk.DestroyImage(g_dev, img->image, NULL); free(img);
        return NULL;
    }
    return img;
}

static VkFormatFeatureFlags shm_features(void) {
    static int known;
    static VkFormatFeatureFlags features;
    if (!known) {
        VkFormatProperties2 fp = {.sType = VK_STRUCTURE_TYPE_FORMAT_PROPERTIES_2};
        if (g_vk.GetPhysicalDeviceFormatProperties2)
            g_vk.GetPhysicalDeviceFormatProperties2(g_pd, VK_FORMAT_B8G8R8A8_UNORM, &fp);
        features = fp.formatProperties.linearTilingFeatures;
        known = 1;
    }
    return features;
}

struct vkp_image *vkp_image_create_shm(int w, int h) {
    if (w <= 0 || h <= 0 || dev_init() != 0) return NULL;
    struct vkp_image *img = calloc(1, sizeof(*img));
    if (!img) return NULL;
    img->w = w; img->h = h;
    img->fmt = VK_FORMAT_B8G8R8A8_UNORM;
    const VkFormatFeatureFlags features = shm_features();
    img->sampled = (features & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) != 0;
    img->filter = (features & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D,
        .format = img->fmt, .extent = {(uint32_t)w, (uint32_t)h, 1}, .mipLevels = 1, .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_LINEAR,
        .usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | (img->sampled ? VK_IMAGE_USAGE_SAMPLED_BIT : 0),
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE, .initialLayout = VK_IMAGE_LAYOUT_PREINITIALIZED};
    if (g_vk.CreateImage(g_dev, &ici, NULL, &img->image) != VK_SUCCESS) { free(img); return NULL; }
    VkMemoryRequirements req;
    g_vk.GetImageMemoryRequirements(g_dev, img->image, &req);
    int idx = memory_type(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    VkMemoryAllocateInfo mai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = req.size,
                                .memoryTypeIndex = (uint32_t)idx};
    if (idx < 0 || g_vk.AllocateMemory(g_dev, &mai, NULL, &img->mem) != VK_SUCCESS) {
        g_vk.DestroyImage(g_dev, img->image, NULL); free(img);
        return NULL;
    }
    g_vk.BindImageMemory(g_dev, img->image, img->mem, 0);
    VkImageSubresource subr = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0};
    VkSubresourceLayout lay;
    g_vk.GetImageSubresourceLayout(g_dev, img->image, &subr, &lay);
    img->offset = lay.offset;
    img->row_pitch = lay.rowPitch;
    if (g_vk.MapMemory(g_dev, img->mem, 0, req.size, 0, &img->map) != VK_SUCCESS) {
        img->map = NULL;
        vkp_image_destroy(img);
        return NULL;
    }
    return img;
}

void vkp_image_upload_shm(struct vkp_image *img, const void *data, int stride) {
    if (!img || !img->map || !data) return;
    size_t rowbytes = (size_t)img->w * 4;
    if ((size_t)stride < rowbytes) rowbytes = (size_t)stride;
    for (int y = 0; y < img->h; y++)
        memcpy((uint8_t *)img->map + img->offset + (size_t)y * img->row_pitch,
               (const uint8_t *)data + (size_t)y * stride, rowbytes);
}

int vkp_image_width(const struct vkp_image *img) { return img ? img->w : 0; }
int vkp_image_height(const struct vkp_image *img) { return img ? img->h : 0; }

void vkp_image_destroy(struct vkp_image *img) {
    if (!img) return;
    if (g_dev) {
        blendp_forget_image(img->image);
        if (img->map) g_vk.UnmapMemory(g_dev, img->mem);
        if (img->image) g_vk.DestroyImage(g_dev, img->image, NULL);
        if (img->mem) g_vk.FreeMemory(g_dev, img->mem, NULL);
    }
    free(img);
}

static int draw_to_blit(const struct vkp_draw *d, VkImageBlit *blit) {
    const float L = 0.0f, T = 0.0f, R = (float)g_extent.width, B = (float)g_extent.height;
    const float k = g_map.k;
    float x0 = g_map.off_x + d->dx * k, y0 = g_map.off_y + d->dy * k;
    float x1 = g_map.off_x + (d->dx + d->dw) * k, y1 = g_map.off_y + (d->dy + d->dh) * k;
    float sx0 = d->sx, sy0 = d->sy, sx1 = d->sx + d->sw, sy1 = d->sy + d->sh;

    if (x1 <= x0 || y1 <= y0 || sx1 <= sx0 || sy1 <= sy0) return 0;
    if (x0 < L) { sx0 += (L - x0) / (x1 - x0) * (sx1 - sx0); x0 = L; }
    if (y0 < T) { sy0 += (T - y0) / (y1 - y0) * (sy1 - sy0); y0 = T; }
    if (x1 > R) { sx1 -= (x1 - R) / (x1 - x0) * (sx1 - sx0); x1 = R; }
    if (y1 > B) { sy1 -= (y1 - B) / (y1 - y0) * (sy1 - sy0); y1 = B; }

    int ix0 = (int)(x0 + 0.5f), iy0 = (int)(y0 + 0.5f), ix1 = (int)(x1 + 0.5f), iy1 = (int)(y1 + 0.5f);
    int isx0 = (int)sx0, isy0 = (int)sy0, isx1 = (int)(sx1 + 0.5f), isy1 = (int)(sy1 + 0.5f);
    if (isx0 < 0) isx0 = 0;
    if (isy0 < 0) isy0 = 0;
    if (isx1 > d->img->w) isx1 = d->img->w;
    if (isy1 > d->img->h) isy1 = d->img->h;
    if (ix1 <= ix0 || iy1 <= iy0 || isx1 <= isx0 || isy1 <= isy0) return 0;
    *blit = (VkImageBlit){.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                          .srcOffsets = {{isx0, isy0, 0}, {isx1, isy1, 1}},
                          .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                          .dstOffsets = {{ix0, iy0, 0}, {ix1, iy1, 1}}};
    return 1;
}

static void record_draw(VkCommandBuffer cmd, const struct vkp_draw *d, const VkImageBlit *blit, VkImage target) {
    const struct vkp_image *im = d->img;
    if (d->blend && im->sampled &&
        blendp_draw(cmd, im->image, im->fmt, im->w, im->h, !im->dmabuf, target, g_swap_fmt,
                    (int)g_extent.width, (int)g_extent.height, blit) == 0)
        return;
    g_vk.CmdBlitImage(cmd, im->image, im->dmabuf ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : VK_IMAGE_LAYOUT_GENERAL,
                      target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, blit, im->filter);
}

static void device_lost(const char *where) {
    if (g_dev_state == -2) return;
    g_dev_state = -2;
    LOGE("GPU device lost in %s: the compositor has stopped presenting", where);
    for (uint32_t i = 0; i < g_nimg; i++) blendp_forget_image(g_images[i]);
    if (g_swapchain) g_vk.DestroySwapchainKHR(g_dev, g_swapchain, NULL);
    g_swapchain = VK_NULL_HANDLE;
    if (g_surface) g_vk.DestroySurfaceKHR(g_inst, g_surface, NULL);
    g_surface = VK_NULL_HANDLE;
    free(g_images);
    g_images = NULL;
    g_nimg = 0;
}

static int acquire_image(uint32_t *img) {
    for (int attempt = 0; ; attempt++) {
        if (!g_swapchain && swap_init() != 0) return -1;
        VkResult ar = g_vk.AcquireNextImageKHR(g_dev, g_swapchain, 1000000000ULL, g_acq, VK_NULL_HANDLE, img);
        if (ar == VK_SUCCESS || ar == VK_SUBOPTIMAL_KHR) return 0;
        if (ar == VK_ERROR_DEVICE_LOST) { device_lost("acquire"); return -1; }
        if (ar == VK_ERROR_OUT_OF_DATE_KHR || ar == VK_ERROR_SURFACE_LOST_KHR) {
            LOGI("screen surface %s on acquire: rebuilding the swapchain", vk_result_name(ar));
            destroy_swapchain();
            if (attempt == 0) continue;
            return -1;
        }
        LOGE("vkAcquireNextImageKHR failed (%s)", vk_result_name(ar));
        if (ar != VK_TIMEOUT && ar != VK_NOT_READY) destroy_swapchain();
        return -1;
    }
}

static void check_surface_changed(void) {
    if (!g_swapchain || !g_surface || g_dev_state != 1) return;
    const int64_t now = now_ns();
    if (now - g_surface_checked_ns < 100000000LL) return;
    g_surface_checked_ns = now;
    VkSurfaceCapabilitiesKHR caps;
    if (g_vk.GetPhysicalDeviceSurfaceCapabilitiesKHR(g_pd, g_surface, &caps) != VK_SUCCESS) return;
    if (caps.currentExtent.width == g_caps_extent.width && caps.currentExtent.height == g_caps_extent.height &&
        caps.currentTransform == g_surface_transform)
        return;
    LOGI("screen surface changed (%ux%u -> %ux%u): rebuilding the swapchain", g_caps_extent.width,
         g_caps_extent.height, caps.currentExtent.width, caps.currentExtent.height);
    destroy_swapchain();
}

static int covers_scene(const struct vkp_draw *d, int scene_w, int scene_h) {
    return d->img && !d->blend && d->dx <= 0 && d->dy <= 0 && d->dx + d->dw >= scene_w && d->dy + d->dh >= scene_h;
}

int vkp_render(int scene_w, int scene_h, const struct vkp_draw *draws, int n) {
    vkp_apply_window_request();
    check_surface_changed();
    if (g_dev_state == -2 || dev_init() != 0 || !g_window || scene_w <= 0 || scene_h <= 0) return -1;

    uint32_t img = 0;
    if (acquire_image(&img) != 0) return -1;
    update_map(scene_w, scene_h);

    VkCommandBuffer cmd = g_cmd;
    g_vk.ResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo bi = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                   .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    g_vk.BeginCommandBuffer(cmd, &bi);
    blendp_begin_frame();
    VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

    int first = 0;
    for (int i = n - 1; i >= 0; i--)
        if (covers_scene(&draws[i], scene_w, scene_h)) { first = i; break; }

    VkImageMemoryBarrier *acquire = calloc((size_t)n + 1, sizeof(*acquire));
    VkImageMemoryBarrier *release = calloc((size_t)n + 1, sizeof(*release));
    if (!acquire || !release) {
        free(acquire); free(release);
        g_vk.EndCommandBuffer(cmd);
        return -1;
    }
    int na = 0, nr = 0;
    acquire[na++] = (VkImageMemoryBarrier){
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = g_images[img], .subresourceRange = range, .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT};
    release[nr++] = (VkImageMemoryBarrier){
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .image = g_images[img], .subresourceRange = range,
        .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT};
    for (int i = first; i < n; i++) {
        struct vkp_image *im = draws[i].img;
        int seen = 0;
        for (int j = first; j < i; j++) if (draws[j].img == im) { seen = 1; break; }
        if (seen || !im) continue;
        if (im->dmabuf) {
            acquire[na++] = (VkImageMemoryBarrier){
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
                .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                .srcQueueFamilyIndex = g_foreign_qfam, .dstQueueFamilyIndex = g_qfam,
                .image = im->image, .subresourceRange = range, .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT};
            release[nr++] = (VkImageMemoryBarrier){
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                .srcQueueFamilyIndex = g_qfam, .dstQueueFamilyIndex = g_foreign_qfam,
                .image = im->image, .subresourceRange = range, .srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT};
        } else if (!im->in_general) {
            acquire[na++] = (VkImageMemoryBarrier){
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, .oldLayout = VK_IMAGE_LAYOUT_PREINITIALIZED,
                .newLayout = VK_IMAGE_LAYOUT_GENERAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = im->image, .subresourceRange = range,
                .srcAccessMask = VK_ACCESS_HOST_WRITE_BIT, .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT};
            im->in_general = 1;
        }
    }
    g_vk.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, (uint32_t)na, acquire);

    VkImageBlit cover;
    const int covered = first < n && covers_scene(&draws[first], scene_w, scene_h) && draw_to_blit(&draws[first], &cover);
    if (!covered || !record_bars_clear(cmd, g_images[img], &cover)) {
        VkClearColorValue black = {.float32 = {0.0f, 0.0f, 0.0f, 1.0f}};
        g_vk.CmdClearColorImage(cmd, g_images[img], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &black, 1, &range);
    }
    VkMemoryBarrier mb = {.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER, .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                          .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT};
    g_vk.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &mb, 0, NULL, 0, NULL);

    for (int i = first; i < n; i++) {
        VkImageBlit blit;
        if (!draws[i].img || !draw_to_blit(&draws[i], &blit)) continue;
        record_draw(cmd, &draws[i], &blit, g_images[img]);
    }
    g_vk.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, NULL, 0, NULL, (uint32_t)nr, release);
    free(acquire);
    free(release);
    g_vk.EndCommandBuffer(cmd);

    VkPipelineStageFlags wait = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo si = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .waitSemaphoreCount = 1, .pWaitSemaphores = &g_acq,
                       .pWaitDstStageMask = &wait, .commandBufferCount = 1, .pCommandBuffers = &cmd,
                       .signalSemaphoreCount = 1, .pSignalSemaphores = &g_rnd};
    g_vk.ResetFences(g_dev, 1, &g_fence);
    VkResult qr = g_vk.QueueSubmit(g_queue, 1, &si, g_fence);
    if (qr != VK_SUCCESS) {
        if (qr == VK_ERROR_DEVICE_LOST) device_lost("submit");
        else { LOGE("vkQueueSubmit failed (%s)", vk_result_name(qr)); destroy_swapchain(); }
        return -1;
    }
    VkPresentInfoKHR pi = {.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, .waitSemaphoreCount = 1,
                           .pWaitSemaphores = &g_rnd, .swapchainCount = 1, .pSwapchains = &g_swapchain,
                           .pImageIndices = &img};
    VkResult pr = g_vk.QueuePresentKHR(g_queue, &pi);
    VkResult fr = g_vk.WaitForFences(g_dev, 1, &g_fence, VK_TRUE, UINT64_MAX);
    if (fr == VK_ERROR_DEVICE_LOST || pr == VK_ERROR_DEVICE_LOST) { device_lost("present"); return -1; }
    if (pr == VK_ERROR_OUT_OF_DATE_KHR || pr == VK_ERROR_SURFACE_LOST_KHR) {
        LOGI("screen surface %s on present: rebuilding the swapchain", vk_result_name(pr));
        destroy_swapchain();
        return -1;
    }
    if (pr != VK_SUCCESS && pr != VK_SUBOPTIMAL_KHR) {
        LOGE("vkQueuePresentKHR failed (%s)", vk_result_name(pr));
        destroy_swapchain();
        return -1;
    }
    return 0;
}
