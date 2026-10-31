package com.spektralab.engine

/**
 * HEIC encoding, through libheif.
 *
 * Split from [EngineNative] because the two have different failure modes: the
 * engine refusing to start is a broken device, while a missing HEIC encoder is
 * an optional capability. The library is built with `-Pspektralab.heif=true`;
 * without it these methods do not exist and [available] is false, which is what
 * [com.spektralab.app.export.heic.HeicColorPolicy] reads before offering the
 * format at all.
 *
 * ### Why the profile is not optional
 *
 * `encodeRgba16` throws when `icc` is empty rather than writing a file without
 * one. A HEIC file with no colour profile is read as sRGB by every viewer, and
 * this engine's ProPhoto output would be reinterpreted rather than approximated
 * — a wrong photograph that looks like a rendering bug. The refusal is the
 * feature.
 */
object HeifNative {

    /**
     * The same `.so` as [EngineNative] — one library, loaded twice.
     *
     * `System.loadLibrary` is idempotent per class loader, so this is free when
     * the engine got there first, and it is what makes `HeifNative.available`
     * answerable without the engine having been started: the export page asks
     * whether HEIC can be offered before any session exists, and an
     * `UnsatisfiedLinkError` there would be indistinguishable from a device
     * that cannot run the encoder at all.
     */
    init {
        System.loadLibrary("spektralab")
    }

    /**
     * True when `libspektralab.so` was built with the encoder.
     *
     * Probed once by calling `encoderInfo`, which returns a non-empty string
     * when the symbol exists and throws `UnsatisfiedLinkError` when it does not.
     * Caching the answer matters: the app asks this on every export page
     * composition, and a `UnsatisfiedLinkError` per frame is a log storm.
     */
    val available: Boolean by lazy {
        runCatching { encoderInfo().startsWith("libheif") }.getOrDefault(false)
    }

    /**
     * `libheif 1.23.7 (HEVC via kvazaar, kvazaar)` — or throws when the build
     * does not carry the encoder.
     */
    @JvmStatic
    external fun encoderInfo(): String

    /**
     * Encode `width × height` interleaved **RGBA**, unsigned 16-bit, to HEIC.
     *
     * @param bitDepth container depth, 8, 10 or 12.
     * @param icc the profile to embed; must not be empty.
     * @param quality libheif's lossy quality, 0–100.
     * @throws IllegalStateException when libheif or the encoder refuses.
     */
    @JvmStatic
    external fun encodeRgba16(
        rgba: ShortArray,
        width: Int,
        height: Int,
        bitDepth: Int,
        icc: ByteArray,
        quality: Int,
    ): ByteArray
}
