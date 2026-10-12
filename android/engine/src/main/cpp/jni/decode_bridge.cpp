// decode_bridge.cpp -- decoding for the Android caller (S3).
#include "decode_bridge.hpp"

#include "json.hpp"          // the engine's own JSON, via spektralab_core
#include "white_balance.hpp"

#include "image_io.hpp"

#include <libraw/libraw.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <filesystem>
#include <memory>
#include <string>
#include <unistd.h>

namespace spk::android {
namespace {

double now_ms() {
    return std::chrono::duration<double, std::milli>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

std::string json_string(const spk::Json& j) { return j.dump(); }

spk::Json metadata_json(const spkhost::Probe& probe) {
    spk::Json meta = spk::Json::object();
    meta.set("kind", spk::Json(std::string(spkhost::kind_name(probe.kind))));
    meta.set("width", spk::Json(double(probe.width)));
    meta.set("height", spk::Json(double(probe.height)));
    const auto& m = probe.metadata;
    meta.set("make", spk::Json(m.make));
    meta.set("model", spk::Json(m.model));
    meta.set("lens", spk::Json(m.lens));
    meta.set("datetime_original", spk::Json(m.datetime_original));
    meta.set("orientation", spk::Json(double(m.orientation)));
    if (m.iso) meta.set("iso", spk::Json(*m.iso));
    if (m.shutter_s) meta.set("shutter_s", spk::Json(*m.shutter_s));
    if (m.aperture) meta.set("aperture", spk::Json(*m.aperture));
    if (m.focal_mm) meta.set("focal_mm", spk::Json(*m.focal_mm));
    if (m.as_shot_temperature_k) meta.set("as_shot_temperature_k", spk::Json(*m.as_shot_temperature_k));
    if (m.as_shot_tint) meta.set("as_shot_tint", spk::Json(*m.as_shot_tint));
    return meta;
}

// The parameter policy of the engine's own `configure_compatible`
// (engine/src/io/raw_decoder.cpp): AHD, ProPhoto, 16-bit, identity gamma, no
// automatic brightening, camera white balance, camera matrix, no highlight
// recovery. It is duplicated here *only* to turn half_size on, which the io
// layer does not expose; every other field has to match it or the preview and
// the export would differ for a reason nobody could see.
void configure_half_size(libraw_output_params_t& params) {
    params.user_qual = 3;   // AHD
    params.half_size = 1;   // the point of this path
    params.four_color_rgb = 0;
    params.output_color = 4;   // ProPhoto RGB
    params.output_bps = 16;
    params.gamm[0] = params.gamm[1] = 1.0;
    params.no_auto_bright = 1;
    params.no_auto_scale = 0;
    params.adjust_maximum_thr = 0.0f;
    params.bright = 1.0f;
    params.use_camera_wb = 1;
    params.use_auto_wb = 0;
    params.use_camera_matrix = 1;
    std::fill(std::begin(params.user_mul), std::end(params.user_mul), 0.0f);
    params.user_black = -1;
    std::fill(std::begin(params.user_cblack), std::end(params.user_cblack), -1000001);
    params.user_sat = -1;
    params.user_flip = -1;
    params.highlight = 0;
    params.exp_correc = 0;
    params.exp_shift = 1.0f;
    params.exp_preser = 0.0f;
    params.threshold = 0.0f;
    params.med_passes = 0;
    params.fbdd_noiserd = 0;
    params.dcb_iterations = 0;
    params.dcb_enhance_fl = 0;
    params.no_interpolation = 0;
    params.output_profile = nullptr;
    params.camera_profile = nullptr;
}

// RAW, half size, linear ProPhoto float in [0, 1] (the same conversion the io
// layer performs on LibRaw's 16-bit output).
bool decode_raw_half_size(const std::string& path, DecodedImage& out, spkhost::Probe& probe,
                          std::string& error) {
    auto raw = std::make_unique<LibRaw>(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
    configure_half_size(raw->imgdata.params);
    const int open_status = raw->open_file(path.c_str());
    if (open_status != LIBRAW_SUCCESS) {
        error = std::string("RAW open: ") + LibRaw::strerror(open_status);
        return false;
    }
    if (raw->unpack() != LIBRAW_SUCCESS || raw->dcraw_process() != LIBRAW_SUCCESS) {
        error = "RAW half-size decode failed";
        return false;
    }
    int status = LIBRAW_SUCCESS;
    std::unique_ptr<libraw_processed_image_t, decltype(&LibRaw::dcraw_clear_mem)> image(
        raw->dcraw_make_mem_image(&status), &LibRaw::dcraw_clear_mem);
    if (status != LIBRAW_SUCCESS || !image || image->type != LIBRAW_IMAGE_BITMAP || image->bits != 16 ||
        image->colors != 3 || image->width == 0 || image->height == 0) {
        error = "RAW half-size decode: unexpected bitmap";
        return false;
    }
    out.width = image->width;
    out.height = image->height;
    const size_t samples = size_t(out.width) * out.height * 3;
    out.rgb.resize(samples);
    for (size_t i = 0; i < samples; ++i) {
        uint16_t value = 0;
        std::memcpy(&value, image->data + i * sizeof(value), sizeof(value));
        out.rgb[i] = float(double(value) / 65535.0);
    }
    // Metadata comes from the host's probe (EXIF + as-shot WB) rather than
    // from this LibRaw instance: one metadata convention, not two.
    std::string probe_error;
    if (spkhost::probe_file(std::filesystem::path(path), probe, probe_error)) {
        // keep it
    } else {
        probe.kind = spkhost::FileKind::Raw;
        probe.width = out.width;
        probe.height = out.height;
    }
    return true;
}

}  // namespace

bool resolve_fd(int fd, std::string& path, std::string& error) {
    path.clear();
    if (fd < 0) {
        error = "invalid file descriptor";
        return false;
    }
    const std::string link = "/proc/self/fd/" + std::to_string(fd);
    char buffer[4096];
    const ssize_t length = ::readlink(link.c_str(), buffer, sizeof buffer - 1);
    if (length <= 0) {
        error = "the document provider gave no readable path for this file (fd " + std::to_string(fd) + ")";
        return false;
    }
    buffer[length] = '\0';
    path.assign(buffer);
    // A pipe or anon_inode (a provider that streams) is not a file the host
    // layer can reopen; say so instead of decoding nothing.
    std::error_code ec;
    if (!std::filesystem::is_regular_file(std::filesystem::path(path), ec)) {
        error = "this provider streams the file instead of exposing it as a file; copy it first (" +
                path + ")";
        path.clear();
        return false;
    }
    return true;
}

bool decode_input(const std::string& path, const DecodeRequest& request, DecodedImage& out,
                  std::string& error) {
    out = DecodedImage{};
    const double started = now_ms();
    spkhost::Probe probe;
    if (!spkhost::probe_file(std::filesystem::path(path), probe, error)) return false;
    out.kind = spkhost::kind_name(probe.kind);
    if (request.white_balance && probe.kind != spkhost::FileKind::Raw) {
        error = "white balance at decode is a RAW-only option";
        return false;
    }
    if (request.half_size && probe.kind == spkhost::FileKind::Raw) {
        if (request.white_balance) {
            // Half size is the interactive path; a decode-time white balance
            // is the export one. Saying so beats silently ignoring the value.
            error = "white balance at decode is not available together with the half-size preview";
            return false;
        }
        if (!decode_raw_half_size(path, out, probe, error)) return false;
        out.half_size = true;
        out.metadata_json = json_string(metadata_json(probe));
        out.decode_ms = now_ms() - started;
        return true;
    }

    spkhost::DecodeOptions options;
    options.raw_mode = request.headroom ? "headroom" : "compatible16";
    if (request.white_balance) {
        options.temperature_k = request.temperature_k;
        options.tint = request.tint;
    }
    spkhost::FloatImage frame;
    if (!spkhost::decode_linear_prophoto(std::filesystem::path(path), options, frame, probe, error))
        return false;
    out.width = frame.width;
    out.height = frame.height;
    out.rgb = std::move(frame.rgb);
    out.metadata_json = json_string(metadata_json(probe));
    out.decode_ms = now_ms() - started;
    return true;
}

bool probe_input(const std::string& path, std::string& json, std::string& error) {
    spkhost::Probe probe;
    if (!spkhost::probe_file(std::filesystem::path(path), probe, error)) return false;
    json = json_string(metadata_json(probe));
    return true;
}

bool thumbnail_input(const std::string& path, uint32_t long_edge, std::vector<uint8_t>& rgba,
                     uint32_t& width, uint32_t& height, std::string& source, std::string& error) {
    spkhost::Rgba8 out;
    if (!spkhost::thumbnail(std::filesystem::path(path), long_edge, out, source, error)) return false;
    rgba = std::move(out.rgba);
    width = out.width;
    height = out.height;
    return true;
}

}  // namespace spk::android
