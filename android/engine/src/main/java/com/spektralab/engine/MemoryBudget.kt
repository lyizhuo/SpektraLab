package com.spektralab.engine

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * D9's memory policy: what this device may open and export.
 *
 * The engine renders whole planes and has no CPU fallback, no disk-backed
 * planes on Vulkan and no memory-pressure callback, so the decision has to be
 * made *before* a frame is handed to `spk_open`, and an over-budget request
 * must be refused with a reason rather than silently downscaled.
 *
 * The budget is `max(1 GB, 0.35 × RAM − 600 MB)`: a fraction of total memory
 * because the engine's planes are undistinguishable from any other anonymous
 * memory to the kernel, plus a fixed reserve so the process, the UI and the
 * system are not competing for the last gigabyte.
 *
 * [bytesPerPixel] is measured, not guessed (android/DEVICE-BASELINE.md):
 * a fresh session rendering `full` at 12 MP peaked at 114 B/px on the
 * reference device; an editing session that has already rendered `live` and
 * `preview` peaked at 191 B/px, which is why the export path is specified as
 * close-reopen-render. The default keeps the plan's conservative 180 with the
 * *editing* session's peak in mind, so the interaction path is inside the
 * budget too.
 */
class MemoryBudget(
    val totalRamBytes: Long,
    val bytesPerPixel: Double = DEFAULT_BYTES_PER_PIXEL,
) {
    /** What the engine may hold in this process. */
    val nativeBudgetBytes: Long =
        if (totalRamBytes <= 0) FLOOR_BYTES
        else max(FLOOR_BYTES, (totalRamBytes * 35 / 100) - HEADROOM_BYTES)

    /** The pixel count the budget affords at [bytesPerPixel]. */
    val allowedPixels: Long = floor(nativeBudgetBytes / bytesPerPixel).toLong()

    /**
     * The longest edge of a 3:2 frame that fits the budget — the "largest
     * long edge this device can export" the export page shows.
     */
    val maxLongEdge: Int = floor(sqrt(allowedPixels.toDouble() * 3.0 / 2.0)).toInt()

    /**
     * The longest edge that fits the budget for a frame with this aspect
     * ratio, which is the number that belongs on the export page for a given
     * photograph.
     */
    fun maxLongEdgeFor(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return maxLongEdge
        val aspect = max(width, height).toDouble() / min(width, height).toDouble()
        // pixels = long * (long / aspect)
        return floor(sqrt(allowedPixels.toDouble() * aspect)).toInt()
    }

    /** What may be opened/rendered at this exact size. */
    fun verdict(
        width: Int,
        height: Int,
        maxPixels: Long,
        maxTextureDimension2d: Int,
    ): Verdict {
        if (width <= 0 || height <= 0) return Verdict.Refused("the frame is empty", maxLongEdge, allowedPixels)
        val pixels = width.toLong() * height.toLong()
        if (maxPixels > 0 && pixels > maxPixels) {
            return Verdict.Refused(
                "frame is too large: $pixels px ($width x $height), above this build's limit of " +
                    "$maxPixels px — the largest frame it renders is a Phase One IQ4 150 (14204 x 10652)",
                maxLongEdge,
                allowedPixels,
            )
        }
        val longEdge = max(width, height)
        if (maxTextureDimension2d > 0 && longEdge > maxTextureDimension2d) {
            return Verdict.Refused(
                "frame is too large for this GPU: its $longEdge px long edge is above the device's " +
                    "$maxTextureDimension2d px texture limit",
                maxLongEdge,
                allowedPixels,
            )
        }
        if (pixels > allowedPixels) {
            val affordable = maxLongEdgeFor(width, height)
            return Verdict.Refused(
                "this device's memory budget allows about ${allowedPixels / 1_000_000} MP " +
                    "(a ${affordable} px long edge at this frame's aspect); " +
                    "$pixels px would need ${estimateBytes(pixels) / (1 shl 20)} MB",
                affordable,
                allowedPixels,
            )
        }
        return Verdict.Allowed(pixels, nativeBudgetBytes - estimateBytes(pixels))
    }

    fun estimateBytes(pixels: Long): Long = (pixels * bytesPerPixel).toLong()

    sealed interface Verdict {
        data class Allowed(val pixels: Long, val headroomBytes: Long) : Verdict
        data class Refused(val reason: String, val maxLongEdge: Int, val allowedPixels: Long) : Verdict
    }

    companion object {
        const val DEFAULT_BYTES_PER_PIXEL = 180.0
        private const val FLOOR_BYTES = 1L shl 30
        private const val HEADROOM_BYTES = 600L shl 20
    }
}
