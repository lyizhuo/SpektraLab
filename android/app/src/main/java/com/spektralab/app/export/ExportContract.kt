package com.spektralab.app.export

import android.net.Uri
import org.json.JSONObject

/** The four formats the export page offers (plan S5/S6). */
enum class ExportFormat(val wire: String, val mime: String, val extension: String, val bits: Int) {
    Tiff16("tiff16", "image/tiff", "tif", 16),
    Png16("png16", "image/png", "png", 16),
    Png8("png8", "image/png", "png", 8),
    Jpeg8("jpeg8", "image/jpeg", "jpg", 8),
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
