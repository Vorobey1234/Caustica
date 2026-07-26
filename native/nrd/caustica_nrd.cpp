#include <algorithm>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <iterator>
#include <new>

#include "NRD.h"
#include "NRDSettings.h"
#include "NRI.h"
#include "Extensions/NRIHelper.h"
#include "Extensions/NRIRayTracing.h"
#include "Extensions/NRIWrapperVK.h"
#include "NRDIntegration.h"
#include "NRDIntegration.hpp"

namespace {
constexpr nrd::Identifier RELAX_DENOISER_ID = 1;
constexpr nrd::Identifier REBLUR_DENOISER_ID = 2;
constexpr nrd::Identifier REFERENCE_DENOISER_ID = 3;
thread_local char lastError[2048] = {};

void setLastError(const char* message) {
    std::snprintf(lastError, sizeof(lastError), "%s", message ? message : "unknown NRD error");
}

void NRI_CALL messageCallback(nri::Message messageType, const char* file, uint32_t line,
        const char* message, void*) {
    const char* severity = messageType == nri::Message::ERROR ? "ERROR"
            : messageType == nri::Message::WARNING ? "WARNING" : "INFO";
    std::snprintf(lastError, sizeof(lastError), "NRI %s (%s:%u): %s", severity,
            file ? file : "?", line, message ? message : "");
    std::fprintf(stderr, "NRD/NRI %s (%s:%u): %s\n", severity,
            file ? file : "?", line, message ? message : "");
}

void NRI_CALL doNotBreakIntoJvm(void*) {
    // NRI's default callback executes DebugBreak() for UNSUPPORTED/FAILURE. That SEH exception
    // cannot safely cross Java's FFM downcall and terminates HotSpot. Returning is intentional:
    // the NRI call still returns its Result and the bridge propagates a normal failure to Java.
}

struct Context {
    nrd::Integration integration;
    uint16_t width = 0;
    uint16_t height = 0;
};

nrd::Resource texture(uint64_t image, int32_t format) {
    nrd::Resource resource = {};
    resource.vk.image = image;
    resource.vk.format = format;
    resource.state = {
        nri::AccessBits::SHADER_RESOURCE_STORAGE,
        nri::Layout::GENERAL,
        nri::StageBits::ALL
    };
    return resource;
}
}

extern "C" __declspec(dllexport) void* causticaNrdCreate(
        uint64_t vkInstance, uint64_t vkPhysicalDevice, uint64_t vkDevice,
        uint32_t graphicsQueueFamily, uint32_t width, uint32_t height) {
    lastError[0] = '\0';
    if (!vkInstance || !vkPhysicalDevice || !vkDevice || width == 0 || height == 0
            || width > 65535 || height > 65535) {
        setLastError("invalid Vulkan handle or NRD resource size");
        return nullptr;
    }

    try {
    Context* context = new (std::nothrow) Context();
    if (!context)
        return nullptr;

    nri::QueueFamilyVKDesc queueFamily = {};
    queueFamily.queueNum = 1;
    queueFamily.queueType = nri::QueueType::GRAPHICS;
    queueFamily.familyIndex = graphicsQueueFamily;

    nri::DeviceCreationVKDesc deviceDesc = {};
    deviceDesc.callbackInterface.MessageCallback = messageCallback;
    deviceDesc.callbackInterface.AbortExecution = doNotBreakIntoJvm;
    deviceDesc.vkInstance = reinterpret_cast<void*>(vkInstance);
    deviceDesc.vkPhysicalDevice = reinterpret_cast<void*>(vkPhysicalDevice);
    deviceDesc.vkDevice = reinterpret_cast<void*>(vkDevice);
    deviceDesc.queueFamilies = &queueFamily;
    deviceDesc.queueFamilyNum = 1;
    deviceDesc.minorVersion = 2;

    // NRI cannot rediscover which extensions were enabled on an existing logical device. Keep this
    // list exactly in sync with VulkanBackendMixin/the VulkanMod device log: claiming supported but
    // non-enabled aliases makes vkGetDeviceProcAddr return null during NRI initialization.
    static const char* const enabledDeviceExtensions[] = {
        "VK_KHR_dynamic_rendering",
        "VK_KHR_synchronization2",
        "VK_KHR_copy_commands2",
        "VK_EXT_extended_dynamic_state"
    };
    deviceDesc.vkExtensions.deviceExtensions = enabledDeviceExtensions;
    deviceDesc.vkExtensions.deviceExtensionNum =
            static_cast<uint32_t>(std::size(enabledDeviceExtensions));

    // Match the selectable methods exposed by NVIDIA's vk_denoise_nrd sample. Caustica currently
    // produces one combined beauty signal, so the diffuse variants are used for the two realtime
    // methods while REFERENCE accumulates that same signal without spatial filtering.
    nrd::DenoiserDesc denoisers[] = {
        {RELAX_DENOISER_ID, nrd::Denoiser::RELAX_DIFFUSE},
        {REBLUR_DENOISER_ID, nrd::Denoiser::REBLUR_DIFFUSE},
        {REFERENCE_DENOISER_ID, nrd::Denoiser::REFERENCE}
    };
    nrd::InstanceCreationDesc instanceDesc = {};
    instanceDesc.denoisers = denoisers;
    instanceDesc.denoisersNum = static_cast<uint32_t>(std::size(denoisers));

    nrd::IntegrationCreationDesc integrationDesc = {};
    std::strncpy(integrationDesc.name, "Caustica NRD", sizeof(integrationDesc.name) - 1);
    integrationDesc.resourceWidth = static_cast<uint16_t>(width);
    integrationDesc.resourceHeight = static_cast<uint16_t>(height);
    integrationDesc.queuedFrameNum = 3;
    integrationDesc.autoWaitForIdle = false;
    integrationDesc.demoteFloat32to16 = true;

    if (context->integration.RecreateVK(integrationDesc, instanceDesc, deviceDesc) != nrd::Result::SUCCESS) {
        if (!lastError[0]) setLastError("NRD Integration::RecreateVK failed");
        delete context;
        return nullptr;
    }

    nrd::RelaxSettings relaxSettings = {};
    relaxSettings.enableAntiFirefly = true;
    if (context->integration.SetDenoiserSettings(RELAX_DENOISER_ID, &relaxSettings) != nrd::Result::SUCCESS) {
        setLastError("NRD SetDenoiserSettings(RELAX_DIFFUSE) failed");
        delete context;
        return nullptr;
    }

    nrd::ReblurSettings reblurSettings = {};
    reblurSettings.enableAntiFirefly = true;
    if (context->integration.SetDenoiserSettings(REBLUR_DENOISER_ID, &reblurSettings) != nrd::Result::SUCCESS) {
        setLastError("NRD SetDenoiserSettings(REBLUR_DIFFUSE) failed");
        delete context;
        return nullptr;
    }

    nrd::ReferenceSettings referenceSettings = {};
    if (context->integration.SetDenoiserSettings(REFERENCE_DENOISER_ID, &referenceSettings) != nrd::Result::SUCCESS) {
        setLastError("NRD SetDenoiserSettings(REFERENCE) failed");
        delete context;
        return nullptr;
    }

    context->width = static_cast<uint16_t>(width);
    context->height = static_cast<uint16_t>(height);
    return context;
    } catch (...) {
        // Never allow a C++ exception to cross Java's FFM boundary.
        setLastError("C++ exception while creating NRD context");
        return nullptr;
    }
}

extern "C" __declspec(dllexport) int32_t causticaNrdDenoise(
        void* opaque, uint64_t vkCommandBuffer,
        uint64_t signalImage, uint64_t motionImage, uint64_t normalRoughnessImage,
        uint64_t viewZImage, uint64_t outputImage,
        const float* viewToClip, const float* viewToClipPrev,
        const float* worldToView, const float* worldToViewPrev,
        uint32_t frameIndex, int32_t method, int32_t resetHistory) {
    try {
    Context* context = static_cast<Context*>(opaque);
    if (!context || !vkCommandBuffer || !signalImage || !motionImage || !normalRoughnessImage
            || !viewZImage || !outputImage || !viewToClip || !viewToClipPrev
            || !worldToView || !worldToViewPrev) {
        return 0;
    }

    context->integration.NewFrame();
    nrd::CommonSettings common = {};
    std::memcpy(common.viewToClipMatrix, viewToClip, sizeof(common.viewToClipMatrix));
    std::memcpy(common.viewToClipMatrixPrev, viewToClipPrev, sizeof(common.viewToClipMatrixPrev));
    std::memcpy(common.worldToViewMatrix, worldToView, sizeof(common.worldToViewMatrix));
    std::memcpy(common.worldToViewMatrixPrev, worldToViewPrev, sizeof(common.worldToViewMatrixPrev));
    // Caustica writes non-jittered 2D screen-space MVs in render-pixel units and NRD consumes
    // normalized screen displacement. Convert pixels to UV while preserving NRD's
    // "old = new + MV" convention. A scale of 1 made even a one-pixel motion span the full screen,
    // producing alternating histories and a regular motion pattern.
    common.motionVectorScale[0] = 1.0f / context->width;
    common.motionVectorScale[1] = 1.0f / context->height;
    common.motionVectorScale[2] = 0.0f;
    common.isMotionVectorInWorldSpace = false;
    common.resourceSize[0] = context->width;
    common.resourceSize[1] = context->height;
    common.resourceSizePrev[0] = context->width;
    common.resourceSizePrev[1] = context->height;
    common.rectSize[0] = context->width;
    common.rectSize[1] = context->height;
    common.rectSizePrev[0] = context->width;
    common.rectSizePrev[1] = context->height;
    common.denoisingRange = 50000.0f;
    common.frameIndex = frameIndex;
    common.accumulationMode = resetHistory
            ? nrd::AccumulationMode::CLEAR_AND_RESTART
            : nrd::AccumulationMode::CONTINUE;

    if (context->integration.SetCommonSettings(common) != nrd::Result::SUCCESS)
    {
        setLastError("NRD SetCommonSettings failed");
        return 0;
    }

    const nrd::Identifier denoiserId = method == 1
            ? REBLUR_DENOISER_ID
            : method == 2 ? REFERENCE_DENOISER_ID : RELAX_DENOISER_ID;

    nrd::ResourceSnapshot resources = {};
    resources.restoreInitialState = true;

    // NRD's REFERENCE denoiser does not consume the common guides in its shader dispatches, but the
    // NRD Integration Vulkan wrapper still uses IN_NORMAL_ROUGHNESS as its first-frame native-texture
    // anchor before it asks NRD for dispatches. Leaving that slot null causes a native null dereference
    // inside Integration::Denoise on the first Reference frame. Always provide all common guide slots;
    // unused resources are ignored by the selected denoiser and add no denoising dispatch work.
    resources.SetResource(nrd::ResourceType::IN_MV, texture(motionImage, 83));
    resources.SetResource(nrd::ResourceType::IN_NORMAL_ROUGHNESS, texture(normalRoughnessImage, 97));
    resources.SetResource(nrd::ResourceType::IN_VIEWZ, texture(viewZImage, 100));

    if (denoiserId == REFERENCE_DENOISER_ID) {
        resources.SetResource(nrd::ResourceType::IN_SIGNAL, texture(signalImage, 97));
        resources.SetResource(nrd::ResourceType::OUT_SIGNAL, texture(outputImage, 97));
    } else {
        resources.SetResource(nrd::ResourceType::IN_DIFF_RADIANCE_HITDIST, texture(signalImage, 97));
        resources.SetResource(nrd::ResourceType::OUT_DIFF_RADIANCE_HITDIST, texture(outputImage, 97));
    }

    if (!resources.slots[static_cast<size_t>(nrd::ResourceType::IN_NORMAL_ROUGHNESS)]) {
        setLastError("NRD common-guide snapshot is missing IN_NORMAL_ROUGHNESS");
        return 0;
    }

    nri::CommandBufferVKDesc commandBuffer = {};
    commandBuffer.vkCommandBuffer = reinterpret_cast<void*>(vkCommandBuffer);
    commandBuffer.queueType = nri::QueueType::GRAPHICS;
    context->integration.DenoiseVK(&denoiserId, 1, commandBuffer, resources);
    return 1;
    } catch (...) {
        // Java will disable NRD and use its built-in fallback on a zero result.
        setLastError("C++ exception during NRD dispatch");
        return 0;
    }
}

extern "C" __declspec(dllexport) void causticaNrdDestroy(void* opaque) {
    try {
        delete static_cast<Context*>(opaque);
    } catch (...) {
        // Destruction is best-effort during renderer teardown; never unwind into the JVM.
    }
}

extern "C" __declspec(dllexport) const char* causticaNrdGetLastError() {
    return lastError[0] ? lastError : "no NRD error reported";
}
