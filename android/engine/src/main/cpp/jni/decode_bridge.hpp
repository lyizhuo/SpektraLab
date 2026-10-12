// decode_bridge.hpp -- the S3 image-I/O bridge (android/native/tests is not
// involved; this is the JNI-side half).
//
// It adds no decoder of its own. RAW and raster files go through the host's
// own `spkhost::decode_linear_prophoto` / `thumbnail` / `probe_file`, which
// are the paths `spektralab-host` and its `host_smoke.py` already cover. The
// one exception is the interactive half-size RAW decode, which the engine's
// io layer does not expose (`configure_compatible` pins `half_size = 0`), so
// it lives here as a preview-only path with the same parameter policy --
// see decode_bridge.cpp for the field-by-field note.
//
// Android hands us a file descriptor, not a path: `ParcelFileDescriptor`'s
// SAF streams have no path the host layer could open. The bridge therefore
// resolves the descriptor through `/proc/self/fd/<n>`, which for a real
// document provider is a FUSE-backed regular file, and refuses with a message
// when it is not (a cloud provider that streams, for instance) so the caller
// can fall back to a cached copy instead of getting a silent wrong decode.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace spk::android {

// The path `/proc/self/fd/<fd>` names, when that is a regular file. `fd` is
// not consumed: the caller keeps owning the descriptor it got from Kotlin.
bool resolve_fd(int fd, std::string& path, std::string& error);

struct DecodeRequest {
    bool half_size = false;      // RAW only: LibRaw half_size, the canvas path
    bool headroom = false;       // RAW only: the opt-in headroom policy
    bool white_balance = false;  // RAW only: use temperature_k/tint
    double temperature_k = 0;
    double tint = 0;
};

struct DecodedImage {
    std::vector<float> rgb;      // interleaved, 3 channels, linear ProPhoto
    uint32_t width = 0;
    uint32_t height = 0;
    std::string kind;            // "raw" | "tiff" | "jpeg" | "png"
    bool half_size = false;
    double decode_ms = 0;
    std::string metadata_json;
};

// Decodes one frame for `spk_open`: linear ProPhoto RGB, orientation applied,
// top row first -- the convention `spk_image` and `input_cctf_decoding=false`
// require, and the one the macOS decoder uses.
bool decode_input(const std::string& path, const DecodeRequest& request, DecodedImage& out,
                  std::string& error);

// EXIF/format/size, as the host's `probe` returns it.
bool probe_input(const std::string& path, std::string& json, std::string& error);

// A display thumbnail, sRGB 8-bit, orientation applied.
bool thumbnail_input(const std::string& path, uint32_t long_edge, std::vector<uint8_t>& rgba,
                     uint32_t& width, uint32_t& height, std::string& source, std::string& error);

}  // namespace spk::android
