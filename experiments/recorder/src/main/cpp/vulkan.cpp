#include "graphics.h"
#include <vulkan/vulkan.h>
#include <vector>
#include <unordered_map>
#include <algorithm>
#include <cstring>

namespace {
void check(VkResult result,const char* operation) {
    if(result!=VK_SUCCESS) throw std::runtime_error(std::string(operation)+": VkResult="+std::to_string(result));
}
struct Imported { VkImage image=VK_NULL_HANDLE; VkDeviceMemory memory=VK_NULL_HANDLE; AHardwareBuffer* buffer=nullptr; };
struct Frame { VkCommandBuffer command=VK_NULL_HANDLE; VkFence fence=VK_NULL_HANDLE; VkSemaphore available=VK_NULL_HANDLE; bool submitted=false; ImagePtr input=imagePtr(); };
class Vulkan final: public Renderer {
    VkInstance instance=VK_NULL_HANDLE;
    VkPhysicalDevice physical=VK_NULL_HANDLE;
    VkDevice device=VK_NULL_HANDLE;
    VkSurfaceKHR surface=VK_NULL_HANDLE;
    VkSwapchainKHR swapchain=VK_NULL_HANDLE;
    VkCommandPool pool=VK_NULL_HANDLE;
    VkQueue queue=VK_NULL_HANDLE;
    uint32_t family=0, frame=0;
    VkExtent2D extent{};
    std::vector<VkImage> outputs;
    std::vector<VkSemaphore> complete;
    Frame frames[2];
    std::unordered_map<AHardwareBuffer*,Imported> cache;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID bufferProperties=nullptr;
    bool supports(VkPhysicalDevice candidate, const char* name) {
        uint32_t count=0; check(vkEnumerateDeviceExtensionProperties(candidate,nullptr,&count,nullptr),"enumerate extensions");
        std::vector<VkExtensionProperties> props(count); check(vkEnumerateDeviceExtensionProperties(candidate,nullptr,&count,props.data()),"enumerate extensions");
        return std::any_of(props.begin(),props.end(),[&](auto& p){return !strcmp(p.extensionName,name);});
    }
    Imported& imported(AHardwareBuffer* buffer) {
        auto found=cache.find(buffer); if(found!=cache.end()) return found->second;
        require(cache.size()<16,"Input buffer pool grew unexpectedly");
        VkAndroidHardwareBufferFormatPropertiesANDROID format{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
        VkAndroidHardwareBufferPropertiesANDROID properties{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID}; properties.pNext=&format;
        check(bufferProperties(device,buffer,&properties),"hardware buffer properties");
        require(format.format!=VK_FORMAT_UNDEFINED && (format.formatFeatures&VK_FORMAT_FEATURE_BLIT_SRC_BIT),"RGBA input cannot be blitted by Vulkan; no GLES fallback");
        AHardwareBuffer_Desc desc{}; AHardwareBuffer_describe(buffer,&desc);
        require(desc.layers==1 && desc.width && desc.height,"Unsupported hardware buffer dimensions");
        auto& entry=cache[buffer]; entry.buffer=buffer; AHardwareBuffer_acquire(buffer);
        VkExternalMemoryImageCreateInfo external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
        external.handleTypes=VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkImageCreateInfo image{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO}; image.pNext=&external;
        image.imageType=VK_IMAGE_TYPE_2D; image.format=format.format; image.extent={desc.width,desc.height,1};
        image.mipLevels=1; image.arrayLayers=1; image.samples=VK_SAMPLE_COUNT_1_BIT; image.tiling=VK_IMAGE_TILING_OPTIMAL;
        image.usage=VK_IMAGE_USAGE_TRANSFER_SRC_BIT; image.sharingMode=VK_SHARING_MODE_EXCLUSIVE; image.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
        check(vkCreateImage(device,&image,nullptr,&entry.image),"import image creation");
        VkImportAndroidHardwareBufferInfoANDROID import{VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID}; import.buffer=buffer;
        VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO}; dedicated.pNext=&import; dedicated.image=entry.image;
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; allocation.pNext=&dedicated; allocation.allocationSize=properties.allocationSize;
        require(properties.memoryTypeBits!=0,"No compatible hardware buffer memory type");
        allocation.memoryTypeIndex=static_cast<uint32_t>(__builtin_ctz(properties.memoryTypeBits));
        check(vkAllocateMemory(device,&allocation,nullptr,&entry.memory),"import hardware buffer memory");
        check(vkBindImageMemory(device,entry.image,entry.memory,0),"bind imported buffer");
        return entry;
    }
    void wait(Frame& f) {
        if(f.submitted) { check(vkWaitForFences(device,1,&f.fence,VK_TRUE,2'000'000'000ULL),"input completion fence"); f.submitted=false; }
        f.input.reset();
    }
    static VkImageMemoryBarrier barrier(VkImage image,VkImageLayout before,VkImageLayout after,VkAccessFlags src,VkAccessFlags dst,uint32_t from=VK_QUEUE_FAMILY_IGNORED,uint32_t to=VK_QUEUE_FAMILY_IGNORED) {
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}; b.srcAccessMask=src; b.dstAccessMask=dst;
        b.oldLayout=before; b.newLayout=after; b.srcQueueFamilyIndex=from; b.dstQueueFamilyIndex=to; b.image=image;
        b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}; return b;
    }
public:
    void init(ANativeWindow* window,int width,int height) override {
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.pApplicationName="RecorderBackendTest"; app.apiVersion=VK_API_VERSION_1_1;
        const char* instanceExtensions[]={VK_KHR_SURFACE_EXTENSION_NAME,VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
        VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ci.pApplicationInfo=&app; ci.enabledExtensionCount=2; ci.ppEnabledExtensionNames=instanceExtensions;
        check(vkCreateInstance(&ci,nullptr,&instance),"create Vulkan instance");
        VkAndroidSurfaceCreateInfoKHR android{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR}; android.window=window;
        check(vkCreateAndroidSurfaceKHR(instance,&android,nullptr,&surface),"encoder Vulkan surface");
        const char* extensions[]={VK_KHR_SWAPCHAIN_EXTENSION_NAME,VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
            VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME};
        uint32_t count=0; check(vkEnumeratePhysicalDevices(instance,&count,nullptr),"enumerate GPU");
        std::vector<VkPhysicalDevice> devices(count); check(vkEnumeratePhysicalDevices(instance,&count,devices.data()),"enumerate GPU");
        for(auto candidate:devices) {
            VkPhysicalDeviceProperties props{}; vkGetPhysicalDeviceProperties(candidate,&props);
            if(props.apiVersion<VK_API_VERSION_1_1) continue;
            bool ok=true; for(auto extension:extensions) if(!supports(candidate,extension)) ok=false; if(!ok) continue;
            uint32_t n=0; vkGetPhysicalDeviceQueueFamilyProperties(candidate,&n,nullptr); std::vector<VkQueueFamilyProperties> families(n); vkGetPhysicalDeviceQueueFamilyProperties(candidate,&n,families.data());
            for(uint32_t i=0;i<n;i++) {
                VkBool32 present=false; check(vkGetPhysicalDeviceSurfaceSupportKHR(candidate,i,surface,&present),"encoder present support");
                if(present && (families[i].queueFlags&VK_QUEUE_GRAPHICS_BIT)) {
                    physical=candidate; family=i; description=std::string(props.deviceName)+" / Vulkan "+std::to_string(VK_VERSION_MAJOR(props.apiVersion))+"."+std::to_string(VK_VERSION_MINOR(props.apiVersion))+" / AHB import + GPU blit + encoder WSI"; break;
                }
            }
            if(physical) break;
        }
        require(physical!=VK_NULL_HANDLE,"Missing Vulkan 1.1, encoder WSI, AHB, foreign-queue or display-timing support; no GLES fallback");
        float priority=1; VkDeviceQueueCreateInfo qc{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; qc.queueFamilyIndex=family; qc.queueCount=1; qc.pQueuePriorities=&priority;
        VkDeviceCreateInfo dc{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; dc.queueCreateInfoCount=1; dc.pQueueCreateInfos=&qc; dc.enabledExtensionCount=4; dc.ppEnabledExtensionNames=extensions;
        check(vkCreateDevice(physical,&dc,nullptr,&device),"create Vulkan device"); vkGetDeviceQueue(device,family,0,&queue);
        bufferProperties=reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(vkGetDeviceProcAddr(device,"vkGetAndroidHardwareBufferPropertiesANDROID"));
        require(bufferProperties!=nullptr,"AHB import function unavailable");
        VkSurfaceCapabilitiesKHR caps{}; check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical,surface,&caps),"encoder surface capabilities");
        require(caps.supportedUsageFlags&VK_IMAGE_USAGE_TRANSFER_DST_BIT,"Encoder swapchain does not support Vulkan transfer destination");
        extent={static_cast<uint32_t>(width),static_cast<uint32_t>(height)};
        if(caps.currentExtent.width!=UINT32_MAX) require(caps.currentExtent.width==extent.width && caps.currentExtent.height==extent.height,"Encoder surface dimensions differ from requested dimensions");
        require(extent.width>=caps.minImageExtent.width && extent.width<=caps.maxImageExtent.width && extent.height>=caps.minImageExtent.height && extent.height<=caps.maxImageExtent.height,"Encoder surface size unsupported");
        require(caps.supportedTransforms&VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR,"Encoder requires an unsupported transform");
        uint32_t n=0; check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical,surface,&n,nullptr),"encoder formats"); std::vector<VkSurfaceFormatKHR> formats(n); check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical,surface,&n,formats.data()),"encoder formats");
        VkSurfaceFormatKHR format{}; bool found=false;
        for(auto f:formats) {
            if(f.format!=VK_FORMAT_R8G8B8A8_UNORM && f.format!=VK_FORMAT_B8G8R8A8_UNORM) continue;
            if(f.colorSpace!=VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) continue;
            VkFormatProperties p{}; vkGetPhysicalDeviceFormatProperties(physical,f.format,&p);
            if(p.optimalTilingFeatures&VK_FORMAT_FEATURE_BLIT_DST_BIT) { format=f; found=true; break; }
        }
        require(found,"No SDR RGBA Vulkan encoder surface format");
        uint32_t images=std::max(3u,caps.minImageCount); if(caps.maxImageCount) images=std::min(images,caps.maxImageCount);
        VkSwapchainCreateInfoKHR sc{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR}; sc.surface=surface; sc.minImageCount=images;
        sc.imageFormat=format.format; sc.imageColorSpace=format.colorSpace; sc.imageExtent=extent; sc.imageArrayLayers=1;
        sc.imageUsage=VK_IMAGE_USAGE_TRANSFER_DST_BIT; sc.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE; sc.preTransform=VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
        sc.compositeAlpha=(caps.supportedCompositeAlpha&VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)?VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR:VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
        require(caps.supportedCompositeAlpha&sc.compositeAlpha,"Encoder surface alpha mode unsupported");
        sc.presentMode=VK_PRESENT_MODE_FIFO_KHR; sc.clipped=VK_TRUE;
        check(vkCreateSwapchainKHR(device,&sc,nullptr,&swapchain),"encoder swapchain");
        check(vkGetSwapchainImagesKHR(device,swapchain,&n,nullptr),"swapchain images"); outputs.resize(n); complete.resize(n,VK_NULL_HANDLE);
        check(vkGetSwapchainImagesKHR(device,swapchain,&n,outputs.data()),"swapchain images");
        VkCommandPoolCreateInfo pc{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; pc.queueFamilyIndex=family; pc.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        check(vkCreateCommandPool(device,&pc,nullptr,&pool),"command pool");
        VkSemaphoreCreateInfo sem{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        for(auto& s:complete) check(vkCreateSemaphore(device,&sem,nullptr,&s),"present semaphore");
        for(auto& f:frames) {
            VkCommandBufferAllocateInfo ac{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; ac.commandPool=pool; ac.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ac.commandBufferCount=1;
            check(vkAllocateCommandBuffers(device,&ac,&f.command),"command buffer");
            VkFenceCreateInfo fc{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; check(vkCreateFence(device,&fc,nullptr,&f.fence),"completion fence");
            check(vkCreateSemaphore(device,&sem,nullptr,&f.available),"acquire semaphore");
        }
    }
    void render(ImagePtr input,int64_t timestamp) override {
        Frame& f=frames[frame%2]; wait(f); f.input=std::move(input);
        AHardwareBuffer* buffer=nullptr; require(AImage_getHardwareBuffer(f.input.get(),&buffer)==AMEDIA_OK && buffer,"Vulkan input has no hardware buffer");
        Imported& source=imported(buffer); AImageCropRect crop{};
        require(AImage_getCropRect(f.input.get(),&crop)==AMEDIA_OK,"Missing input crop");
        uint32_t index=0; VkResult acquired=vkAcquireNextImageKHR(device,swapchain,2'000'000'000ULL,f.available,VK_NULL_HANDLE,&index);
        if(acquired!=VK_SUBOPTIMAL_KHR) check(acquired,"acquire encoder image");
        check(vkResetCommandBuffer(f.command,0),"reset command buffer");
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(f.command,&begin),"begin frame");
        // Synchronous AImage acquisition has waited the producer fence. Import its
        // preserved pixels from the foreign queue, then return ownership after the copy.
        VkImageMemoryBarrier before[]={barrier(source.image,VK_IMAGE_LAYOUT_GENERAL,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,0,VK_ACCESS_TRANSFER_READ_BIT,VK_QUEUE_FAMILY_FOREIGN_EXT,family),
            barrier(outputs[index],VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,0,VK_ACCESS_TRANSFER_WRITE_BIT)};
        vkCmdPipelineBarrier(f.command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,2,before);
        VkImageBlit blit{}; blit.srcSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1}; blit.dstSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
        blit.srcOffsets[0]={crop.left,crop.top,0}; blit.srcOffsets[1]={crop.right,crop.bottom,1};
        blit.dstOffsets[1]={static_cast<int32_t>(extent.width),static_cast<int32_t>(extent.height),1};
        vkCmdBlitImage(f.command,source.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,outputs[index],VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&blit,VK_FILTER_NEAREST);
        VkImageMemoryBarrier after[]={barrier(source.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_IMAGE_LAYOUT_GENERAL,VK_ACCESS_TRANSFER_READ_BIT,0,family,VK_QUEUE_FAMILY_FOREIGN_EXT),
            barrier(outputs[index],VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,VK_ACCESS_TRANSFER_WRITE_BIT,0)};
        vkCmdPipelineBarrier(f.command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,0,0,nullptr,0,nullptr,2,after);
        check(vkEndCommandBuffer(f.command),"end frame"); check(vkResetFences(device,1,&f.fence),"reset frame fence");
        VkPipelineStageFlags stage=VK_PIPELINE_STAGE_TRANSFER_BIT;
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO}; submit.waitSemaphoreCount=1; submit.pWaitSemaphores=&f.available; submit.pWaitDstStageMask=&stage;
        submit.commandBufferCount=1; submit.pCommandBuffers=&f.command; submit.signalSemaphoreCount=1; submit.pSignalSemaphores=&complete[index];
        check(vkQueueSubmit(queue,1,&submit,f.fence),"submit GPU copy"); f.submitted=true;
        VkPresentTimeGOOGLE time{++frame,static_cast<uint64_t>(timestamp)};
        VkPresentTimesInfoGOOGLE times{VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE}; times.swapchainCount=1; times.pTimes=&time;
        VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR}; present.pNext=&times; present.waitSemaphoreCount=1; present.pWaitSemaphores=&complete[index];
        present.swapchainCount=1; present.pSwapchains=&swapchain; present.pImageIndices=&index;
        VkResult result=vkQueuePresentKHR(queue,&present); if(result!=VK_SUBOPTIMAL_KHR) check(result,"present encoder frame");
    }
    void finish() override { for(auto& f:frames) wait(f); }
    ~Vulkan() override {
        if(device) {
            vkDeviceWaitIdle(device);
            for(auto& f:frames) { f.input.reset(); if(f.fence) vkDestroyFence(device,f.fence,nullptr); if(f.available) vkDestroySemaphore(device,f.available,nullptr); }
            for(auto& [key,e]:cache) { if(e.image) vkDestroyImage(device,e.image,nullptr); if(e.memory) vkFreeMemory(device,e.memory,nullptr); if(e.buffer) AHardwareBuffer_release(e.buffer); }
            for(auto s:complete) if(s) vkDestroySemaphore(device,s,nullptr);
            if(pool) vkDestroyCommandPool(device,pool,nullptr); if(swapchain) vkDestroySwapchainKHR(device,swapchain,nullptr);
            vkDestroyDevice(device,nullptr);
        }
        if(surface) vkDestroySurfaceKHR(instance,surface,nullptr); if(instance) vkDestroyInstance(instance,nullptr);
    }
};
}
std::unique_ptr<Renderer> makeVulkan() { return std::make_unique<Vulkan>(); }
