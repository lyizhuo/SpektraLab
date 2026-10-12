// android/native/tests/decode_probe.cpp -- the S3/S7 cross-check tool for the
// Android decode bridge, runnable on Linux.
//
// It compiles the same `decode_bridge.cpp` / `png16.cpp` the JNI library uses
// (no JNI headers involved) and exercises them against files on disk, so the
// decode, the probe, the thumbnail and the 16-bit PNG writer can be compared
// with `spektralab-host`'s answers before either is trusted on a phone.
//
//   decode_probe probe <file>              EXIF/format/size, as sf_probe returns it
//   decode_probe decode <file> [--half]    decoded size, checksum, milliseconds
//   decode_probe thumb <file> <long-edge>  sRGB RGBA8 thumbnail, checksum
//   decode_probe png16 <out.png> <w> <h>   writes a 16-bit PNG, re-reads it and
//                                          reports whether the round trip is exact
//   decode_probe roundtrip <in> <out> <format>
//                                          decode -> encode (host writer) ->
//                                          decode again; max abs difference
//
// The checksum is FNV-1a over the decoded float bit patterns: two decoders
// agree exactly or the numbers say so.
#include "decode_bridge.hpp"
#include "json.hpp"
#include "output.hpp"
#include "platform.hpp"
#include "png16.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <filesystem>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

// engine/host/stb_impl.cpp is the repository's one stb implementation unit
// (it declares both the reader and the writer); this only needs the
// declarations.
#include "stb_image.h"

namespace {

double now_ms() {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

uint64_t checksum(const float* values, size_t count) {
    uint64_t hash = 1469598103934665603ull;
    const auto* bytes = reinterpret_cast<const unsigned char*>(values);
    for (size_t i = 0; i < count * sizeof(float); ++i) {
        hash ^= bytes[i];
        hash *= 1099511628211ull;
    }
    return hash;
}

int usage() {
    std::fprintf(stderr,
                 "usage: decode_probe probe <file>\n"
                 "       decode_probe decode <file> [--half]\n"
                 "       decode_probe thumb <file> <long-edge>\n"
                 "       decode_probe png16 <out.png> <w> <h>\n"
                 "       decode_probe roundtrip <in> <out> <tiff16|png16|png8|jpeg8>\n"
                 "       decode_probe f32 <in> <out.f32> [--half]\n");
    return 2;
}

int probe(const std::string& path) {
    std::string json, error;
    if (!spk::android::probe_input(path, json, error)) {
        std::fprintf(stderr, "probe failed: %s\n", error.c_str());
        return 1;
    }
    std::printf("%s\n", json.c_str());
    return 0;
}

int decode(const std::string& path, bool half) {
    spk::android::DecodeRequest request;
    request.half_size = half;
    spk::android::DecodedImage image;
    std::string error;
    const double started = now_ms();
    if (!spk::android::decode_input(path, request, image, error)) {
        std::fprintf(stderr, "decode failed: %s\n", error.c_str());
        return 1;
    }
    const double total = now_ms() - started;
    std::printf(
        "kind=%s width=%u height=%u half_size=%s checksum=%016llx bytes=%zu decode_ms=%.1f total_ms=%.1f\n",
        image.kind.c_str(), image.width, image.height, image.half_size ? "yes" : "no",
        static_cast<unsigned long long>(checksum(image.rgb.data(), image.rgb.size())),
        image.rgb.size() * sizeof(float), image.decode_ms, total);
    return 0;
}

int thumb(const std::string& path, int long_edge) {
    std::vector<uint8_t> rgba;
    uint32_t width = 0, height = 0;
    std::string source, error;
    if (!spk::android::thumbnail_input(path, uint32_t(long_edge), rgba, width, height, source, error)) {
        std::fprintf(stderr, "thumbnail failed: %s\n", error.c_str());
        return 1;
    }
    uint64_t hash = 1469598103934665603ull;
    for (uint8_t byte : rgba) {
        hash ^= byte;
        hash *= 1099511628211ull;
    }
    std::printf("width=%u height=%u source=%s checksum=%016llx bytes=%zu\n", width, height, source.c_str(),
                static_cast<unsigned long long>(hash), rgba.size());
    return 0;
}

int png16(const std::string& out, uint32_t width, uint32_t height) {
    std::vector<uint16_t> pixels(size_t(width) * height * 3);
    for (uint32_t y = 0; y < height; ++y) {
        for (uint32_t x = 0; x < width; ++x) {
            const size_t i = (size_t(y) * width + x) * 3;
            // Full 16-bit range including the extremes, so a truncation or an
            // 8-bit detour cannot pass by accident.
            pixels[i + 0] = uint16_t((x * 65535u) / (width > 1 ? width - 1 : 1));
            pixels[i + 1] = uint16_t((y * 65535u) / (height > 1 ? height - 1 : 1));
            pixels[i + 2] = uint16_t((x * 7919u + y * 104729u) & 0xFFFFu);
        }
    }
    std::vector<uint8_t> png;
    std::string error;
    if (!spk::android::encode_png16(pixels.data(), width, height, {}, png, error)) {
        std::fprintf(stderr, "png16 failed: %s\n", error.c_str());
        return 1;
    }
    std::FILE* file = std::fopen(out.c_str(), "wb");
    if (!file) {
        std::fprintf(stderr, "png16: cannot write %s\n", out.c_str());
        return 1;
    }
    std::fwrite(png.data(), 1, png.size(), file);
    std::fclose(file);

    int w = 0, h = 0, channels = 0;
    stbi_us* read_back = stbi_load_16(out.c_str(), &w, &h, &channels, 3);
    if (!read_back) {
        std::fprintf(stderr, "png16: stb could not read back %s\n", out.c_str());
        return 1;
    }
    size_t mismatches = 0;
    int max_delta = 0;
    for (size_t i = 0; i < pixels.size(); ++i) {
        const int delta = std::abs(int(pixels[i]) - int(read_back[i]));
        if (delta != 0) ++mismatches;
        if (delta > max_delta) max_delta = delta;
    }
    stbi_image_free(read_back);
    std::printf("png16 bytes=%zu width=%d height=%d channels=%d mismatches=%zu max_delta=%d\n", png.size(), w, h,
                channels, mismatches, max_delta);
    return mismatches == 0 ? 0 : 1;
}

// The colour pipeline's parity harness (plan S7) needs the *same* pixels on
// both machines: this writes the decoded frame as the tightly packed float32
// `spk_render_fixture` reads, so the only thing left that can differ between
// the device and the Linux reference is the render itself.
int dump_f32(const std::string& in, const std::string& out, bool half) {
    spk::android::DecodeRequest request;
    request.half_size = half;
    spk::android::DecodedImage image;
    std::string error;
    if (!spk::android::decode_input(in, request, image, error)) {
        std::fprintf(stderr, "f32: decode failed: %s\n", error.c_str());
        return 1;
    }
    const size_t bytes = image.rgb.size() * sizeof(float);
    if (!spkhost::write_file_atomic(std::filesystem::path(out),
                                    std::vector<uint8_t>(reinterpret_cast<const uint8_t*>(image.rgb.data()),
                                                         reinterpret_cast<const uint8_t*>(image.rgb.data()) + bytes),
                                    true, error)) {
        std::fprintf(stderr, "f32: write failed: %s\n", error.c_str());
        return 1;
    }
    std::printf("width=%u height=%u channels=3 bytes=%zu checksum=%016llx\n", image.width, image.height, bytes,
                static_cast<unsigned long long>(checksum(image.rgb.data(), image.rgb.size())));
    return 0;
}

int roundtrip(const std::string& in, const std::string& out, const std::string& format) {
    spk::android::DecodeRequest request;
    spk::android::DecodedImage source;
    std::string error;
    if (!spk::android::decode_input(in, request, source, error)) {
        std::fprintf(stderr, "roundtrip: decode failed: %s\n", error.c_str());
        return 1;
    }
    // No colour-space change is involved: the point of the round trip is the
    // container and the codec (a 16-bit TIFF, a 16-bit PNG, an 8-bit PNG, a
    // JPEG) and the decoder that reads it back, so the pixels go out as they
    // came in.
    std::vector<float>& linear = source.rgb;
    std::vector<uint8_t> icc, bytes;
    const size_t pixels = size_t(source.width) * source.height;
    bool ok = false;
    const int quality = 95;
    if (format == "tiff16" || format == "png16") {
        std::vector<uint16_t> rgb(pixels * 3);
        for (size_t p = 0; p < pixels; ++p)
            for (int c = 0; c < 3; ++c) {
                const float value = std::clamp(linear[p * 3 + c], 0.0f, 1.0f);
                rgb[p * 3 + c] = uint16_t(std::lround(value * 65535.0f));
            }
        if (format == "tiff16") ok = spkhost::encode_tiff(rgb.data(), source.width, source.height, 16, icc, bytes, error);
        else ok = spk::android::encode_png16(rgb.data(), source.width, source.height, icc, bytes, error);
    } else {
        std::vector<uint8_t> rgb(pixels * 3);
        for (size_t p = 0; p < pixels; ++p)
            for (int c = 0; c < 3; ++c) {
                const float value = std::clamp(linear[p * 3 + c], 0.0f, 1.0f);
                rgb[p * 3 + c] = uint8_t(std::lround(value * 255.0f));
            }
        if (format == "png8") ok = spkhost::encode_png8(rgb.data(), source.width, source.height, icc, bytes, error);
        else ok = spkhost::encode_jpeg(rgb.data(), source.width, source.height, quality, icc, bytes, error);
    }
    if (!ok) {
        std::fprintf(stderr, "roundtrip: encode failed: %s\n", error.c_str());
        return 1;
    }
    if (!spkhost::write_file_atomic(std::filesystem::path(out), bytes, true, error)) {
        std::fprintf(stderr, "roundtrip: write failed: %s\n", error.c_str());
        return 1;
    }
    spk::android::DecodedImage back;
    if (!spk::android::decode_input(out, request, back, error)) {
        std::fprintf(stderr, "roundtrip: re-decode failed: %s\n", error.c_str());
        return 1;
    }
    if (back.width != source.width || back.height != source.height) {
        std::fprintf(stderr, "roundtrip: size changed %ux%u -> %ux%u\n", source.width, source.height, back.width,
                     back.height);
        return 1;
    }
    double max_abs = 0;
    double sum_abs = 0;
    for (size_t i = 0; i < source.rgb.size(); ++i) {
        const double delta = std::fabs(double(source.rgb[i]) - double(back.rgb[i]));
        max_abs = std::fmax(max_abs, delta);
        sum_abs += delta;
    }
    std::printf("roundtrip format=%s width=%u height=%u bytes=%zu max_abs=%.6f mean_abs=%.6f\n", format.c_str(),
                source.width, source.height, bytes.size(), max_abs, sum_abs / double(source.rgb.size()));
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) return usage();
    const std::string command = argv[1];
    if (command == "probe") return probe(argv[2]);
    if (command == "decode") {
        const bool half = argc > 3 && std::strcmp(argv[3], "--half") == 0;
        return decode(argv[2], half);
    }
    if (command == "thumb") return argc > 3 ? thumb(argv[2], std::atoi(argv[3])) : usage();
    if (command == "png16") return argc > 4 ? png16(argv[2], uint32_t(std::atoi(argv[3])), uint32_t(std::atoi(argv[4]))) : usage();
    if (command == "roundtrip") return argc > 4 ? roundtrip(argv[2], argv[3], argv[4]) : usage();
    if (command == "f32") {
        const bool half = argc > 4 && std::strcmp(argv[4], "--half") == 0;
        return argc > 3 ? dump_f32(argv[2], argv[3], half) : usage();
    }
    return usage();
}
