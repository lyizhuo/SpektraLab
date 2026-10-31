package com.spektralab.app.colour

import kotlin.math.max
import kotlin.math.min

/**
 * The three numbers the export page prints under the size line:
 *
 * ```
 * 6048 × 4024 · Display P3 · 16-bit
 * 超出目标色域 1.8% · 被映射 0.6% · 被夹限 0.1%
 * ```
 *
 * This is the product's differentiation in one row: a film render routinely
 * lands colours the destination cannot hold, and the honest thing is to say how
 * many and how they were treated rather than to hand back a file and let the
 * viewer decide.
 *
 * ### The three categories
 *
 * Given destination-space **linear** values, a pixel is:
 *
 * - **outside** when any channel leaves `[0, 1]` — the destination gamut does
 *   not contain the colour;
 * - **mapped** when the only violations are *negative* channels. A negative
 *   channel means the colour lies outside the destination's triangle but at a
 *   representable luminance; desaturating toward the white point brings it in
 *   without losing the light. This is the recoverable half;
 * - **clipped** when any channel exceeds 1. That is light the destination
 *   cannot carry at all, and it is the one that loses information.
 *
 * `mapped` is a subset of `outside`, and `clipped` is disjoint from `mapped`
 * when a pixel violates both (it is counted as clipped, because clipping is the
 * lossier treatment and dominates the report).
 *
 * ### What this deliberately is not
 *
 * It is not a rendering intent and it does not convert anything. The caller
 * hands in values **already converted** to the destination space, because the
 * colour science lives in the engine (`core/colour.hpp`) and a second copy in
 * Kotlin would be a second answer to the same question — `AGENTS.md` trap 28's
 * shape, where a harness's private copy of a convention goes wrong on its own.
 */
data class GamutReport(
    val totalPixels: Int,
    val outsidePixels: Int,
    val mappedPixels: Int,
    val clippedPixels: Int,
) {
    val outsidePercent: Double get() = percent(outsidePixels)
    val mappedPercent: Double get() = percent(mappedPixels)
    val clippedPercent: Double get() = percent(clippedPixels)

    /** `超出目标色域 1.8% · 被映射 0.6% · 被夹限 0.1%` */
    fun label(): String = buildString {
        append("超出目标色域 ")
        append(fmt(outsidePercent))
        append("% · 被映射 ")
        append(fmt(mappedPercent))
        append("% · 被夹限 ")
        append(fmt(clippedPercent))
        append("%")
    }

    private fun percent(n: Int): Double =
        if (totalPixels <= 0) 0.0 else n.toDouble() * 100.0 / totalPixels

    private fun fmt(v: Double): String =
        if (v == 0.0) "0" else String.format("%.1f", v)

    companion object {
        val EMPTY = GamutReport(0, 0, 0, 0)

        /**
         * Classify [rgb] — tightly packed linear RGB, three floats per pixel.
         *
         * [stride] is float offsets between pixels (3 for packed RGB, 4 for
         * RGBA), so the engine's own buffers can be passed without a copy.
         * A trailing partial pixel is ignored rather than read past the end.
         */
        fun of(rgb: FloatArray, count: Int = -1, stride: Int = 3): GamutReport {
            if (stride < 3) {
                throw IllegalArgumentException("stride must be >= 3, was $stride")
            }
            val pixels = if (count >= 0) min(count, rgb.size / stride) else rgb.size / stride
            if (pixels <= 0) return EMPTY

            var outside = 0
            var mapped = 0
            var clipped = 0
            for (i in 0 until pixels) {
                val base = i * stride
                val r = rgb[base]
                val g = rgb[base + 1]
                val b = rgb[base + 2]
                val lo = min(r, min(g, b))
                val hi = max(r, max(g, b))
                if (lo < 0f || hi > 1f) {
                    outside++
                    if (hi > 1f) clipped++ else mapped++
                }
            }
            return GamutReport(
                totalPixels = pixels,
                outsidePixels = outside,
                mappedPixels = mapped,
                clippedPixels = clipped,
            )
        }
    }
}
