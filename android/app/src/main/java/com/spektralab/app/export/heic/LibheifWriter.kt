package com.spektralab.app.export.heic

import android.graphics.Bitmap
import com.spektralab.engine.HeifNative

/**
 * The HEIC writer this build actually has: libheif with x265 behind it.
 *
 * What it can and cannot promise is decided by the encoder, not by this class:
 *
 * - **10-bit only.** x265 is built with `HIGH_BIT_DEPTH=ON`, so it is a 10-bit
 *   encoder; asking for 8 or 12 raises rather than quietly reinterpreting the
 *   pixels. That is why [maxBitDepth] is 10 and not 12, and why the export page
 *   must not offer "HEIC 8-bit" as though it were a choice.
 * - **ICC profiles are carried.** This is the reason the dependency exists: a
 *   `colr` box holding the profile, not an `nclx` enumeration, which is the
 *   only way to name this engine's ProPhoto output or a measured paper.
 *
 * The encoder is built out of band (`android/scripts/build-heif.ps1`) so the
 * ordinary build never waits for it; when it is absent [HeifNative.available]
 * is false and the policy refuses HEIC with a reason instead of crashing.
 *
 * ### Why the pixels are converted here and not handed over as a Bitmap
 *
 * The engine's output is 16-bit; `Bitmap` at `ARGB_8888` would truncate to 8
 * before the encoder ever saw it, and at `RGBA_F16` it is half-float and
 * re-quantised. The only path that keeps 10 bits is to hand over the 16-bit
 * samples and let [HeifNative] round once, at the very end.
 */
class LibheifWriter : HeicWriter {

    override val supportsIcc: Boolean = true

    override val maxBitDepth: Int = 10

    /**
     * True when a `HeicWriter` is actually present in this build.
     *
     * Checked before the export page offers the format, so a build without the
     * library says "this build has no HEIC encoder" rather than failing at the
     * end of a long render.
     */
    val usable: Boolean get() = HeifNative.available

    override fun encode(
        rgba: ShortArray,
        width: Int,
        height: Int,
        bitDepth: Int,
        iccProfile: ByteArray?,
        quality: Int,
    ): Result {
        if (!HeifNative.available) {
            return Result.Unsupported(
                "这个安装包不带 HEIC 编码器（libheif 未编入）。用 -Pspektralab.heif=true 构建。",
            )
        }
        if (iccProfile == null || iccProfile.isEmpty()) {
            // Refusing is the feature: a HEIC with no profile is read as sRGB
            // by every viewer, so this engine's ProPhoto output would be
            // reinterpreted rather than approximated.
            return Result.Unsupported("HEIC 导出必须带 ICC 色彩配置；这个组合没有。")
        }
        if (bitDepth != maxBitDepth) {
            return Result.Unsupported(
                "这个编码器只有 ${maxBitDepth}-bit 一种；请求的是 $bitDepth-bit。",
            )
        }
        if (rgba.size < width * height * 4) {
            return Result.Failed("像素缓冲太小：需要 ${width * height * 4} 个样本，实际 ${rgba.size}。")
        }

        return try {
            val bytes = HeifNative.encodeRgba16(rgba, width, height, bitDepth, iccProfile, quality)
            if (bytes.isEmpty()) Result.Failed("编码器没有产出字节。") else Result.Encoded(bytes)
        } catch (e: IllegalStateException) {
            Result.Failed(e.message ?: "HEIC 编码失败", e)
        } catch (e: UnsatisfiedLinkError) {
            // A build that was compiled with the flag but shipped a library
            // without it. Reported as unavailable, not as a crash.
            Result.Unsupported("这个安装包的本地库没有 HEIC 编码器：${e.message}")
        }
    }

    /**
     * Convert the engine's result to the interleaved 16-bit RGBA the JNI takes.
     *
     * `Bitmap.getPixels` gives 8 bits per channel, which is not enough for a
     * 10-bit encode — it would quantise before the encoder rounded. This
     * overload exists for callers that already hold 16-bit data (the PNG16 path
     * in `engine_jni.cpp` writes exactly that); a caller holding a `Bitmap` gets
     * [fromBitmap8], which says in its name what it costs.
     */
    fun fromBitmap8(bitmap: Bitmap): ShortArray {
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val out = ShortArray(width * height * 4)
        for (i in argb.indices) {
            val p = argb[i]
            // 8 -> 16 by *bit replication* (v * 257), not a shift: a shift maps
            // 255 to 0xFF00 instead of 0xFFFF and darkens every highlight.
            out[i * 4] = (((p shr 16) and 0xFF) * 257).toShort()
            out[i * 4 + 1] = (((p shr 8) and 0xFF) * 257).toShort()
            out[i * 4 + 2] = ((p and 0xFF) * 257).toShort()
            out[i * 4 + 3] = 0xFFFF.toShort()
        }
        return out
    }
}
