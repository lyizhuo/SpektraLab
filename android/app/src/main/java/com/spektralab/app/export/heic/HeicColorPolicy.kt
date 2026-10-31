package com.spektralab.app.export.heic

import com.spektralab.app.export.ExportColorSpace
import com.spektralab.app.export.ExportFormat

/**
 * Which HEIC exports this app may offer, and why the others are refused.
 *
 * "Support HEIC" is not one feature. A HEIF file can name its colour in two
 * ways:
 *
 * - **`nclx`** — three enumerations (primaries, transfer, matrix) drawn from a
 *   fixed list. It can say sRGB, Display P3 and BT.2020 and nothing else.
 * - **an ICC profile** in a `colr` box — arbitrary. It is the only way to
 *   describe ProPhoto RGB or a profile measured from a paper.
 *
 * This product is a colour product: the render's own working space is ProPhoto
 * RGB and the paper profiles are measured, so an HEIC writer that can only emit
 * `nclx` would silently reinterpret every export as sRGB — the exact failure
 * `AGENTS.md` trap 27 describes, where a literal that was correct in one space
 * became 1.478× the light in another.
 *
 * Therefore the rule is **a format is offered only when its writer can carry
 * the requested space**, and the refusal is typed so the export page can print
 * the reason. The encoder behind [HeicWriter] decides that; this file is the
 * policy both sides agree on, and the only part of it that is testable without
 * a device.
 */
object HeicColorPolicy {

    /**
     * The colour spaces expressible as `nclx` without an ICC profile.
     *
     * ProPhoto RGB is deliberately absent: ROMM has no `nclx` code point, and
     * the two spaces that share its primaries are not it.
     */
    val nclxExpressible: Set<ExportColorSpace> = setOf(
        ExportColorSpace.Srgb,
        ExportColorSpace.DisplayP3,
    )

    /** True when this space needs an ICC profile carried inside the file. */
    fun requiresIcc(space: ExportColorSpace): Boolean = space !in nclxExpressible

    /**
     * May [format] be offered for [space] by a writer with [iccSupport]?
     *
     * Non-HEIC formats are always allowed: their writers already carry ICC
     * (`ExportRunner` writes the profile through the host's own writers), so
     * this policy only constrains HEIF.
     */
    fun allows(format: ExportFormat, space: ExportColorSpace, iccSupport: Boolean): Decision {
        if (format != ExportFormat.Heic8 && format != ExportFormat.Heic10) {
            return Decision.Allowed
        }
        if (!requiresIcc(space)) return Decision.Allowed
        if (iccSupport) return Decision.Allowed
        return Decision.Refused(
            "这个组合需要把 ICC 色彩配置写进 HEIC 容器；当前编码器只能写 nclx，"
                + "会把 ${space.wire} 解释成 sRGB。",
        )
    }

    sealed interface Decision {
        data object Allowed : Decision
        data class Refused(val reason: String) : Decision
    }
}

/**
 * The HEIC writer the export path talks to.
 *
 * An interface rather than a direct call because the two implementations are
 * very different in what they can promise:
 *
 * - [com.spektralab.app.export.heic.PlatformHeifWriter] uses the platform's
 *   `MediaMuxer` HEIF output. It is free to ship (no native dependency), it
 *   works on devices whose OEM implemented it, and it **cannot write an ICC
 *   profile** — so [HeicColorPolicy] refuses ProPhoto through it.
 * - A libheif-backed writer (not yet wired) can write 10-bit and an ICC
 *   `colr` box, and would make every cell of the policy allowed.
 *
 * Keeping the interface means the export page does not change when the second
 * one lands, and the first one's honest limits are already expressed as data.
 */
interface HeicWriter {

    /** True when this writer can embed an ICC profile of its own. */
    val supportsIcc: Boolean

    /** Highest bit depth this writer produces. */
    val maxBitDepth: Int

    /**
     * Encode [rgba] (16-bit per channel, row-major, 4 channels) to HEIC.
     *
     * [iccProfile] is required when [supportsIcc] and non-null otherwise; a
     * writer that cannot carry it must fail rather than drop it, because a
     * file that lost its profile does not look broken — it looks like a
     * different photograph.
     */
    fun encode(
        rgba: ShortArray,
        width: Int,
        height: Int,
        bitDepth: Int,
        iccProfile: ByteArray?,
        quality: Int,
    ): Result
}

sealed interface Result {
    data class Encoded(val bytes: ByteArray) : Result

    /** The device or this build cannot do it. Rendered as a refusal, not a crash. */
    data class Unsupported(val reason: String) : Result

    data class Failed(val message: String, val cause: Throwable? = null) : Result
}
