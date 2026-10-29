/* Derived from DroidDeck waylandcomp/src/vk_loader.h (GPL-3.0); trimmed for DDO Mobile, 2026-10. */
#ifndef VK_LOADER_H
#define VK_LOADER_H
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#define VK_GLOBAL_FUNCS(X) X(CreateInstance)

#define VK_INSTANCE_FUNCS(X) \
    X(EnumeratePhysicalDevices) X(GetPhysicalDeviceProperties) \
    X(GetPhysicalDeviceQueueFamilyProperties) X(GetPhysicalDeviceSurfaceSupportKHR) \
    X(GetPhysicalDeviceSurfaceCapabilitiesKHR) X(GetPhysicalDeviceSurfaceFormatsKHR) \
    X(CreateAndroidSurfaceKHR) X(DestroySurfaceKHR) X(CreateDevice) \
    X(GetDeviceProcAddr) X(EnumerateDeviceExtensionProperties) \
    X(GetPhysicalDeviceMemoryProperties) X(GetPhysicalDeviceFormatProperties2) \
    X(GetPhysicalDeviceImageFormatProperties2)

#define VK_DEVICE_FUNCS(X) \
    X(GetDeviceQueue) X(CreateSwapchainKHR) X(DestroySwapchainKHR) X(GetSwapchainImagesKHR) \
    X(CreateCommandPool) X(AllocateCommandBuffers) X(CreateSemaphore) X(DestroySemaphore) X(CreateFence) \
    X(DeviceWaitIdle) X(CreateImage) X(GetImageMemoryRequirements) X(AllocateMemory) \
    X(BindImageMemory) X(DestroyImage) X(FreeMemory) X(AcquireNextImageKHR) \
    X(ResetCommandBuffer) X(BeginCommandBuffer) X(CmdPipelineBarrier) X(CmdBlitImage) \
    X(EndCommandBuffer) X(ResetFences) X(QueueSubmit) X(QueuePresentKHR) X(WaitForFences) \
    X(GetMemoryFdPropertiesKHR) X(GetImageSubresourceLayout) X(MapMemory) X(UnmapMemory) \
    X(CmdClearColorImage) \
    X(CreateShaderModule) X(DestroyShaderModule) X(CreatePipelineLayout) X(DestroyPipelineLayout) \
    X(CreateGraphicsPipelines) X(CreateRenderPass) X(DestroyRenderPass) \
    X(CreateFramebuffer) X(DestroyFramebuffer) X(CreateImageView) X(DestroyImageView) \
    X(CreateSampler) X(DestroySampler) X(CreateDescriptorSetLayout) X(DestroyDescriptorSetLayout) \
    X(CreateDescriptorPool) X(DestroyDescriptorPool) X(AllocateDescriptorSets) X(FreeDescriptorSets) \
    X(UpdateDescriptorSets) X(CmdBeginRenderPass) X(CmdEndRenderPass) X(CmdBindPipeline) \
    X(CmdBindDescriptorSets) X(CmdPushConstants) X(CmdDraw) X(CmdSetViewport) X(CmdSetScissor)

struct vk_api {
#define X(n) PFN_vk##n n;
    VK_GLOBAL_FUNCS(X) VK_INSTANCE_FUNCS(X) VK_DEVICE_FUNCS(X)
#undef X
};
extern struct vk_api g_vk;

int vk_loader_open(const char *driver_dir, const char *driver_name, const char *native_lib_dir);
void vk_loader_load_instance(VkInstance instance);
void vk_loader_load_device(VkDevice device);

#endif
