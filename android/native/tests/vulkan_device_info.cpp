// android/native/tests/vulkan_device_info.cpp -- S1 acceptance item 2.
//
// Prints what this device and this engine are, before anything is rendered:
//
//   * the probe's own compile-time state (`__OPTIMIZE__`/`NDEBUG`): a build
//     that is not optimised silently invalidates every performance number
//     S1 collects, so it is reported rather than assumed;
//   * `spk_build_info()` and the `spk_capabilities` JSON verbatim -- tiers,
//     `max_pixels`, `max_texture_dimension_2d`, `backend.{gpu, math_mode,
//     unsupported_features, session_cache}`;
//   * every Vulkan physical device the loader exposes: name, api/driver
//     version, queue families, the four limits `vulkan_gpu.cpp` gates on
//     (`maxComputeWorkGroupInvocations`, `maxComputeWorkGroupSize[0]`,
//     `maxPerStageDescriptorStorageBuffers`, `maxDescriptorSetStorageBuffers`),
//     `maxStorageBufferRange`, the memory heaps and whether any memory type is
//     both host-visible and device-local (the UMA no-staging decision);
//   * the process's RAM as `MemTotal`, with the D9 budget formula's answer;
//   * `spk_memory_report` verbatim, the baseline the pool audit is compared
//     against after the S2 batching patch.
//
// Exit status is 0 when the probe ran to completion (even when a gate fails --
// the gates are printed, not enforced here) and 1 when the engine itself could
// not be created.
#include "spektrafilm/spk_engine.h"

#include "volk.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unistd.h>
#include <vector>

namespace {

const char* yn(bool v) { return v ? "yes" : "no"; }

std::string vk_version(uint32_t v) {
    char buf[32];
    std::snprintf(buf, sizeof buf, "%u.%u.%u", VK_VERSION_MAJOR(v), VK_VERSION_MINOR(v), VK_VERSION_PATCH(v));
    return buf;
}

const char* device_type_name(VkPhysicalDeviceType t) {
    switch (t) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated_gpu";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return "discrete_gpu";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return "virtual_gpu";
        case VK_PHYSICAL_DEVICE_TYPE_CPU: return "cpu";
        default: return "other";
    }
}

uint64_t read_mem_total_bytes() {
    std::FILE* f = std::fopen("/proc/meminfo", "r");
    if (!f) return 0;
    char line[256];
    uint64_t kb = 0;
    while (std::fgets(line, sizeof line, f)) {
        if (std::sscanf(line, "MemTotal: %llu kB", reinterpret_cast<unsigned long long*>(&kb)) == 1) break;
    }
    std::fclose(f);
    return kb * 1024ull;
}

// The D9 formula, as it is meant to be implemented: the engine's own
// `max_pixels` capped by what this process's RAM can hold at 180 B/px.
void print_budget(uint64_t max_pixels, uint64_t ram) {
    const uint64_t floor_budget = 1ull << 30;
    const uint64_t fraction = ram * 35 / 100;
    const uint64_t after_headroom = fraction > 600ull * 1024 * 1024 ? fraction - 600ull * 1024 * 1024 : 0;
    const uint64_t budget = ram ? (after_headroom > floor_budget ? after_headroom : floor_budget) : floor_budget;
    const uint64_t allowed = budget / 180;
    const uint64_t capped = max_pixels ? (allowed < max_pixels ? allowed : max_pixels) : allowed;
    // 3:2 sensor: pixels = w * h with w/h = 1.5 -> long edge = sqrt(1.5 * pixels).
    uint64_t long_edge = 0;
    while ((long_edge + 1) * (long_edge + 1) <= capped * 3 / 2) ++long_edge;
    std::printf("budget.total_ram_bytes=%llu\n", static_cast<unsigned long long>(ram));
    std::printf("budget.native_budget_bytes=%llu\n", static_cast<unsigned long long>(budget));
    std::printf("budget.allowed_pixels_180bpp=%llu\n", static_cast<unsigned long long>(capped));
    std::printf("budget.max_long_edge_180bpp=%llu\n", static_cast<unsigned long long>(long_edge));
}

}  // namespace

int main(int argc, char** argv) {
    const char* resources = argc > 1 ? argv[1] : "resources";

    // --- the probe's own build state -----------------------------------
#ifdef __OPTIMIZE__
    std::printf("probe.optimized=yes\n");
#else
    std::printf("probe.optimized=NO (-O0; performance numbers are void)\n");
#endif
#ifdef NDEBUG
    std::printf("probe.ndebug=yes\n");
#else
    std::printf("probe.ndebug=no\n");
#endif
#if defined(__aarch64__)
    std::printf("probe.abi=arm64-v8a\n");
#elif defined(__x86_64__)
    std::printf("probe.abi=x86_64\n");
#elif defined(__arm__)
    std::printf("probe.abi=armeabi-v7a\n");
#else
    std::printf("probe.abi=unknown\n");
#endif
#ifdef __ANDROID_API__
    std::printf("probe.android_api=%d\n", __ANDROID_API__);
#endif
    std::printf("probe.compiler=%s\n", __VERSION__);
    std::printf("probe.page_size=%ld\n", sysconf(_SC_PAGESIZE));
    std::printf("probe.cxx_std=%ld\n", __cplusplus);
    std::printf("probe.resources=%s\n", resources);

    // --- the engine -----------------------------------------------------
    spk_engine* engine = spk_engine_create(resources, nullptr);
    std::printf("engine.created=%s\n", yn(engine != nullptr));
    if (!engine) {
        std::printf("engine.error=%s\n", spk_last_error(nullptr));
        return 1;
    }
    std::printf("engine.build_info=%s\n", spk_build_info());
    std::printf("capabilities=%s\n", spk_capabilities(engine));
    std::printf("memory_report=%s\n", spk_memory_report(engine));

    // `max_pixels` for the budget line, from the capabilities JSON.
    uint64_t max_pixels = 0;
    {
        const char* caps = spk_capabilities(engine);
        const char* key = std::strstr(caps, "\"max_pixels\"");
        if (key) {
            key = std::strchr(key, ':');
            if (key) max_pixels = std::strtoull(key + 1, nullptr, 10);
        }
    }
    print_budget(max_pixels, read_mem_total_bytes());

    // --- the devices the loader exposes ---------------------------------
    // A second instance, owned by the probe: the engine's own device choice is
    // private, and the four limits have to be readable even when the engine
    // refuses to start on them.
    if (volkInitialize() != VK_SUCCESS) {
        std::printf("vulkan.loader=missing\n");
        spk_engine_destroy(engine);
        return 0;
    }
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "spektralab android probe";
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo create{};
    create.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    const VkResult made = vkCreateInstance(&create, nullptr, &instance);
    if (made != VK_SUCCESS) {
        std::printf("vulkan.instance=no (VkResult %d)\n", int(made));
        spk_engine_destroy(engine);
        return 0;
    }
    volkLoadInstance(instance);

    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(instance, &count, nullptr) != VK_SUCCESS || count == 0) {
        std::printf("vulkan.devices=0\n");
        vkDestroyInstance(instance, nullptr);
        spk_engine_destroy(engine);
        return 0;
    }
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance, &count, devices.data());
    std::printf("vulkan.devices=%u\n", count);

    for (uint32_t i = 0; i < count; ++i) {
        VkPhysicalDeviceProperties p{};
        vkGetPhysicalDeviceProperties(devices[i], &p);
        const VkPhysicalDeviceLimits& l = p.limits;
        std::printf("device.%u.name=%s\n", i, p.deviceName);
        std::printf("device.%u.type=%s\n", i, device_type_name(p.deviceType));
        std::printf("device.%u.vendor_id=0x%04x\n", i, p.vendorID);
        std::printf("device.%u.device_id=0x%04x\n", i, p.deviceID);
        std::printf("device.%u.api_version=%s\n", i, vk_version(p.apiVersion).c_str());
        std::printf("device.%u.driver_version=%u\n", i, p.driverVersion);
        std::printf("device.%u.max_compute_workgroup_invocations=%u\n", i, l.maxComputeWorkGroupInvocations);
        std::printf("device.%u.max_compute_workgroup_size_x=%u\n", i, l.maxComputeWorkGroupSize[0]);
        std::printf("device.%u.max_compute_workgroup_count_x=%u\n", i, l.maxComputeWorkGroupCount[0]);
        std::printf("device.%u.max_per_stage_descriptor_storage_buffers=%u\n", i,
                    l.maxPerStageDescriptorStorageBuffers);
        std::printf("device.%u.max_descriptor_set_storage_buffers=%u\n", i, l.maxDescriptorSetStorageBuffers);
        std::printf("device.%u.max_storage_buffer_range=%llu\n", i,
                    static_cast<unsigned long long>(l.maxStorageBufferRange));
        // 12 B per pixel is one float32 3-channel plane; the size the engine
        // gates a single buffer on.
        std::printf("device.%u.max_storage_buffer_plane_pixels=%llu\n", i,
                    static_cast<unsigned long long>(l.maxStorageBufferRange / 12));
        std::printf("device.%u.max_image_dimension_2d=%u\n", i, l.maxImageDimension2D);

        uint32_t families = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &families, nullptr);
        std::vector<VkQueueFamilyProperties> queues(families);
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &families, queues.data());
        for (uint32_t q = 0; q < families; ++q) {
            std::printf("device.%u.queue.%u.compute=%s\n", i, q, yn((queues[q].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0));
            std::printf("device.%u.queue.%u.count=%u\n", i, q, queues[q].queueCount);
        }

        VkPhysicalDeviceMemoryProperties mem{};
        vkGetPhysicalDeviceMemoryProperties(devices[i], &mem);
        bool uma = false;
        for (uint32_t t = 0; t < mem.memoryTypeCount; ++t) {
            const VkMemoryPropertyFlags f = mem.memoryTypes[t].propertyFlags;
            std::printf("device.%u.memory_type.%u.flags=0x%x heap=%u\n", i, t, unsigned(f), mem.memoryTypes[t].heapIndex);
            if ((f & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) && (f & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) uma = true;
        }
        for (uint32_t h = 0; h < mem.memoryHeapCount; ++h) {
            std::printf("device.%u.heap.%u.bytes=%llu device_local=%s\n", i, h,
                        static_cast<unsigned long long>(mem.memoryHeaps[h].size),
                        yn((mem.memoryHeaps[h].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0));
        }
        // P1-6's judgement call: with one host-visible device-local type, a
        // full-frame plane never needs a staging copy.
        std::printf("device.%u.uma_no_staging_possible=%s\n", i, yn(uma));

        // S1 acceptance item 2's gates, per device.
        const bool gates = l.maxComputeWorkGroupInvocations >= 256 && l.maxComputeWorkGroupSize[0] >= 256 &&
                           l.maxPerStageDescriptorStorageBuffers >= 8 && l.maxDescriptorSetStorageBuffers >= 8;
        std::printf("device.%u.s1_gates=%s\n", i, gates ? "PASS" : "FAIL");
        std::printf("device.%u.supports_12mp_plane=%s\n", i,
                    yn(l.maxStorageBufferRange >= 12ull * 4000 * 3000));
        std::printf("device.%u.supports_25mp_plane=%s\n", i,
                    yn(l.maxStorageBufferRange >= 12ull * 6250 * 4000));
    }

    vkDestroyInstance(instance, nullptr);
    spk_engine_destroy(engine);
    return 0;
}
