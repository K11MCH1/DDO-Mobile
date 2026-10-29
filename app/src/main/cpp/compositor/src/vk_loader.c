/* Derived from DroidDeck waylandcomp/src/vk_loader.c (GPL-3.0); modified for DDO Mobile, 2026-10. */
#define _GNU_SOURCE
#include "vk_loader.h"
#include "ddo_log.h"
#include <adrenotools/driver.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>

struct vk_api g_vk;
static void *g_handle;
static PFN_vkGetInstanceProcAddr g_gip;

int vk_loader_open(const char *driver_dir, const char *driver_name, const char *native_lib_dir) {
    if (g_handle) return g_gip ? 0 : -1;
    if (driver_dir && driver_name && native_lib_dir) {
        char *tmpdir = NULL;
        if (asprintf(&tmpdir, "%stemp", driver_dir) < 0) tmpdir = NULL;
        if (tmpdir) mkdir(tmpdir, S_IRWXU | S_IRWXG);
        g_handle = adrenotools_open_libvulkan(RTLD_LOCAL | RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, tmpdir,
                                              native_lib_dir, driver_dir, driver_name, NULL, NULL);
        free(tmpdir);
        if (g_handle) LOGI("vulkan: adrenotools driver %s%s", driver_dir, driver_name);
        else LOGE("vulkan: adrenotools could not load %s%s (hooks from %s); using the system driver",
                  driver_dir, driver_name, native_lib_dir);
    }
    if (!g_handle) g_handle = dlopen("libvulkan.so", RTLD_LOCAL | RTLD_NOW);
    if (!g_handle) { LOGE("vulkan: no libvulkan.so"); return -1; }
    g_gip = (PFN_vkGetInstanceProcAddr)dlsym(g_handle, "vkGetInstanceProcAddr");
    if (!g_gip) { LOGE("vulkan: no vkGetInstanceProcAddr"); return -1; }
#define X(n) g_vk.n = (PFN_vk##n)g_gip(NULL, "vk" #n);
    VK_GLOBAL_FUNCS(X)
#undef X
    return g_vk.CreateInstance ? 0 : -1;
}

void vk_loader_load_instance(VkInstance instance) {
#define X(n) g_vk.n = (PFN_vk##n)g_gip(instance, "vk" #n);
    VK_INSTANCE_FUNCS(X)
#undef X
}

void vk_loader_load_device(VkDevice device) {
#define X(n) g_vk.n = (PFN_vk##n)g_vk.GetDeviceProcAddr(device, "vk" #n);
    VK_DEVICE_FUNCS(X)
#undef X
}
