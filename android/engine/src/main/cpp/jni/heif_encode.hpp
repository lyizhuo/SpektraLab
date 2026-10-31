// heif_encode.hpp -- the HEIC encode path, callable from both the JNI surface
// and the export worker.
//
// Split out of the `.cpp` because two callers need it and only one of them is
// Kotlin: `EngineNative.exportImage` writes every format it is asked for, and
// HEIC is one more of them rather than a second pipeline. The JNI function
// stays as a thin wrapper so the instrumented tests can drive the encoder
// without a session.
//
// Declared unconditionally and defined twice (real, or a refusing stub) so that
// `engine_jni.cpp` needs no `#if` of its own: a build without the encoder
// answers "this build has no HEIC encoder" through the same channel as every
// other export failure, instead of failing to link.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace spk::android {

/**
 * Encode interleaved 16-bit RGBA to HEIC bytes.
 *
 * @param rgba      `width * height * 4` unsigned 16-bit samples, R,G,B,A.
 * @param bit_depth 8, 10 or 12. The build this repository produces carries a
 *                  10-bit encoder (x265 with `HIGH_BIT_DEPTH=ON`), so anything
 *                  else is refused rather than silently re-quantised.
 * @param icc       the profile to embed. **Required** — a HEIC with no profile
 *                  is read as sRGB by every viewer, which would reinterpret
 *                  this engine's ProPhoto output rather than approximate it.
 * @param out       the file's bytes on success.
 * @param error     why it failed, when it did.
 *
 * @return true on success. Never throws across this boundary.
 */
bool encode_heif(const uint16_t* rgba,
                 int width,
                 int height,
                 int bit_depth,
                 const std::vector<uint8_t>& icc,
                 int quality,
                 std::vector<uint8_t>& out,
                 std::string& error) noexcept;

/** `libheif 1.23.7 (…)`, or an explanation of why this build has none. */
std::string heif_encoder_info() noexcept;

}  // namespace spk::android
