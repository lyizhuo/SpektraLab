// android/native/tests/probe_common.hpp -- shared plumbing of the S1 probes.
//
// Header-only on purpose: both probes are single translation units that link
// the engine, and neither should grow a library of its own.
//
// The JSON here is the engine's own `spk::Json` (engine/src/core/json.hpp),
// reached through `spektralab_core`'s PUBLIC include directory. Parsing the
// reports rather than pattern-matching them keeps the fields named the same
// way the engine names them, so a rename upstream fails loudly at the probe
// instead of quietly at the documentation.
#pragma once

#include "spektrafilm/spk_engine.h"

#include "json.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

namespace probe {

inline double now_ms() {
    return std::chrono::duration<double, std::milli>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

inline uint64_t mem_total_bytes() {
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

// D9's budget: max(1 GB, 0.35 * RAM - 600 MB), then `allowed = budget / 180`.
struct Budget {
    uint64_t ram = 0;
    uint64_t bytes = 0;
    uint64_t allowed_pixels = 0;
    uint64_t max_long_edge = 0;
};

inline Budget budget_for(uint64_t ram, double bytes_per_pixel) {
    Budget b;
    b.ram = ram;
    const uint64_t floor_budget = 1ull << 30;
    const uint64_t fraction = ram > 600ull * 1024 * 1024
                                  ? ram * 35 / 100 - 600ull * 1024 * 1024
                                  : 0;
    b.bytes = ram ? (fraction > floor_budget ? fraction : floor_budget) : floor_budget;
    b.allowed_pixels = uint64_t(double(b.bytes) / bytes_per_pixel);
    // 3:2 sensor: long_edge = sqrt(1.5 * pixels)
    while ((b.max_long_edge + 1) * (b.max_long_edge + 1) <= b.allowed_pixels * 3 / 2) ++b.max_long_edge;
    return b;
}

// --- memory report -----------------------------------------------------

struct MemFields {
    double total_bytes = 0;
    double pool_total = 0;
    double live_bytes = 0;
    double free_bytes = 0;
    double pending_bytes = 0;
    double frame_high_water = 0;
    double persistent = 0;
    double source_bytes = 0;
    double cached_negative_bytes = 0;
    double negatives = 0;
    double file_backed = 0;
    double allocations = 0;
    double reuses = 0;
    double pending_held = 0;
    double over_releases = 0;
    double live_underflows = 0;
    double reuse_bytes_taken = 0;
    std::string raw;
};

inline MemFields parse_memory(const char* json_text) {
    MemFields m;
    if (!json_text) return m;
    m.raw = json_text;
    spk::Json j;
    std::string error;
    if (!spk::Json::parse(m.raw, j, error)) return m;
    m.total_bytes = j.at("total_bytes").as_double();
    const spk::Json& pool = j.at("pool");
    m.pool_total = pool.at("total_bytes").as_double();
    m.live_bytes = pool.at("live_bytes").as_double();
    m.free_bytes = pool.at("free_bytes").as_double();
    m.pending_bytes = pool.at("pending_bytes").as_double();
    m.frame_high_water = pool.at("frame_high_water_bytes").as_double();
    m.allocations = pool.at("audit").at("allocations").as_double();
    m.reuses = pool.at("audit").at("reuses").as_double();
    m.pending_held = pool.at("audit").at("pending_held").as_double();
    m.over_releases = pool.at("audit").at("over_releases").as_double();
    m.live_underflows = pool.at("audit").at("live_underflows").as_double();
    m.reuse_bytes_taken = pool.at("audit").at("reuse_bytes_taken").as_double();
    const spk::Json& persistent = j.at("persistent");
    m.persistent = persistent.at("bytes").as_double();
    m.source_bytes = persistent.at("source_bytes").as_double();
    m.cached_negative_bytes = persistent.at("cached_negative_bytes").as_double();
    m.negatives = persistent.at("negatives").as_double();
    m.file_backed = persistent.at("file_backed_bytes").as_double();
    return m;
}

inline spk::Json mem_json(const MemFields& m) {
    spk::Json j = spk::Json::object();
    j.set("total_bytes", spk::Json(m.total_bytes));
    j.set("pool_total_bytes", spk::Json(m.pool_total));
    j.set("live_bytes", spk::Json(m.live_bytes));
    j.set("free_bytes", spk::Json(m.free_bytes));
    j.set("pending_bytes", spk::Json(m.pending_bytes));
    j.set("frame_high_water_bytes", spk::Json(m.frame_high_water));
    j.set("persistent_bytes", spk::Json(m.persistent));
    j.set("source_bytes", spk::Json(m.source_bytes));
    j.set("cached_negative_bytes", spk::Json(m.cached_negative_bytes));
    j.set("negatives", spk::Json(m.negatives));
    j.set("file_backed_bytes", spk::Json(m.file_backed));
    j.set("audit_allocations", spk::Json(m.allocations));
    j.set("audit_reuses", spk::Json(m.reuses));
    j.set("audit_pending_held", spk::Json(m.pending_held));
    j.set("audit_over_releases", spk::Json(m.over_releases));
    j.set("audit_live_underflows", spk::Json(m.live_underflows));
    j.set("audit_reuse_bytes_taken", spk::Json(m.reuse_bytes_taken));
    return j;
}

inline MemFields memory_now(spk_engine* engine) { return parse_memory(spk_memory_report(engine)); }

inline spk::Json parse_json(const char* text) {
    spk::Json value;
    std::string error;
    if (!text) return value;
    if (!spk::Json::parse(text, value, error)) return spk::Json();
    return value;
}

// --- one render --------------------------------------------------------

struct RenderStats {
    int status = SPK_OK;
    double wall_ms = 0;
    double engine_ms = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t row_stride_px = 0;
    int reprint = 0;
    int negative_was_cached = 0;
    std::string error;
    MemFields before;
    MemFields after_free;
    MemFields with_result;
    double peak_total_bytes = 0;   // max of the two snapshots above
    // `spk_progress`: stage, percentage and, with SPEKTRAFILM_NODE_TIMINGS=1,
    // the per-node milliseconds S1 acceptance item 6 compares against wall time.
    std::string progress_json;
    double node_ms_sum = 0;
};

// The sum of `node_times`, which is what the timing trigger compares against
// the frame's wall time.
inline double node_times_ms(const spk::Json& progress) {
    double total = 0;
    for (const auto& kv : progress.at("node_times").fields()) total += kv.second.as_double();
    return total;
}

inline RenderStats render_once(spk_engine* engine, spk_session* session, const char* tier, bool reprint) {
    RenderStats st;
    st.before = memory_now(engine);
    spk_result result{};
    const double t0 = now_ms();
    st.status = reprint ? spk_reprint(session, tier, &result) : spk_render(session, tier, &result);
    st.wall_ms = now_ms() - t0;
    if (st.status != SPK_OK) {
        st.error = spk_last_error(engine);
        return st;
    }
    st.engine_ms = result.elapsed_ms;
    st.width = result.width;
    st.height = result.height;
    st.row_stride_px = result.row_stride_px;
    st.reprint = result.reprint;
    st.negative_was_cached = result.negative_was_cached;
    {
        char* json = nullptr;
        if (spk_progress(session, nullptr, &json) == SPK_OK && json) {
            st.progress_json = json;
            const spk::Json progress = parse_json(json);
            st.node_ms_sum = node_times_ms(progress);
        }
        if (json) spk_string_free(json);
    }
    st.with_result = memory_now(engine);
    spk_result_free(&result);
    st.after_free = memory_now(engine);
    st.peak_total_bytes = std::max(st.with_result.total_bytes, st.after_free.total_bytes);
    return st;
}

inline spk::Json stats_json(const RenderStats& st) {
    spk::Json j = spk::Json::object();
    j.set("status", spk::Json(double(st.status)));
    j.set("wall_ms", spk::Json(st.wall_ms));
    j.set("engine_ms", spk::Json(st.engine_ms));
    j.set("width", spk::Json(double(st.width)));
    j.set("height", spk::Json(double(st.height)));
    j.set("row_stride_px", spk::Json(double(st.row_stride_px)));
    j.set("pixels", spk::Json(double(st.width) * double(st.height)));
    j.set("reprint", spk::Json(double(st.reprint)));
    j.set("negative_was_cached", spk::Json(double(st.negative_was_cached)));
    if (!st.error.empty()) j.set("error", spk::Json(st.error));
    j.set("node_ms_sum", spk::Json(st.node_ms_sum));
    if (!st.progress_json.empty()) j.set("progress", parse_json(st.progress_json.c_str()));
    j.set("memory_before", mem_json(st.before));
    j.set("memory_with_result", mem_json(st.with_result));
    j.set("memory_after_free", mem_json(st.after_free));
    j.set("peak_total_bytes", spk::Json(st.peak_total_bytes));
    return j;
}

inline double median(std::vector<double> v) {
    if (v.empty()) return 0;
    std::sort(v.begin(), v.end());
    const size_t n = v.size();
    return n % 2 ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
}

// --- the synthetic source frame ---------------------------------------
//
// A deterministic scene in linear light: a sky-to-ground gradient, a
// leaf-like mid-frequency texture, pixel-scale noise and a specular cluster.
// It is chosen so every stage the S1 numbers depend on does real work --
// halation and glare need highlights, grain needs noise, the TIFF path needs
// a wide range -- and so two runs of the probe are two identical frames.
inline std::vector<float> make_scene(uint32_t w, uint32_t h) {
    std::vector<float> px(size_t(w) * h * 3);
    uint32_t seed = 0x12345678u;
    auto rnd = [&seed] {
        seed = seed * 1664525u + 1013904223u;
        return float((seed >> 8) & 0xFFFFu) / 65535.0f;
    };
    const float inv_w = w > 1 ? 1.0f / float(w - 1) : 0.0f;
    const float inv_h = h > 1 ? 1.0f / float(h - 1) : 0.0f;
    for (uint32_t y = 0; y < h; ++y) {
        const float v = float(y) * inv_h;
        for (uint32_t x = 0; x < w; ++x) {
            const float u = float(x) * inv_w;
            // Sky at the top, foliage below.
            const float sky = 0.9f * (1.0f - v) + 0.08f;
            const float leaves = 0.35f + 0.30f * std::sin(41.0f * u) * std::cos(29.0f * v) +
                                 0.15f * std::sin(113.0f * u + 71.0f * v);
            const float base = v < 0.42f ? sky : leaves + 0.10f;
            float value = base * (0.9f + 0.2f * rnd());
            // A small, hot specular cluster: halation and glare read this.
            const float du = u - 0.72f, dv = v - 0.24f;
            value += 26.0f * std::exp(-(du * du + dv * dv) / 4e-5f);
            // A deep shadow patch for the print's own roll-off.
            const float su = u - 0.18f, sv = v - 0.82f;
            value *= 1.0f - 0.92f * std::exp(-(su * su + sv * sv) / 8e-3f);
            const size_t i = (size_t(y) * w + x) * 3;
            px[i + 0] = value * 1.00f;
            px[i + 1] = value * 0.97f;
            px[i + 2] = value * 0.90f;
        }
    }
    return px;
}

}  // namespace probe
