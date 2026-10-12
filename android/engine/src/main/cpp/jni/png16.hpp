// png16.hpp -- a 16-bit PNG writer for exports.
//
// The host's `spkhost::encode_png8` exists; a 16-bit one does not, because the
// bundled stb_image_write only writes 8-bit PNGs (`stb_image_write.h` has no
// bit-depth path at all). The container is small and completely specified, so
// it is written here, in the Android layer, rather than by diverging the
// engine's host code on a third patch branch. Everything it embeds comes from
// the host: the ICC profile from `spkhost::icc_for`, the EXIF block from
// `spkhost::exif_block`, and zlib from stb's own compressor -- the same
// compressor `encode_png8` uses.
//
// `rgb` is interleaved 3-channel 16-bit *host-endian*; the file is
// big-endian, as PNG requires.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "output.hpp"   // spkhost::Exif

namespace spk::android {

bool encode_png16(const uint16_t* rgb, uint32_t width, uint32_t height,
                  const std::vector<uint8_t>& icc, std::vector<uint8_t>& out, std::string& error,
                  const spkhost::Exif* exif = nullptr);

}  // namespace spk::android
