// heif_encode.cpp -- HEIC encoding through libheif, the one place in this
// repository where a colour decision is made by a library rather than by the
// engine.
//
// Why libheif and not the platform. `HeifWriter` (API 28) is 8-bit YUV/RGBA and
// has no colour-profile path at all; `MediaMuxer`'s HEIF output carries colour
// as `nclx`, three enumerations that can name sRGB, Display P3 and BT.2020 and
// nothing else. This engine's working space is linear ProPhoto RGB, and its
// prints are described by measured paper profiles, so a writer limited to
// `nclx` does not "approximate" a ProPhoto export -- it produces a file every
// viewer decodes as sRGB, about 1.5x the light (AGENTS.md trap 27). libheif
// writes an ICC profile into a `colr` box, which is the only mechanism that can
// carry what this product produces.
//
// Why x265 and not kvazaar. Both build and both produce correct 10-bit files,
// but kvazaar's plugin aborts the process on the *second* encode (measured
// 2026-11-01: FORTIFY reported a destroyed mutex being destroyed again, raised
// from this library during encoder teardown; one thread does not avoid it).
// x265 is `HIGH_BIT_DEPTH=ON` and the abort disappears with no change here.
// See android/HEIC-NOTES.md.

#include "heif_encode.hpp"

#if defined(SPEKTRALAB_WITH_HEIF)

#include <jni.h>

#include <cstring>
#include <vector>

#include "libheif/heif.h"

namespace spk::android {
namespace {

const char* error_text(const heif_error& error) {
    return error.message != nullptr ? error.message : "unknown libheif error";
}

/** Collects what `heif_context_write` hands back, instead of writing a file. */
struct BufferSink {
    std::vector<uint8_t> bytes;
};

heif_error buffer_writer(heif_context* /*ctx*/, const void* data, size_t size, void* userdata) {
    auto* sink = static_cast<BufferSink*>(userdata);
    const auto* begin = static_cast<const uint8_t*>(data);
    sink->bytes.insert(sink->bytes.end(), begin, begin + size);
    heif_error ok{};
    ok.code = heif_error_Ok;
    ok.subcode = heif_suberror_Unspecified;
    ok.message = nullptr;
    return ok;
}

}  // namespace

bool encode_heif(const uint16_t* rgba,
                 int width,
                 int height,
                 int bit_depth,
                 const std::vector<uint8_t>& icc,
                 int quality,
                 std::vector<uint8_t>& out,
                 std::string& error) noexcept {
    out.clear();
    if (rgba == nullptr || width <= 0 || height <= 0) {
        error = "heif: empty image";
        return false;
    }
    if (bit_depth != 10) {
        // The encoder this build carries is 10-bit. Anything else must raise:
        // silently re-quantising to 10 would be the "plausible photograph"
        // failure mode AGENTS.md trap 11 is about, and the export page would
        // go on claiming the depth it asked for.
        error = "heif: this build has a 10-bit encoder only (asked for " + std::to_string(bit_depth) + ")";
        return false;
    }
    if (icc.empty()) {
        // Deliberately fatal. Dropping the profile would still produce a
        // viewable file, which is exactly why it must not happen quietly.
        error = "heif: an ICC profile is required";
        return false;
    }

    heif_context* ctx = heif_context_alloc();
    if (ctx == nullptr) {
        error = "heif: context allocation failed";
        return false;
    }

    heif_encoder* encoder = nullptr;
    heif_error err = heif_context_get_encoder_for_format(ctx, heif_compression_HEVC, &encoder);
    if (err.code != heif_error_Ok || encoder == nullptr) {
        error = std::string("heif: no HEVC encoder (") + error_text(err) + ")";
        heif_context_free(ctx);
        return false;
    }
    heif_encoder_set_lossy_quality(encoder, quality > 0 ? quality : 90);
    heif_encoder_set_logging_level(encoder, 0);

    heif_image* image = nullptr;
    err = heif_image_create(width, height, heif_colorspace_RGB,
                            heif_chroma_interleaved_RRGGBB_LE, &image);
    if (err.code != heif_error_Ok) {
        error = std::string("heif: image_create (") + error_text(err) + ")";
        heif_encoder_release(encoder);
        heif_context_free(ctx);
        return false;
    }

    err = heif_image_add_plane(image, heif_channel_interleaved, width, height, bit_depth);
    if (err.code != heif_error_Ok) {
        error = std::string("heif: add_plane (") + error_text(err) + ")";
        heif_image_release(image);
        heif_encoder_release(encoder);
        heif_context_free(ctx);
        return false;
    }

    int stride = 0;
    uint8_t* plane = heif_image_get_plane(image, heif_channel_interleaved, &stride);
    if (plane == nullptr) {
        error = "heif: no plane";
        heif_image_release(image);
        heif_encoder_release(encoder);
        heif_context_free(ctx);
        return false;
    }

    // Quantise once, here: the caller has 16-bit data and the container wants
    // `bit_depth`. Rounding-to-nearest rather than truncating matters --
    // truncation biases every value down by half a step, which is visible as a
    // slight darkening after enough samples.
    const int shift = 16 - bit_depth;
    const uint32_t half = shift > 0 ? (1u << (shift - 1)) : 0u;
    const uint32_t max_value = (1u << bit_depth) - 1u;

    for (int y = 0; y < height; ++y) {
        auto* row = reinterpret_cast<uint16_t*>(plane + static_cast<size_t>(y) * stride);
        const size_t src = static_cast<size_t>(y) * width * 4;
        for (int x = 0; x < width; ++x) {
            for (int c = 0; c < 3; ++c) {
                uint32_t v = rgba[src + static_cast<size_t>(x) * 4 + c];
                if (shift > 0) v = (v + half) >> shift;
                row[static_cast<size_t>(x) * 3 + c] =
                    static_cast<uint16_t>(v > max_value ? max_value : v);
            }
        }
    }

    err = heif_image_set_raw_color_profile(
        image, "prof", reinterpret_cast<const void*>(icc.data()), icc.size());
    if (err.code != heif_error_Ok) {
        error = std::string("heif: colour profile (") + error_text(err) + ")";
        heif_image_release(image);
        heif_encoder_release(encoder);
        heif_context_free(ctx);
        return false;
    }

    heif_image_handle* handle = nullptr;
    err = heif_context_encode_image(ctx, image, encoder, nullptr, &handle);
    if (err.code != heif_error_Ok) {
        error = std::string("heif: encode (") + error_text(err) + ")";
        heif_image_release(image);
        heif_encoder_release(encoder);
        heif_context_free(ctx);
        return false;
    }

    BufferSink sink;
    heif_writer writer{};
    writer.writer_api_version = 1;
    writer.write = buffer_writer;
    err = heif_context_write(ctx, &writer, &sink);

    if (handle != nullptr) heif_image_handle_release(handle);
    heif_image_release(image);
    heif_encoder_release(encoder);
    heif_context_free(ctx);

    if (err.code != heif_error_Ok) {
        error = std::string("heif: write (") + error_text(err) + ")";
        return false;
    }
    if (sink.bytes.empty()) {
        error = "heif: encoder produced no bytes";
        return false;
    }
    out = std::move(sink.bytes);
    return true;
}

std::string heif_encoder_info() noexcept {
    std::string info = "libheif ";
    info += heif_get_version();

    heif_context* ctx = heif_context_alloc();
    if (ctx == nullptr) return info + " (no context)";

    heif_encoder* encoder = nullptr;
    const heif_error err = heif_context_get_encoder_for_format(ctx, heif_compression_HEVC, &encoder);
    if (err.code == heif_error_Ok && encoder != nullptr) {
        info += " (HEVC via ";
        info += heif_encoder_get_name(encoder);
        info += ")";
        heif_encoder_release(encoder);
    } else {
        info += " (NO ENCODER)";
    }
    heif_context_free(ctx);
    return info;
}

}  // namespace spk::android

// --- the JNI surface --------------------------------------------------------
//
// A thin wrapper over `encode_heif`, kept so the instrumented tests can drive
// the encoder with no session and no photograph.

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_com_spektralab_engine_HeifNative_encodeRgba16(
    JNIEnv* env, jclass /*clazz*/,
    jshortArray rgba, jint width, jint height, jint bitDepth,
    jbyteArray icc, jint quality) {
    if (rgba == nullptr) {
        if (jclass cls = env->FindClass("java/lang/IllegalStateException")) {
            env->ThrowNew(cls, "heif: empty image");
        }
        return nullptr;
    }

    const jsize samples = env->GetArrayLength(rgba);
    std::vector<uint16_t> pixels(static_cast<size_t>(samples));
    env->GetShortArrayRegion(rgba, 0, samples, reinterpret_cast<jshort*>(pixels.data()));

    std::vector<uint8_t> profile;
    if (icc != nullptr && env->GetArrayLength(icc) > 0) {
        profile.resize(static_cast<size_t>(env->GetArrayLength(icc)));
        env->GetByteArrayRegion(icc, 0, env->GetArrayLength(icc),
                                reinterpret_cast<jbyte*>(profile.data()));
    }

    std::vector<uint8_t> bytes;
    std::string error;
    if (!spk::android::encode_heif(pixels.data(), width, height, bitDepth, profile, quality, bytes, error)) {
        if (jclass cls = env->FindClass("java/lang/IllegalStateException")) {
            env->ThrowNew(cls, error.c_str());
        }
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (out != nullptr) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(bytes.size()),
                                reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_spektralab_engine_HeifNative_encoderInfo(JNIEnv* env, jclass /*clazz*/) {
    return env->NewStringUTF(spk::android::heif_encoder_info().c_str());
}

}  // extern "C"

#else  // !SPEKTRALAB_WITH_HEIF

namespace spk::android {

bool encode_heif(const uint16_t*, int, int, int, const std::vector<uint8_t>&, int,
                 std::vector<uint8_t>& out, std::string& error) noexcept {
    out.clear();
    error = "this build has no HEIC encoder (libheif was not linked in)";
    return false;
}

std::string heif_encoder_info() noexcept {
    return "no HEIC encoder in this build";
}

}  // namespace spk::android

#endif
