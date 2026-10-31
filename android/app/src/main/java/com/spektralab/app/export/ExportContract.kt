package com.spektralab.app.export

import android.net.Uri
import org.json.JSONObject

/**
 * The formats the export page offers (plan S5/S6, plus HEIC).
 *
 * `Heic10` is the one that is not a raster: a 10-bit HEVC image inside a HEIF
 * container, with the colour profile written as an ICC `colr` box rather than
 * an `nclx` enum. That distinction is the whole point for this product — `nclx`
 * can name sRGB, Display P3 and BT.2020 and nothing else, so a ProPhoto export
 * or a profile measured from the paper cannot be described by it, and the file
 * would be interpreted as sRGB by every viewer.
 *
 * `bits` is the *container* depth on the wire, not the source: the engine
 * always hands back float or 16-bit data and the writer quantises.
 */
enum class ExportFormat(val wire: String, val mime: String, val extension: String, val bits: Int) {
    Tiff16("tiff16", "image/tiff", "tif", 16),
    Png16("png16", "image/png", "png", 16),
    Png8("png8", "image/png", "png", 8),
    Jpeg8("jpeg8", "image/jpeg", "jpg", 8),
    Heic10("heic10", "image/heic", "heic", 10),
    Heic8("heic8", "image/heic", "heic", 8),
    ;

    /** True when this format must carry an ICC profile of its own. */
    val needsIccProfile: Boolean get() = this == Heic10 || this == Heic8
}

/**
 * Which formats the export pipeline can actually produce.
 *
 * The distinction this exists to make is between *the enum has a value* and
 * *the pipeline can write it*. `ExportRunner` asks the engine to render into a
 * file, and the engine writes TIFF, PNG and JPEG; it has no HEIF writer, by
 * design (`ARCHITECTURE.md` §8.8 — the engine returns pixels, the host writes
 * files). HEIC is written by [com.spektralab.app.export.heic.LibheifWriter]
 * instead, which means it needs the render's 16-bit pixels handed over rather
 * than a path handed to the engine, and **that bridge does not exist yet**.
 *
 * So until it does, HEIC must not appear in the picker: an offered format that
 * fails at the end of a long render is worse than an absent one, and a chip
 * that sends `heic10` to an engine that has never heard of it is a guaranteed
 * failure dressed as a choice.
 *
 * [heicPending] is what the picker shows instead — greyed, with this as the
 * reason — because the same rule applies here as on the parameter panel: a
 * control that is not available has to be able to say why.
 */
object ExportFormats {

    /** Formats the engine's own writer can produce. */
    val engineWritten: List<ExportFormat> = listOf(
        ExportFormat.Tiff16,
        ExportFormat.Png16,
        ExportFormat.Png8,
        ExportFormat.Jpeg8,
    )

    /**
     * Formats this build can write, given whether a HEIF writer is linked.
     *
     * When the render→encoder bridge lands, this is the one line that changes:
     * `if (hasHeifWriter) engineWritten + ExportFormat.Heic10`.
     */
    fun offered(hasHeifWriter: Boolean): List<ExportFormat> {
        // TODO(heic): return engineWritten + Heic10 when the bridge exists.
        // The flag is deliberately unused rather than removed: the signature is
        // what the caller already threads through, and the day it becomes true
        // this function must change, which is the point of keeping it.
        check(hasHeifWriter || true)
        return engineWritten
    }

    /** The reason to show beside a format this build cannot produce. */
    fun pendingReason(format: ExportFormat): String? = when (format) {
        ExportFormat.Heic10 ->
            "编码器已可用，但渲染结果还没有接到它上面：导出通路仍由引擎直接写 TIFF/PNG/JPEG。"
        ExportFormat.Heic8 ->
            "HEIC 编码器是 10-bit 的，8-bit 版本它产不出来。"
        else -> null
    }
}

/** The colour spaces the host's writers have profiles for. */
enum class ExportColorSpace(val wire: String) {
    Srgb("sRGB"),
    DisplayP3("Display P3"),
    ProPhoto("ProPhoto RGB"),
}

/** Where the published file goes. */
enum class ExportTarget { Album, Directory }

/**
 * One export request. `longEdge` is the requested size; 0 means the frame's
 * own resolution, and anything above what [com.spektralab.engine.MemoryBudget]
 * allows is refused before a render starts.
 */
data class ExportSpec(
    val format: ExportFormat = ExportFormat.Tiff16,
    val colorSpace: ExportColorSpace = ExportColorSpace.Srgb,
    val jpegQuality: Int = 92,
    val longEdge: Int = 0,
) {
    fun toWireJson(): JSONObject = JSONObject().apply {
        put("format", format.wire)
        put("color_space", colorSpace.wire)
        put("jpeg_quality", jpegQuality.coerceIn(1, 100))
        put("long_edge", maxOf(0, longEdge))
    }
}

sealed interface ExportOutcome {
    /** Published. `uri` is where it landed; `bytes` is what was written. */
    data class Success(
        val uri: String,
        val width: Int,
        val height: Int,
        val bytes: Long,
        val format: ExportFormat,
        val colorSpace: ExportColorSpace,
    ) : ExportOutcome

    /**
     * Refused before rendering: the frame does not fit this device's budget.
     * `maxLongEdge` is the largest long edge that does (plan D9 item 3).
     */
    data class Refused(val reason: String, val maxLongEdge: Int) : ExportOutcome

    data class Failed(val message: String) : ExportOutcome

    data object Cancelled : ExportOutcome
}

/** Why an export is unavailable, for the export page's disabled state. */
data class ExportGate(val allowed: Boolean, val reason: String?, val maxLongEdge: Int)
