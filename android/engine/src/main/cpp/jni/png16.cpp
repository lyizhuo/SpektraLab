// png16.cpp -- a 16-bit truecolour PNG, with iCCP and eXIf when the caller
// has them. The layout follows the same order spkhost::encode_png8 uses
// (signature + IHDR, then the ancillary chunks, then IDAT), so the two
// writers' files differ only in bit depth.
#include "png16.hpp"

#include <cstdlib>
#include <cstring>

#include "stb_image_write.h"

// The bundled stb_image_write declares this only inside its implementation
// section; engine/host/output.cpp declares it the same way for the 8-bit PNG
// path, and stb_impl.cpp's instantiation is what provides it.
extern "C" unsigned char* stbi_zlib_compress(unsigned char* data, int data_len, int* out_len, int quality);

namespace spk::android {
namespace {

uint32_t crc_table[256];
bool crc_ready = false;

void init_crc() {
    if (crc_ready) return;
    for (uint32_t n = 0; n < 256; ++n) {
        uint32_t c = n;
        for (int k = 0; k < 8; ++k) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
        crc_table[n] = c;
    }
    crc_ready = true;
}

uint32_t crc32_of(const uint8_t* data, size_t n) {
    init_crc();
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < n; ++i) crc = crc_table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    return ~crc;
}

// PNG integers are big-endian.
void put32(std::vector<uint8_t>& out, uint32_t value) {
    out.push_back(uint8_t(value >> 24));
    out.push_back(uint8_t(value >> 16));
    out.push_back(uint8_t(value >> 8));
    out.push_back(uint8_t(value));
}

void chunk(std::vector<uint8_t>& out, const char* type, const std::vector<uint8_t>& body) {
    put32(out, uint32_t(body.size()));
    const size_t start = out.size();
    out.insert(out.end(), type, type + 4);
    out.insert(out.end(), body.begin(), body.end());
    put32(out, crc32_of(out.data() + start, out.size() - start));
}

}  // namespace

bool encode_png16(const uint16_t* rgb, uint32_t width, uint32_t height,
                  const std::vector<uint8_t>& icc, std::vector<uint8_t>& out, std::string& error,
                  const spkhost::Exif* exif) {
    out.clear();
    if (!rgb || width == 0 || height == 0) {
        error = "PNG16 encode: invalid image";
        return false;
    }
    static const uint8_t signature[8] = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    out.insert(out.end(), signature, signature + 8);

    std::vector<uint8_t> ihdr;
    put32(ihdr, width);
    put32(ihdr, height);
    ihdr.push_back(16);   // bit depth
    ihdr.push_back(2);    // colour type: truecolour
    ihdr.push_back(0);    // deflate
    ihdr.push_back(0);    // adaptive filtering
    ihdr.push_back(0);    // no interlace
    chunk(out, "IHDR", ihdr);

    if (!icc.empty()) {
        int compressed_length = 0;
        unsigned char* compressed = stbi_zlib_compress(const_cast<unsigned char*>(icc.data()),
                                                       int(icc.size()), &compressed_length, 8);
        if (!compressed) {
            error = "PNG16 encode: ICC compression failed";
            return false;
        }
        std::vector<uint8_t> body = {'I', 'C', 'C', ' ', 'P', 'r', 'o', 'f', 'i', 'l', 'e', 0, 0};
        body.insert(body.end(), compressed, compressed + compressed_length);
        std::free(compressed);
        chunk(out, "iCCP", body);
    }
    if (exif) {
        const std::vector<uint8_t> block = spkhost::exif_block(*exif, width, height);
        chunk(out, "eXIf", block);
    }

    // Raw scanlines: one filter byte (0 = None) per row, then big-endian
    // samples. zlib on this is the same compressor the 8-bit path uses.
    const size_t row_bytes = size_t(width) * 3 * 2;
    std::vector<uint8_t> raw;
    raw.reserve((row_bytes + 1) * height);
    for (uint32_t y = 0; y < height; ++y) {
        raw.push_back(0);
        const uint16_t* row = rgb + size_t(y) * width * 3;
        for (size_t i = 0; i < size_t(width) * 3; ++i) {
            raw.push_back(uint8_t(row[i] >> 8));
            raw.push_back(uint8_t(row[i] & 0xFF));
        }
    }
    int compressed_length = 0;
    unsigned char* compressed =
        stbi_zlib_compress(raw.data(), int(raw.size()), &compressed_length, 8);
    if (!compressed) {
        error = "PNG16 encode: pixel compression failed";
        return false;
    }
    std::vector<uint8_t> idat(compressed, compressed + compressed_length);
    std::free(compressed);
    chunk(out, "IDAT", idat);
    chunk(out, "IEND", {});
    return true;
}

}  // namespace spk::android
