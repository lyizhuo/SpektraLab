// android/native/tests/device_baseline.cpp -- the numbers android/DEVICE-BASELINE.md
// is made of (S1 acceptance items 3 and 6).
//
//   device_baseline <resources_dir> <out.json>
//                   [--sources 12,25]        source frames to synthesise, MP
//                   [--live-edges 1600,2048] live-tier long edges to time
//                   [--reps 5]               hot repeats per measurement
//                   [--levers preview]       tier for the lever comparison ("" skips)
//                   [--skip-full]            skip the export-scale render
//
// Three sessions per source, because a peak is only a peak if the session
// holds what it is measuring and nothing else:
//
//   A  interaction -- cold and hot `live` at each preview_long_edge, then cold
//      and hot `preview`. This is one session because it is one editing
//      session, cached negatives and all. Released afterwards, and the memory
//      report before/after is what closing a session gives back.
//   B  export -- a fresh session rendering `full` (the frame's own
//      resolution), which is how S6 exports: close, reopen, render, release.
//      The peak here is the bytes-per-pixel constant the D9 budget uses.
//   C  levers -- a fresh session at `--levers`' tier comparing, in order:
//      default, `striped=true`+`strip_rows=512`, glare+grain off, both. The
//      rows carry `pool.frame_high_water_bytes` as well as `total_bytes`,
//      because the pool's free bytes are retention, not requirement.
//
// Every phase is printed as `phase=<label> wall_ms=... engine_ms=... peak=...`
// and written to the JSON file, so the document can be built from either.
#include "probe_common.hpp"

#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

namespace {

struct Options {
    std::string resources = "resources";
    std::string out = "device_baseline.json";
    std::vector<double> sources_mp{12.0, 25.0};
    std::vector<int> live_edges{1600, 2048};
    int reps = 5;
    std::string levers_tier = "preview";
    bool skip_full = false;
};

std::vector<double> parse_doubles(const char* text) {
    std::vector<double> out;
    std::string s(text ? text : "");
    size_t start = 0;
    while (start < s.size()) {
        const size_t comma = s.find(',', start);
        const std::string piece = s.substr(start, comma == std::string::npos ? std::string::npos : comma - start);
        if (!piece.empty()) out.push_back(std::strtod(piece.c_str(), nullptr));
        if (comma == std::string::npos) break;
        start = comma + 1;
    }
    return out;
}

spk::Json session_cache_of(spk_engine* engine) {
    const spk::Json caps = probe::parse_json(spk_capabilities(engine));
    return caps.at("backend").at("session_cache");
}

// What the user would hold: 3:2 geometry at the requested megapixels.
void geometry_for(double mp, uint32_t& w, uint32_t& h) {
    h = uint32_t(std::sqrt(mp * 1e6 / 1.5) + 0.5);
    w = uint32_t(double(h) * 1.5 + 0.5);
}

struct Hot {
    std::vector<double> wall;
    std::vector<double> engine;
    double memory_peak = 0;
    double frame_high_water = 0;
    double cached_negative = 0;
    int negative_was_cached = 0;
};

void add_to_hot(Hot& hot, const probe::RenderStats& one) {
    hot.wall.push_back(one.wall_ms);
    hot.engine.push_back(one.engine_ms);
    hot.memory_peak = std::max(hot.memory_peak, one.peak_total_bytes);
    hot.frame_high_water = std::max(hot.frame_high_water, one.with_result.frame_high_water);
    hot.cached_negative = one.after_free.cached_negative_bytes;
    hot.negative_was_cached = one.negative_was_cached;
}

spk::Json hot_json(const Hot& hot) {
    spk::Json j = spk::Json::object();
    j.set("reps", spk::Json(double(hot.wall.size())));
    j.set("wall_ms_p50", spk::Json(probe::median(hot.wall)));
    j.set("engine_ms_p50", spk::Json(probe::median(hot.engine)));
    double lo = 0, hi = 0;
    if (!hot.wall.empty()) {
        lo = *std::min_element(hot.wall.begin(), hot.wall.end());
        hi = *std::max_element(hot.wall.begin(), hot.wall.end());
    }
    j.set("wall_ms_min", spk::Json(lo));
    j.set("wall_ms_max", spk::Json(hi));
    j.set("memory_peak_total_bytes", spk::Json(hot.memory_peak));
    j.set("frame_high_water_bytes", spk::Json(hot.frame_high_water));
    j.set("cached_negative_bytes", spk::Json(hot.cached_negative));
    j.set("negative_was_cached", spk::Json(double(hot.negative_was_cached)));
    return j;
}

std::string edge_delta(int edge) { return std::string("{\"preview_long_edge\":") + std::to_string(edge) + "}"; }

// The release measurement, in one place: what a session holds before
// `spk_session_release` and what the engine holds after it.
spk::Json release_session(spk_engine* engine, spk_session* session) {
    const probe::MemFields before = probe::memory_now(engine);
    spk_session_release(session);
    const probe::MemFields after = probe::memory_now(engine);
    spk::Json release = spk::Json::object();
    release.set("before", probe::mem_json(before));
    release.set("after", probe::mem_json(after));
    release.set("released_total_bytes", spk::Json(before.total_bytes - after.total_bytes));
    release.set("released_cached_negative_bytes",
                spk::Json(before.cached_negative_bytes - after.cached_negative_bytes));
    release.set("released_source_bytes", spk::Json(before.source_bytes - after.source_bytes));
    return release;
}

}  // namespace

int main(int argc, char** argv) {
    Options o;
    int positional = 0;
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        auto next = [&](const char* name) -> const char* {
            if (i + 1 >= argc) { std::fprintf(stderr, "%s needs a value\n", name); std::exit(2); }
            return argv[++i];
        };
        if (arg == "--sources") o.sources_mp = parse_doubles(next("--sources"));
        else if (arg == "--live-edges") {
            o.live_edges.clear();
            for (double d : parse_doubles(next("--live-edges"))) o.live_edges.push_back(int(d));
        } else if (arg == "--reps") o.reps = std::atoi(next("--reps"));
        else if (arg == "--levers") o.levers_tier = next("--levers");
        else if (arg == "--skip-full") o.skip_full = true;
        else if (!arg.empty() && arg[0] == '-') {
            std::fprintf(stderr, "unknown flag %s\n", arg.c_str());
            return 2;
        } else if (positional++ == 0) o.resources = arg;
        else o.out = arg;
    }
    if (o.reps < 2) o.reps = 2;
    if (o.live_edges.empty()) o.live_edges.push_back(1600);

    spk_engine* engine = spk_engine_create(o.resources.c_str(), nullptr);
    if (!engine) {
        std::fprintf(stderr, "engine create failed: %s\n", spk_last_error(nullptr));
        return 1;
    }

    const uint64_t ram = probe::mem_total_bytes();
    const probe::Budget budget = probe::budget_for(ram, 180.0);
    spk::Json root = spk::Json::object();
    root.set("build_info", spk::Json(std::string(spk_build_info())));
    root.set("capabilities", probe::parse_json(spk_capabilities(engine)));
    root.set("mem_total_bytes", spk::Json(double(ram)));
    spk::Json budget_json = spk::Json::object();
    budget_json.set("native_budget_bytes_180bpp", spk::Json(double(budget.bytes)));
    budget_json.set("allowed_pixels_180bpp", spk::Json(double(budget.allowed_pixels)));
    budget_json.set("max_long_edge_180bpp", spk::Json(double(budget.max_long_edge)));
    root.set("budget", budget_json);
    std::printf("budget.mem_total_bytes=%llu\n", static_cast<unsigned long long>(ram));
    std::printf("budget.native_budget_bytes_180bpp=%llu\n", static_cast<unsigned long long>(budget.bytes));
    std::printf("budget.allowed_pixels_180bpp=%llu\n", static_cast<unsigned long long>(budget.allowed_pixels));

    spk::Json sources_json = spk::Json::array();

    for (double mp : o.sources_mp) {
        uint32_t w = 0, h = 0;
        geometry_for(mp, w, h);
        std::printf("source.mp=%.1f width=%u height=%u\n", mp, w, h);

        spk::Json entry = spk::Json::object();
        entry.set("mp", spk::Json(mp));
        entry.set("width", spk::Json(double(w)));
        entry.set("height", spk::Json(double(h)));
        entry.set("pixels", spk::Json(double(w) * double(h)));

        const std::vector<float> scene = probe::make_scene(w, h);
        spk_image image{};
        image.data = scene.data();
        image.width = w;
        image.height = h;
        image.channels = 3;

        auto open = [&](int edge, spk::Json* into) -> spk_session* {
            char* open_json = nullptr;
            spk_session* session = spk_open(engine, &image, edge_delta(edge).c_str(), &open_json);
            if (into) *into = probe::parse_json(open_json);
            spk_string_free(open_json);
            return session;
        };
        auto set_delta = [&](spk_session* session, const char* delta) -> bool {
            char* delta_json = nullptr;
            const spk_status status = spk_set_params(session, delta, &delta_json);
            spk_string_free(delta_json);
            if (status != SPK_OK) std::printf("set_params(%s) failed: %s\n", delta, spk_last_error(engine));
            return status == SPK_OK;
        };

        // --- A: the interaction session ----------------------------------
        spk::Json phases = spk::Json::array();
        spk_session* session = open(o.live_edges.front(), nullptr);
        if (!session) {
            entry.set("error", spk::Json(std::string(spk_last_error(engine))));
            std::printf("source.open=FAILED %s\n", spk_last_error(engine));
            sources_json.push(std::move(entry));
            continue;
        }
        {
            char* params_json = nullptr;
            if (spk_get_params(session, &params_json) == SPK_OK) {
                entry.set("resolved_params", probe::parse_json(params_json));
                spk_string_free(params_json);
            }
        }
        for (size_t e = 0; e < o.live_edges.size(); ++e) {
            const int edge = o.live_edges[e];
            if (e > 0 && !set_delta(session, edge_delta(edge).c_str())) break;
            const probe::RenderStats cold = probe::render_once(engine, session, "live", false);
            spk::Json cold_json = probe::stats_json(cold);
            cold_json.set("label", spk::Json(std::string("live_cold_") + std::to_string(edge)));
            cold_json.set("tier", spk::Json(std::string("live")));
            cold_json.set("preview_long_edge", spk::Json(double(edge)));
            phases.push(cold_json);
            std::printf("phase=live_cold_%d wall_ms=%.1f engine_ms=%.1f peak=%llu fhw=%llu\n", edge, cold.wall_ms,
                        cold.engine_ms, static_cast<unsigned long long>(cold.peak_total_bytes),
                        static_cast<unsigned long long>(cold.with_result.frame_high_water));
            Hot hot;
            for (int r = 1; r < o.reps; ++r) {
                const probe::RenderStats one = probe::render_once(engine, session, "live", false);
                if (one.status != SPK_OK) break;
                add_to_hot(hot, one);
            }
            spk::Json hot_phase = hot_json(hot);
            hot_phase.set("label", spk::Json(std::string("live_hot_") + std::to_string(edge)));
            hot_phase.set("tier", spk::Json(std::string("live")));
            hot_phase.set("preview_long_edge", spk::Json(double(edge)));
            phases.push(hot_phase);
            std::printf("phase=live_hot_%d wall_ms_p50=%.1f engine_ms_p50=%.1f peak=%llu\n", edge,
                        probe::median(hot.wall), probe::median(hot.engine),
                        static_cast<unsigned long long>(hot.memory_peak));
        }
        {
            const probe::RenderStats cold = probe::render_once(engine, session, "preview", false);
            spk::Json cold_json = probe::stats_json(cold);
            cold_json.set("label", spk::Json(std::string("preview_cold")));
            cold_json.set("tier", spk::Json(std::string("preview")));
            phases.push(cold_json);
            std::printf("phase=preview_cold wall_ms=%.1f engine_ms=%.1f peak=%llu\n", cold.wall_ms, cold.engine_ms,
                        static_cast<unsigned long long>(cold.peak_total_bytes));
            Hot hot;
            for (int r = 1; r < o.reps; ++r) {
                const probe::RenderStats one = probe::render_once(engine, session, "preview", false);
                if (one.status != SPK_OK) break;
                add_to_hot(hot, one);
            }
            spk::Json hot_phase = hot_json(hot);
            hot_phase.set("label", spk::Json(std::string("preview_hot")));
            hot_phase.set("tier", spk::Json(std::string("preview")));
            phases.push(hot_phase);
            std::printf("phase=preview_hot wall_ms_p50=%.1f engine_ms_p50=%.1f peak=%llu\n", probe::median(hot.wall),
                        probe::median(hot.engine), static_cast<unsigned long long>(hot.memory_peak));
        }
        entry.set("interaction_phases", std::move(phases));
        entry.set("memory_after_interaction", probe::mem_json(probe::memory_now(engine)));
        entry.set("session_cache_after_interaction", session_cache_of(engine));
        entry.set("release_interaction", release_session(engine, session));
        {
            const spk::Json& rel = entry.at("release_interaction");
            std::printf("session_release.interaction before=%llu after=%llu released_cached_negative=%.0f\n",
                        static_cast<unsigned long long>(rel.at("before").at("total_bytes").as_double()),
                        static_cast<unsigned long long>(rel.at("after").at("total_bytes").as_double()),
                        rel.at("released_cached_negative_bytes").as_double());
        }

        // --- B: the export session ---------------------------------------
        if (!o.skip_full) {
            spk_session* export_session = open(o.live_edges.front(), nullptr);
            if (export_session) {
                const probe::RenderStats full = probe::render_once(engine, export_session, "full", false);
                spk::Json full_json = probe::stats_json(full);
                full_json.set("label", spk::Json(std::string("full_export")));
                full_json.set("tier", spk::Json(std::string("full")));
                entry.set("export_phase", full_json);
                std::printf("phase=full_export wall_ms=%.1f engine_ms=%.1f peak=%llu fhw=%llu\n", full.wall_ms,
                            full.engine_ms, static_cast<unsigned long long>(full.peak_total_bytes),
                            static_cast<unsigned long long>(full.with_result.frame_high_water));
                if (full.status == SPK_OK && full.width && full.height) {
                    const double pixels = double(full.width) * double(full.height);
                    const double per_pixel = full.peak_total_bytes / pixels;
                    entry.set("full_bytes_per_pixel", spk::Json(per_pixel));
                    entry.set("full_frame_high_water_bytes_per_pixel",
                              spk::Json(full.with_result.frame_high_water / pixels));
                    std::printf("source.full_bytes_per_pixel=%.2f\n", per_pixel);
                }
                entry.set("release_export", release_session(engine, export_session));
                const spk::Json& rel = entry.at("release_export");
                std::printf("session_release.export before=%llu after=%llu released_cached_negative=%.0f\n",
                            static_cast<unsigned long long>(rel.at("before").at("total_bytes").as_double()),
                            static_cast<unsigned long long>(rel.at("after").at("total_bytes").as_double()),
                            rel.at("released_cached_negative_bytes").as_double());
            } else {
                std::printf("export session open failed: %s\n", spk_last_error(engine));
            }
        }

        // --- C: the levers, in a session of their own ---------------------
        if (!o.levers_tier.empty()) {
            spk_session* lever_session = open(o.live_edges.front(), nullptr);
            if (lever_session) {
                struct Variant { const char* label; const char* delta; };
                const Variant variants[] = {
                    {"default", "{\"striped\":false,\"glare_active\":true,\"grain_active\":true}"},
                    {"striped512", "{\"striped\":true,\"strip_rows\":512}"},
                    {"no_glare_no_grain", "{\"striped\":false,\"glare_active\":false,\"grain_active\":false}"},
                    {"striped512_no_glare_no_grain",
                     "{\"striped\":true,\"strip_rows\":512,\"glare_active\":false,\"grain_active\":false}"},
                };
                spk::Json levers = spk::Json::array();
                for (const Variant& v : variants) {
                    if (!set_delta(lever_session, v.delta)) continue;
                    const probe::RenderStats one =
                        probe::render_once(engine, lever_session, o.levers_tier.c_str(), false);
                    spk::Json row = probe::stats_json(one);
                    row.set("label", spk::Json(std::string(v.label)));
                    row.set("tier", spk::Json(o.levers_tier));
                    levers.push(row);
                    std::printf("lever=%s tier=%s wall_ms=%.1f engine_ms=%.1f peak=%llu fhw=%llu\n", v.label,
                                o.levers_tier.c_str(), one.wall_ms, one.engine_ms,
                                static_cast<unsigned long long>(one.peak_total_bytes),
                                static_cast<unsigned long long>(one.with_result.frame_high_water));
                }
                entry.set("levers", std::move(levers));
                entry.set("release_levers", release_session(engine, lever_session));
            }
        }

        entry.set("memory_after_source", probe::mem_json(probe::memory_now(engine)));
        sources_json.push(std::move(entry));
    }

    root.set("sources", std::move(sources_json));
    root.set("session_cache_final", session_cache_of(engine));
    root.set("memory_final", probe::mem_json(probe::memory_now(engine)));

    std::ofstream out(o.out);
    if (out) {
        out << root.dump();
        std::printf("wrote %s\n", o.out.c_str());
    } else {
        std::fprintf(stderr, "could not write %s\n", o.out.c_str());
    }

    spk_engine_destroy(engine);
    return 0;
}
