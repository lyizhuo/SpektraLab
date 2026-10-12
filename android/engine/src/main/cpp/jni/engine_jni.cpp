// engine_jni.cpp -- the JNI facade over the engine's C ABI (S4).
//
// Three rules from `engine/include/spektrafilm/spk_engine.h` shape everything
// below, and the Kotlin side relies on them:
//
//   1. Nothing throws across the boundary: every entry point returns a JSON
//      reply with `ok`, `status` and `error`, or a handle (0 on failure).
//      `status` is the engine's own `spk_status`.
//   2. Ownership never crosses: `spk_result` is freed here (into a Bitmap, or
//      held as this session's `pending` until the caller has one), and every
//      `char*` the engine handed out is freed with `spk_string_free`.
//   3. One frame at a time. `render`, `setParams`, `open` and `exportImage`
//      are called from the app's single engine worker thread; `cancel` is the
//      exception and is safe from another thread (`spk_cancel` is).
//
// The display path is the host's own: `spkhost::OutputTransform` from
// engine/host/output.cpp -- the same code `spektralab-host` runs for the
// desktop's display and export, driven by `spk_output_transform`'s numbers.
// This file does not implement a second conversion.
#include <jni.h>

#include <android/bitmap.h>
#include <android/log.h>

#include "spektrafilm/spk_engine.h"

#include "decode_bridge.hpp"
#include "image_io.hpp"
#include "json.hpp"
#include "output.hpp"
#include "platform.hpp"
#include "png16.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include <unistd.h>

namespace {

constexpr const char* kTag = "SpektraLabNative";

void log_error(const std::string& message) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
}

std::string from_java(JNIEnv* env, jstring text) {
    if (text == nullptr) return {};
    const char* chars = env->GetStringUTFChars(text, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(text, chars);
    return out;
}

jstring to_java(JNIEnv* env, const std::string& text) { return env->NewStringUTF(text.c_str()); }

long long handle_of(void* pointer) { return static_cast<long long>(reinterpret_cast<intptr_t>(pointer)); }
void* pointer_of(long long handle) { return reinterpret_cast<void*>(static_cast<intptr_t>(handle)); }

spk::Json error_reply(const std::string& message, int status = SPK_ERR_INVALID_ARG) {
    spk::Json reply = spk::Json::object();
    reply.set("ok", spk::Json(false));
    reply.set("status", spk::Json(double(status)));
    reply.set("error", spk::Json(message));
    return reply;
}

std::string reply_string(const spk::Json& reply) { return reply.dump(); }

// The engine's Json has no "string or default" accessor; every default in this
// file is a wire value the caller may legitimately omit.
std::string str_or(const spk::Json& value, const char* fallback) {
    return value.is_string() && !value.as_string().empty() ? value.as_string() : std::string(fallback);
}

spk::Json parse_or_null(const std::string& text) {
    spk::Json value;
    std::string error;
    if (text.empty()) return value;
    if (!spk::Json::parse(text, value, error)) return spk::Json();
    return value;
}

// --- sessions --------------------------------------------------------------

struct DisplayCache {
    bool valid = false;
    std::string workspace;
    spkhost::OutputTransform transform;
};

struct Session {
    spk_engine* engine = nullptr;
    spk_session* session = nullptr;
    std::string workspace = "ProPhoto RGB";
    std::string metadata_json;
    spkhost::Exif exif;
    bool has_exif = false;
    uint32_t source_width = 0;
    uint32_t source_height = 0;
    bool half_size = false;

    DisplayCache display;
    // Scratch buffers, reused across frames: the display copy is the one
    // per-frame allocation that would otherwise be unavoidable (plan D9/S2's
    // "no per-frame allocation" rule).
    std::vector<float> scratch_rgb;
    std::vector<uint8_t> scratch_rgba;

    // A render whose pixels the caller has not taken yet (no Bitmap, or one
    // of the wrong size). Freed by `releasePending`, `copyPendingToBitmap`,
    // the next render, or `close`.
    spk_result pending{};
    bool has_pending = false;

    std::atomic<bool> rendering{false};

    void release_pending() {
        if (has_pending) {
            spk_result_free(&pending);
            pending = spk_result{};
            has_pending = false;
        }
    }
};

std::mutex g_lock;
std::unordered_map<void*, std::string> g_resources;              // engine -> resources dir
std::unordered_map<void*, std::vector<Session*>> g_sessions;     // engine -> live sessions

void register_session(spk_engine* engine, Session* session) {
    std::lock_guard<std::mutex> guard(g_lock);
    g_sessions[engine].push_back(session);
}

void unregister_session(spk_engine* engine, Session* session) {
    std::lock_guard<std::mutex> guard(g_lock);
    auto it = g_sessions.find(engine);
    if (it == g_sessions.end()) return;
    auto& list = it->second;
    list.erase(std::remove(list.begin(), list.end(), session), list.end());
}

std::string resources_of(spk_engine* engine) {
    std::lock_guard<std::mutex> guard(g_lock);
    auto it = g_resources.find(engine);
    return it == g_resources.end() ? std::string() : it->second;
}

Session* session_of(long long handle) { return static_cast<Session*>(pointer_of(handle)); }

// --- params ----------------------------------------------------------------

void apply_workspace(Session* session, const spk::Json& params) {
    const spk::Json& space = params.at("output_color_space");
    if (space.is_string() && !space.as_string().empty()) session->workspace = space.as_string();
}

void apply_metadata(Session* session, const std::string& json) {
    session->metadata_json = json;
    const spk::Json meta = parse_or_null(json);
    if (meta.is_null()) return;
    spkhost::Exif exif;
    exif.make = meta.at("make").as_string();
    exif.model = meta.at("model").as_string();
    exif.lens = meta.at("lens").as_string();
    exif.datetime_original = meta.at("datetime_original").as_string();
    exif.iso = meta.at("iso").as_double();
    exif.shutter_s = meta.at("shutter_s").as_double();
    exif.aperture = meta.at("aperture").as_double();
    exif.focal_mm = meta.at("focal_mm").as_double();
    session->exif = exif;
    session->has_exif = !(exif.make.empty() && exif.model.empty() && exif.datetime_original.empty());
}

// The half-size decode is the only consumer of `halfSize`; a custom decode
// white balance rides in the params delta under this key, because it is a
// property of the decode rather than a wire field (the schema has no
// temperature row): {"decode": {"headroom": true, "white_balance": true,
// "temperature_k": 5200, "tint": 8}}.
constexpr const char* kDecodeKey = "decode";

spk::android::DecodeRequest decode_request_of(const spk::Json& delta) {
    spk::android::DecodeRequest request;
    const spk::Json& decode = delta.at(kDecodeKey);
    if (!decode.is_object()) return request;
    request.half_size = decode.at("half_size").as_bool(false);
    request.headroom = decode.at("headroom").as_bool(false);
    request.white_balance = decode.at("white_balance").as_bool(false);
    request.temperature_k = decode.at("temperature_k").as_double(0);
    request.tint = decode.at("tint").as_double(0);
    return request;
}

// The delta the engine sees: the caller's fields minus `decode`, plus the two
// input fields the bridge guarantees are already true of the pixels it
// decoded (linear ProPhoto, no transfer function). Stating them here rather
// than trusting each call site is what keeps `input_cctf_decoding` from
// silently being wrong for one format.
std::string engine_delta(const spk::Json& delta) {
    spk::Json out = spk::Json::object();
    if (delta.is_object()) {
        for (const auto& kv : delta.fields()) {
            if (kv.first == kDecodeKey) continue;
            out.set(kv.first, kv.second);
        }
    }
    out.set("input_color_space", spk::Json(std::string("ProPhoto RGB")));
    out.set("input_cctf_decoding", spk::Json(false));
    return out.dump();
}

// --- display and export ----------------------------------------------------

const spkhost::OutputTransform* display_transform(Session* session, std::string& error) {
    if (session->display.valid && session->display.workspace == session->workspace)
        return &session->display.transform;
    if (!session->display.transform.fetch(session->engine, session->workspace, "sRGB", error))
        return nullptr;
    session->display.valid = true;
    session->display.workspace = session->workspace;
    return &session->display.transform;
}

bool copy_result_to_bitmap(JNIEnv* env, Session* session, jobject bitmap, const spk_result& result,
                           std::string& error) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        error = "Bitmap info unavailable";
        return false;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        error = "Bitmap must be ARGB_8888 (RGBA_8888)";
        return false;
    }
    if (info.width != result.width || info.height != result.height) {
        error = "Bitmap is " + std::to_string(info.width) + "x" + std::to_string(info.height) +
                ", the render is " + std::to_string(result.width) + "x" + std::to_string(result.height);
        return false;
    }
    const spkhost::OutputTransform* transform = display_transform(session, error);
    if (!transform) return false;
    transform->to_linear(result.rgba16, result.width, result.height,
                         result.row_stride_px ? result.row_stride_px : result.width,
                         session->scratch_rgb);
    transform->encode_rgba8(session->scratch_rgb, session->scratch_rgba);
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        error = "Bitmap lock failed";
        return false;
    }
    const size_t row_bytes = size_t(result.width) * 4;
    uint8_t* destination = static_cast<uint8_t*>(pixels);
    const uint8_t* source = session->scratch_rgba.data();
    if (size_t(info.stride) == row_bytes) {
        std::memcpy(destination, source, row_bytes * result.height);
    } else {
        for (uint32_t y = 0; y < result.height; ++y)
            std::memcpy(destination + size_t(y) * info.stride, source + size_t(y) * row_bytes, row_bytes);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

std::string format_of(const std::string& wire) {
    if (wire == "tiff16" || wire == "tiff8" || wire == "png16" || wire == "png8" || wire == "jpeg8")
        return wire;
    return std::string();
}

}  // namespace

extern "C" {

// --- engine ----------------------------------------------------------------

JNIEXPORT jlong JNICALL Java_com_spektralab_engine_EngineNative_create(JNIEnv* env, jclass, jstring resources) {
    const std::string dir = from_java(env, resources);
    spk_engine* engine = spk_engine_create(dir.c_str(), nullptr);
    if (!engine) {
        log_error("spk_engine_create failed: " + std::string(spk_last_error(nullptr)) + " (resources=" + dir + ")");
        return 0;
    }
    std::lock_guard<std::mutex> guard(g_lock);
    g_resources[engine] = dir;
    return handle_of(engine);
}

JNIEXPORT void JNICALL Java_com_spektralab_engine_EngineNative_destroy(JNIEnv*, jclass, jlong handle) {
    auto* engine = static_cast<spk_engine*>(pointer_of(handle));
    if (!engine) return;
    // Sessions first: `spk_engine_destroy` requires it, and a caller that
    // forgot one is a bug here rather than a crash later.
    std::vector<Session*> orphans;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        auto it = g_sessions.find(engine);
        if (it != g_sessions.end()) {
            orphans = it->second;
            g_sessions.erase(it);
        }
        g_resources.erase(engine);
    }
    for (Session* session : orphans) {
        session->release_pending();
        if (session->session) spk_session_release(session->session);
        delete session;
    }
    spk_engine_destroy(engine);
}

JNIEXPORT jstring JNICALL Java_com_spektralab_engine_EngineNative_buildInfo(JNIEnv* env, jclass) {
    return to_java(env, std::string(spk_build_info()));
}

// What this library was compiled as. The plan's S1 acceptance turns on
// `optimized`: an -O0 engine (AGP's default for a debug variant) makes every
// measurement taken on it meaningless, so the flag is reported, not assumed.
JNIEXPORT jstring JNICALL Java_com_spektralab_engine_EngineNative_nativeBuildInfo(JNIEnv* env, jclass) {
    spk::Json info = spk::Json::object();
    info.set("engine", spk::Json(std::string(spk_build_info())));
#ifndef SPEKTRALAB_ANDROID_CMAKE_BUILD_TYPE
    info.set("cmake_build_type", spk::Json(std::string("unknown")));
#else
    info.set("cmake_build_type", spk::Json(std::string(SPEKTRALAB_ANDROID_CMAKE_BUILD_TYPE)));
#endif
#ifdef __OPTIMIZE__
    info.set("optimized", spk::Json(true));
#else
    info.set("optimized", spk::Json(false));
#endif
#ifdef NDEBUG
    info.set("ndebug", spk::Json(true));
#else
    info.set("ndebug", spk::Json(false));
#endif
#if defined(__aarch64__)
    info.set("abi", spk::Json(std::string("arm64-v8a")));
#elif defined(__x86_64__)
    info.set("abi", spk::Json(std::string("x86_64")));
#else
    info.set("abi", spk::Json(std::string("other")));
#endif
#ifdef __ANDROID_API__
    info.set("android_api", spk::Json(double(__ANDROID_API__)));
#endif
    info.set("cxx_std", spk::Json(double(__cplusplus / 100 % 100)));
    info.set("compiler", spk::Json(std::string(__VERSION__)));
    return to_java(env, reply_string(info));
}

JNIEXPORT jstring JNICALL Java_com_spektralab_engine_EngineNative_capabilities(JNIEnv* env, jclass, jlong handle) {
    auto* engine = static_cast<spk_engine*>(pointer_of(handle));
    if (!engine) return to_java(env, reply_string(error_reply("engine handle is null")));
    return to_java(env, std::string(spk_capabilities(engine)));
}

JNIEXPORT jstring JNICALL Java_com_spektralab_engine_EngineNative_paramsSchema(JNIEnv* env, jclass, jlong handle) {
    auto* engine = static_cast<spk_engine*>(pointer_of(handle));
    if (!engine) return to_java(env, reply_string(error_reply("engine handle is null")));
    return to_java(env, std::string(spk_params_schema(engine)));
}

JNIEXPORT jstring JNICALL Java_com_spektralab_engine_EngineNative_memoryReport(JNIEnv* env, jclass, jlong handle) {
    auto* engine = static_cast<spk_engine*>(pointer_of(handle));
    if (!engine) return to_java(env, reply_string(error_reply("engine handle is null")));
    return to_java(env, std::string(spk_memory_report(engine)));
}

JNIEXPORT jstring JNICALL Java_com_spektralab_engine_EngineNative_lastError(JNIEnv* env, jclass, jlong handle) {
    auto* engine = static_cast<spk_engine*>(pointer_of(handle));
    return to_java(env, std::string(spk_last_error(engine)));
}

JNIEXPORT jstring JNICALL Java_com_spektralab_engine_EngineNative_warmUp(JNIEnv* env, jclass, jlong handle) {
    auto* engine = static_cast<spk_engine*>(pointer_of(handle));
    if (!engine) return to_java(env, reply_string(error_reply("engine handle is null")));
    char* json = nullptr;
    const spk_status status = spk_warm_up(engine, nullptr, nullptr, &json);
    spk::Json reply = status == SPK_OK ? parse_or_null(json ? json : "") : spk::Json();
    if (json) spk_string_free(json);
    if (status != SPK_OK) return to_java(env, reply_string(error_reply(spk_last_error(engine), status)));
    reply.set("ok", spk::Json(true));
    return to_java(env, reply_string(reply));
}

// --- open / probe / thumbnail ---------------------------------------------

JNIEXPORT jlong JNICALL Java_com_spektralab_engine_EngineNative_open(JNIEnv* env, jclass, jlong handle,
                                                                    jint fd, jstring displayName,
                                                                    jboolean halfSize, jstring paramsDelta) {
    auto* engine = static_cast<spk_engine*>(pointer_of(handle));
    if (!engine) {
        ::close(fd);
        return 0;
    }
    std::string path;
    std::string error;
    if (!spk::android::resolve_fd(fd, path, error)) {
        log_error("open: " + error + " (" + from_java(env, displayName) + ")");
        ::close(fd);
        return 0;
    }
    spk::Json delta = parse_or_null(from_java(env, paramsDelta));
    spk::android::DecodeRequest request = decode_request_of(delta);
    request.half_size = request.half_size || halfSize == JNI_TRUE;
    spk::android::DecodedImage image;
    const bool decoded = spk::android::decode_input(path, request, image, error);
    ::close(fd);
    if (!decoded) {
        log_error("open: decode failed: " + error);
        return 0;
    }

    spk_image input{};
    input.data = image.rgb.data();
    input.width = image.width;
    input.height = image.height;
    input.channels = 3;
    char* open_json = nullptr;
    spk_session* raw = spk_open(engine, &input, engine_delta(delta).c_str(), &open_json);
    if (!raw) {
        log_error(std::string("open: spk_open failed: ") + spk_last_error(engine));
        if (open_json) spk_string_free(open_json);
        return 0;
    }
    auto* session = new Session();
    session->engine = engine;
    session->session = raw;
    session->source_width = image.width;
    session->source_height = image.height;
    session->half_size = image.half_size;
    const spk::Json reply = parse_or_null(open_json ? open_json : "");
    if (open_json) spk_string_free(open_json);
    apply_workspace(session, reply.at("params"));
    apply_metadata(session, image.metadata_json);
    register_session(engine, session);
    return handle_of(session);
}

JNIEXPORT jstring JNICALL Java_com_spektralab_engine_EngineNative_probe(JNIEnv* env, jclass, jint fd, jstring displayName) {
    std::string path, error, json;
    if (!spk::android::resolve_fd(fd, path, error)) {
        ::close(fd);
        return to_java(env, reply_string(error_reply(error)));
    }
    const bool ok = spk::android::probe_input(path, json, error);
    ::close(fd);
    if (!ok) return to_java(env, reply_string(error_reply(error, SPK_ERR_IO)));
    spk::Json reply = parse_or_null(json);
    reply.set("ok", spk::Json(true));
    return to_java(env, reply_string(reply));
}

JNIEXPORT jbyteArray JNICALL Java_com_spektralab_engine_EngineNative_thumbnail(JNIEnv* env, jclass, jint fd,
                                                                               jstring displayName, jint longEdge,
                                                                               jintArray outSize) {
    std::string path, error, source;
    if (!spk::android::resolve_fd(fd, path, error)) {
        ::close(fd);
        log_error("thumbnail: " + error);
        return nullptr;
    }
    std::vector<uint8_t> rgba;
    uint32_t width = 0, height = 0;
    const bool ok = spk::android::thumbnail_input(path, uint32_t(longEdge < 16 ? 256 : longEdge), rgba, width,
                                                  height, source, error);
    ::close(fd);
    if (!ok) {
        log_error("thumbnail: " + error);
        return nullptr;
    }
    if (outSize != nullptr) {
        jint dims[2] = {jint(width), jint(height)};
        env->SetIntArrayRegion(outSize, 0, 2, dims);
    }
    jbyteArray bytes = env->NewByteArray(jsize(rgba.size()));
    if (bytes && !rgba.empty()) env->SetByteArrayRegion(bytes, 0, jsize(rgba.size()),
                                                        reinterpret_cast<const jbyte*>(rgba.data()));
    return bytes;
}

// --- session ---------------------------------------------------------------

// What `open` decoded and what the session currently resolves to: the decoded
// frame's size (not the file's, and not a tier's -- both of those move), the
// file's EXIF for the export path, and the flat params.
JNIEXPORT jstring JNICALL Java_com_spektralab_engine_EngineNative_sessionInfo(JNIEnv* env, jclass, jlong handle) {
    Session* session = session_of(handle);
    if (!session) return to_java(env, reply_string(error_reply("session handle is null")));
    char* params_json = nullptr;
    const spk_status status = spk_get_params(session->session, &params_json);
    if (status != SPK_OK) {
        if (params_json) spk_string_free(params_json);
        return to_java(env, reply_string(error_reply(spk_last_error(session->engine), status)));
    }
    spk::Json reply = spk::Json::object();
    reply.set("width", spk::Json(double(session->source_width)));
    reply.set("height", spk::Json(double(session->source_height)));
    reply.set("half_size", spk::Json(session->half_size));
    reply.set("metadata", parse_or_null(session->metadata_json));
    reply.set("params", parse_or_null(params_json ? params_json : ""));
    if (params_json) spk_string_free(params_json);
    reply.set("ok", spk::Json(true));
    return to_java(env, reply_string(reply));
}

JNIEXPORT jstring JNICALL Java_com_spektralab_engine_EngineNative_setParams(JNIEnv* env, jclass, jlong handle,
                                                                            jstring deltaJson) {
    Session* session = session_of(handle);
    if (!session) return to_java(env, reply_string(error_reply("session handle is null")));
    const spk::Json delta = parse_or_null(from_java(env, deltaJson));
    if (delta.at(kDecodeKey).is_object())
        return to_java(env, reply_string(error_reply(
            "decode options (white balance, half size) need a re-open, not setParams", SPK_ERR_USER)));
    char* json = nullptr;
    const spk_status status = spk_set_params(session->session, engine_delta(delta).c_str(), &json);
    spk::Json reply = parse_or_null(json ? json : "");
    if (json) spk_string_free(json);
    if (status != SPK_OK) return to_java(env, reply_string(error_reply(spk_last_error(session->engine), status)));
    apply_workspace(session, reply.at("params"));
    reply.set("ok", spk::Json(true));
    return to_java(env, reply_string(reply));
}

JNIEXPORT jstring JNICALL Java_com_spektralab_engine_EngineNative_getParams(JNIEnv* env, jclass, jlong handle) {
    Session* session = session_of(handle);
    if (!session) return to_java(env, reply_string(error_reply("session handle is null")));
    char* json = nullptr;
    const spk_status status = spk_get_params(session->session, &json);
    spk::Json reply = parse_or_null(json ? json : "");
    if (json) spk_string_free(json);
    if (status != SPK_OK) return to_java(env, reply_string(error_reply(spk_last_error(session->engine), status)));
    reply.set("ok", spk::Json(true));
    return to_java(env, reply_string(reply));
}

JNIEXPORT jstring JNICALL Java_com_spektralab_engine_EngineNative_render(JNIEnv* env, jclass, jlong handle,
                                                                         jstring tier, jboolean reprint,
                                                                         jobject bitmap, jint progressId) {
    Session* session = session_of(handle);
    if (!session) return to_java(env, reply_string(error_reply("session handle is null")));
    const std::string tier_text = from_java(env, tier);

    session->release_pending();
    spk_result result{};
    session->rendering = true;
    spk_status status = SPK_OK;
    bool fell_back = false;
    std::string fallback_error;
    if (reprint == JNI_TRUE) {
        status = spk_reprint(session->session, tier_text.c_str(), &result);
        if (status != SPK_OK) {
            // `spk_reprint` needs the tier's cached negative; a tier the user
            // has not rendered yet has none, and refusing a *reprint* of a
            // frame the user is looking at is not something to surface as a
            // failure. The plan asks for this fallback by name: render it.
            fallback_error = spk_last_error(session->engine);
            fell_back = true;
            status = spk_render(session->session, tier_text.c_str(), &result);
        }
    } else {
        status = spk_render(session->session, tier_text.c_str(), &result);
    }
    session->rendering = false;

    spk::Json reply = spk::Json::object();
    reply.set("tier", spk::Json(tier_text));
    reply.set("progress_id", spk::Json(double(progressId)));
    if (status != SPK_OK) {
        spk::Json error = error_reply(spk_last_error(session->engine), status);
        error.set("tier", spk::Json(tier_text));
        return to_java(env, reply_string(error));
    }
    reply.set("ok", spk::Json(true));
    reply.set("status", spk::Json(double(SPK_OK)));
    reply.set("width", spk::Json(double(result.width)));
    reply.set("height", spk::Json(double(result.height)));
    reply.set("row_stride_px", spk::Json(double(result.row_stride_px)));
    reply.set("elapsed_ms", spk::Json(result.elapsed_ms));
    reply.set("reprint", spk::Json(double(result.reprint)));
    reply.set("negative_was_cached", spk::Json(double(result.negative_was_cached)));
    if (fell_back) {
        reply.set("reprint_fallback", spk::Json(true));
        reply.set("reprint_error", spk::Json(fallback_error));
    }
    reply.set("engine_progress_id", spk::Json(std::string(result.progress_id)));

    if (bitmap != nullptr) {
        std::string error;
        if (copy_result_to_bitmap(env, session, bitmap, result, error)) {
            spk_result_free(&result);
            reply.set("pixels", spk::Json(std::string("bitmap")));
        } else {
            // The caller's Bitmap was the wrong size (or not RGBA_8888): keep
            // the render so the new Bitmap is one call away, not one render.
            session->pending = result;
            session->has_pending = true;
            reply.set("pixels", spk::Json(std::string("pending")));
            reply.set("bitmap_error", spk::Json(error));
        }
    } else {
        session->pending = result;
        session->has_pending = true;
        reply.set("pixels", spk::Json(std::string("pending")));
    }
    reply.set("memory", parse_or_null(spk_memory_report(session->engine)));
    return to_java(env, reply_string(reply));
}

JNIEXPORT jboolean JNICALL Java_com_spektralab_engine_EngineNative_copyPendingToBitmap(JNIEnv* env, jclass,
                                                                                       jlong handle, jobject bitmap) {
    Session* session = session_of(handle);
    if (!session || !session->has_pending || bitmap == nullptr) return JNI_FALSE;
    std::string error;
    if (!copy_result_to_bitmap(env, session, bitmap, session->pending, error)) {
        log_error("copyPendingToBitmap: " + error);
        return JNI_FALSE;
    }
    session->release_pending();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_spektralab_engine_EngineNative_releasePending(JNIEnv*, jclass, jlong handle) {
    if (Session* session = session_of(handle)) session->release_pending();
}

JNIEXPORT jstring JNICALL Java_com_spektralab_engine_EngineNative_exportImage(JNIEnv* env, jclass, jlong handle,
                                                                              jstring tempPath, jstring specJson,
                                                                              jint progressId) {
    Session* session = session_of(handle);
    if (!session) return to_java(env, reply_string(error_reply("session handle is null")));
    const std::string path = from_java(env, tempPath);
    const spk::Json spec = parse_or_null(from_java(env, specJson));
    const std::string format = format_of(str_or(spec.at("format"), "tiff16"));
    if (format.empty())
        return to_java(env, reply_string(error_reply("format must be tiff16, png16, png8 or jpeg8", SPK_ERR_USER)));
    const std::string space = str_or(spec.at("color_space"), "sRGB");
    const int quality = spec.at("jpeg_quality").as_int(92);
    const uint32_t long_edge = uint32_t(std::max(0, spec.at("long_edge").as_int(0)));
    const std::string tier = str_or(spec.at("tier"), "full");

    session->release_pending();
    spk_result result{};
    const spk_status status = spk_render(session->session, tier.c_str(), &result);
    if (status != SPK_OK) {
        const spk::Json error = error_reply(spk_last_error(session->engine), status);
        return to_java(env, reply_string(error));
    }
    std::string error;
    bool ok = false;
    spk::Json reply = spk::Json::object();
    {
        spkhost::OutputTransform transform;
        if (!transform.fetch(session->engine, session->workspace, space, error)) {
            spk_result_free(&result);
            return to_java(env, reply_string(error_reply(error, SPK_ERR_USER)));
        }
        uint32_t width = result.width, height = result.height;
        std::vector<float> linear;
        transform.to_linear(result.rgba16, width, height,
                            result.row_stride_px ? result.row_stride_px : width, linear);
        spk_result_free(&result);
        if (long_edge && std::max(width, height) > long_edge) {
            const double scale = double(long_edge) / double(std::max(width, height));
            const uint32_t dw = std::max(1u, uint32_t(std::lround(width * scale)));
            const uint32_t dh = std::max(1u, uint32_t(std::lround(height * scale)));
            std::vector<float> shrunk;
            spkhost::resize_area(linear.data(), width, height, 3, dw, dh, shrunk);
            linear.swap(shrunk);
            width = dw;
            height = dh;
        }
        std::vector<uint8_t> icc;
        if (!spkhost::icc_for(space, std::filesystem::path(resources_of(session->engine)), icc, error)) {
            return to_java(env, reply_string(error_reply(error, SPK_ERR_IO)));
        }
        const spkhost::Exif* exif = session->has_exif ? &session->exif : nullptr;
        std::vector<uint8_t> bytes;
        const size_t pixels = size_t(width) * height;
        if (format == "tiff16" || format == "png16") {
            std::vector<uint16_t> rgba16;
            transform.encode_rgba16(linear, rgba16);
            std::vector<uint16_t> rgb(pixels * 3);
            for (size_t p = 0; p < pixels; ++p)
                for (int c = 0; c < 3; ++c) rgb[p * 3 + c] = rgba16[p * 4 + c];
            if (format == "tiff16") {
                ok = spkhost::encode_tiff(rgb.data(), width, height, 16, icc, bytes, error, exif);
            } else {
                ok = spk::android::encode_png16(rgb.data(), width, height, icc, bytes, error, exif);
            }
        } else {
            std::vector<uint8_t> rgba8;
            transform.encode_rgba8(linear, rgba8);
            std::vector<uint8_t> rgb(pixels * 3);
            for (size_t p = 0; p < pixels; ++p)
                for (int c = 0; c < 3; ++c) rgb[p * 3 + c] = rgba8[p * 4 + c];
            if (format == "png8") ok = spkhost::encode_png8(rgb.data(), width, height, icc, bytes, error, exif);
            else ok = spkhost::encode_jpeg(rgb.data(), width, height, quality, icc, bytes, error, exif);
        }
        if (!ok) {
            log_error("export: encode failed: " + error);
            return to_java(env, reply_string(error_reply(error, SPK_ERR_IO)));
        }
        // The host's own transactional write: a hidden partial file, then a
        // rename, so a reader never sees a half-written export.
        if (!spkhost::write_file_atomic(std::filesystem::path(path), bytes, false, error)) {
            log_error("export: write failed: " + error);
            return to_java(env, reply_string(error_reply(error, SPK_ERR_IO)));
        }
        reply.set("path", spk::Json(path));
        reply.set("width", spk::Json(double(width)));
        reply.set("height", spk::Json(double(height)));
        reply.set("bytes", spk::Json(double(bytes.size())));
        reply.set("format", spk::Json(format));
        reply.set("color_space", spk::Json(space));
    }
    reply.set("ok", spk::Json(true));
    reply.set("status", spk::Json(double(SPK_OK)));
    reply.set("progress_id", spk::Json(double(progressId)));
    return to_java(env, reply_string(reply));
}

JNIEXPORT jboolean JNICALL Java_com_spektralab_engine_EngineNative_cancel(JNIEnv*, jclass, jlong handle, jint) {
    Session* session = session_of(handle);
    if (!session) return JNI_FALSE;
    // `spk_cancel` is deliberately not under the session lock; a null id
    // cancels whatever render is in flight on this session. The Kotlin side's
    // `progressId` is its own bookkeeping, not the engine's.
    if (!session->rendering.load()) return JNI_FALSE;
    spk_cancel(session->session, nullptr);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL Java_com_spektralab_engine_EngineNative_progress(JNIEnv* env, jclass, jlong handle, jint) {
    Session* session = session_of(handle);
    if (!session) return to_java(env, reply_string(error_reply("session handle is null")));
    char* json = nullptr;
    const spk_status status = spk_progress(session->session, nullptr, &json);
    spk::Json reply = parse_or_null(json ? json : "");
    if (json) spk_string_free(json);
    if (status != SPK_OK) return to_java(env, reply_string(error_reply(spk_last_error(session->engine), status)));
    reply.set("ok", spk::Json(true));
    return to_java(env, reply_string(reply));
}

JNIEXPORT void JNICALL Java_com_spektralab_engine_EngineNative_close(JNIEnv*, jclass, jlong handle) {
    Session* session = session_of(handle);
    if (!session) return;
    unregister_session(session->engine, session);
    session->release_pending();
    if (session->session) spk_session_release(session->session);
    delete session;
}

}  // extern "C"
