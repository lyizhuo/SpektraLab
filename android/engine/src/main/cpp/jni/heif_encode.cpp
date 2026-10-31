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
// Why kvazaar and not x265. Both are statically linked into one .so here, and
// kvazaar is C with a small CMake surface, which keeps the Android build to one
// toolchain. The encoder is selected by libheif's format id, so swapping it
// later is a CMake change and not a code change.

#include <jni.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "libheif/heif.h"

namespace {

void throw_illegal_state(JNIEnv* env, const std::string& message) {
    if (jclass cls = env->FindClass("java/lang/IllegalStateException")) {
        env->ThrowNew(cls, message.c_str());
    }
}

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

extern "C" {

/**
 * Encode interleaved 16-bit RGBA to HEIC bytes.
 *
 * `rgba` is `width * height * 4` unsigned 16-bit samples in R,G,B,A order, which
 * is what the engine's export path already produces before a writer quantises
 * it. `bitDepth` is the *container* depth: 8 writes `heif_chroma_interleaved_RGB`,
 * 10 or 12 write the little-endian HDR form. `icc` is the profile to embed; it
 * is required, because a HEIC file without one is interpreted as sRGB and this
 * function exists precisely to avoid that.
 *
 * Returns the encoded file, or null with an exception set.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_spektralab_engine_HeifNative_encodeRgba16(
    JNIEnv* env, jclass /*clazz*/,
    jshortArray rgba, jint width, jint height, jint bitDepth,
    jbyteArray icc, jint quality) {
    if (rgba == nullptr || width <= 0 || height <= 0) {
        throw_illegal_state(env, "heif: empty image");
        return nullptr;
    }
    if (bitDepth != 8 && bitDepth != 10 && bitDepth != 12) {
        throw_illegal_state(env, "heif: bit depth must be 8, 10 or 12");
        return nullptr;
    }
    if (icc == nullptr || env->GetArrayLength(icc) == 0) {
        // Deliberately fatal. Dropping the profile would still produce a
        // viewable file, which is exactly why it must not be allowed to happen
        // quietly.
        throw_illegal_state(env, "heif: an ICC profile is required");
        return nullptr;
    }

    const jsize samples = env->GetArrayLength(rgba);
    if (samples < width * height * 4) {
        throw_illegal_state(env, "heif: pixel buffer is too small");
        return nullptr;
    }

    std::vector<uint16_t> pixels(static_cast<size_t>(samples));
    env->GetShortArrayRegion(rgba, 0, samples, reinterpret_cast<jshort*>(pixels.data()));

    std::vector<uint8_t> profile(static_cast<size_t>(env->GetArrayLength(icc)));
    env->GetByteArrayRegion(icc, 0, env->GetArrayLength(icc), reinterpret_cast<jbyte*>(profile.data()));

    heif_context* ctx = heif_context_alloc();
    if (ctx == nullptr) {
        throw_illegal_state(env, "heif: context allocation failed");
        return nullptr;
    }

    heif_encoder* encoder = nullptr;
    heif_error err = heif_context_get_encoder_for_format(ctx, heif_compression_HEVC, &encoder);
    if (err.code != heif_error_Ok || encoder == nullptr) {
        const std::string message = std::string("heif: no HEVC encoder (") + error_text(err) + ")";
        heif_context_free(ctx);
        throw_illegal_state(env, message);
        return nullptr;
    }
    heif_encoder_set_lossy_quality(encoder, quality > 0 ? quality : 90);
    heif_encoder_set_logging_level(encoder, 0);
    // One thread, deliberately.
    //
    // With kvazaar's default thread pool, a second encode in the same process
    // aborts inside libc: "FORTIFY: pthread_mutex_destroy called on a destroyed
    // mutex", raised from this library's teardown. The mutex belongs to the
    // encoder's thread pool, and destroying it twice on the second context free
    // is a real bug in that path — not something this file can wait out. An
    // export is one image, so a single-threaded encode costs a fraction of a
    // second and removes the failure entirely.
    //
    // Ignoring the error is intentional: encoders that do not expose "threads"
    // must not fail because of it, and those encoders do not have this bug.
    (void)heif_encoder_set_parameter_integer(encoder, "threads", 1);

    const heif_chroma chroma = (bitDepth == 8) ? heif_chroma_interleaved_RGB
                                               : heif_chroma_interleaved_RRGGBB_LE;
    heif_image* image = nullptr;
    err = heif_image_create(width, height, heif_colorspace_RGB, chroma, &image);
    if (err.code != heif_error_Ok) {
        const std::string message = std::string("heif: image_create (") + error_text(err) + ")";
        heif_encoder_release(encoder);
        heif_context_free(ctx);
        throw_illegal_state(env, message);
        return nullptr;
    }

    err = heif_image_add_plane(image, heif_channel_interleaved, width, height, bitDepth);
    if (err.code != heif_error_Ok) {
        const std::string message = std::string("heif: add_plane (") + error_text(err) + ")";
        heif_image_release(image);
        heif_encoder_release(encoder);
        heif_context_free(ctx);
        throw_illegal_state(env, message);
        return nullptr;
    }

    int stride = 0;
    uint8_t* plane = heif_image_get_plane(image, heif_channel_interleaved, &stride);
    if (plane == nullptr) {
        heif_image_release(image);
        heif_encoder_release(encoder);
        heif_context_free(ctx);
        throw_illegal_state(env, "heif: no plane");
        return nullptr;
    }

    // Quantise once, here: the engine hands back 16-bit data and the container
    // wants `bitDepth`. Rounding-to-nearest rather than truncating matters --
    // truncation biases every value down by half a step, which is visible as a
    // slight darkening after enough samples.
    const int shift = 16 - bitDepth;
    const uint32_t half = shift > 0 ? (1u << (shift - 1)) : 0u;
    const uint32_t max_value = (1u << bitDepth) - 1u;

    for (int y = 0; y < height; ++y) {
        auto* row = reinterpret_cast<uint16_t*>(plane + static_cast<size_t>(y) * stride);
        const size_t src = static_cast<size_t>(y) * width * 4;
        for (int x = 0; x < width; ++x) {
            for (int c = 0; c < 3; ++c) {
                uint32_t v = pixels[src + static_cast<size_t>(x) * 4 + c];
                if (shift > 0) {
                    v = (v + half) >> shift;
                }
                row[static_cast<size_t>(x) * 3 + c] =
                    static_cast<uint16_t>(v > max_value ? max_value : v);
            }
        }
    }

    err = heif_image_set_raw_color_profile(
        image, "prof",
        reinterpret_cast<const void*>(profile.data()), profile.size());
    if (err.code != heif_error_Ok) {
        const std::string message = std::string("heif: colour profile (") + error_text(err) + ")";
        heif_image_release(image);
        heif_encoder_release(encoder);
        heif_context_free(ctx);
        throw_illegal_state(env, message);
        return nullptr;
    }

    heif_image_handle* handle = nullptr;
    err = heif_context_encode_image(ctx, image, encoder, nullptr, &handle);
    if (err.code != heif_error_Ok) {
        const std::string message = std::string("heif: encode (") + error_text(err) + ")";
        heif_image_release(image);
        heif_encoder_release(encoder);
        heif_context_free(ctx);
        throw_illegal_state(env, message);
        return nullptr;
    }

    BufferSink sink;
    heif_writer writer{};
    writer.writer_api_version = 1;
    writer.write = buffer_writer;
    err = heif_context_write(ctx, &writer, &sink);

    if (handle != nullptr) {
        heif_image_handle_release(handle);
    }
    heif_image_release(image);
    heif_encoder_release(encoder);
    heif_context_free(ctx);

    if (err.code != heif_error_Ok) {
        const std::string message = std::string("heif: write (") + error_text(err) + ")";
        throw_illegal_state(env, message);
        return nullptr;
    }
    if (sink.bytes.empty()) {
        throw_illegal_state(env, "heif: encoder produced no bytes");
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(static_cast<jsize>(sink.bytes.size()));
    if (out != nullptr) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(sink.bytes.size()),
                                reinterpret_cast<const jbyte*>(sink.bytes.data()));
    }
    return out;
}

/** The encoder's own identity, for the About panel and for a support report. */
JNIEXPORT jstring JNICALL
Java_com_spektralab_engine_HeifNative_encoderInfo(JNIEnv* env, jclass /*clazz*/) {
    std::string info = "libheif ";
    info += heif_get_version();
    info += " (HEVC via kvazaar";

    heif_context* ctx = heif_context_alloc();
    if (ctx != nullptr) {
        heif_encoder* encoder = nullptr;
        const heif_error err = heif_context_get_encoder_for_format(ctx, heif_compression_HEVC, &encoder);
        if (err.code == heif_error_Ok && encoder != nullptr) {
            info += ", ";
            info += heif_encoder_get_name(encoder);
            heif_encoder_release(encoder);
        } else {
            info += ", NO ENCODER";
        }
        heif_context_free(ctx);
    }
    info += ")";
    return env->NewStringUTF(info.c_str());
}

}  // extern "C"
