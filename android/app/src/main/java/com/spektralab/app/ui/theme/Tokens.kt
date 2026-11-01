package com.spektralab.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The design's own numbers, taken from the Figma file itself.
 *
 * **Where these came from.** `get_design_context` on the "SpektraLab · Android
 * 原型" frames returns each layer's exact Tailwind classes, so every value
 * below is read off the file rather than eyeballed: `#100e0c` is the C1
 * background's `bg-[#100e0c]`, `#e1a95f` is `action/import-plus`'s fill, 18 dp
 * is `card/continue`'s `rounded-[18px]`.
 *
 * **What is not read off the file.** The design specifies Inter and Cascadia
 * Mono. Neither ships with Android, and bundling them is a separate decision
 * (two font files, their licences, and the APK size) that has not been taken.
 * `Sans` and `Mono` below therefore select the **platform** faces, which are
 * metrically close at these sizes but are not the design's. Everything else --
 * colour, size, weight, spacing -- is the file's. When the fonts are decided,
 * only these two properties change.
 *
 * **Why a reference width.** The frames are 412 wide, which is the Pixel-class
 * width this app is designed against; every offset in the screens is authored
 * in that space. The screens are written with those same literal dp values and
 * centred, rather than scaled, because a scaled 412 would make the type lie
 * about its size on a 360-wide phone. See `ScreenFrame`.
 */
object Tokens {

    // -- surfaces ---------------------------------------------------------
    /** The app's ground. C1's `bg-[#100e0c]`, and every screen's backdrop. */
    val Ground = Color(0xFF100E0C)

    /** A raised card: `card/continue`'s `bg-[#191512]`. */
    val Card = Color(0xFF191512)

    /** A card's hairline: `border-[rgba(255,255,255,0.08)]`. */
    val Hairline = Color(0x14FFFFFF)

    /** The bottom bar's fill: `bg-[rgba(255,255,255,0.05)]`. */
    val Bar = Color(0x0DFFFFFF)

    /** A pill's fill inside a card: `bg-[rgba(255,255,255,0.06)]`. */
    val Pill = Color(0x0FFFFFFF)

    /** A secondary button's border: `border-[rgba(255,255,255,0.1)]`. */
    val PillHairline = Color(0x1AFFFFFF)

    /** An unselected track: `bg-[rgba(255,255,255,0.1)]`. */
    val Track = Color(0x1AFFFFFF)

    // -- ink --------------------------------------------------------------
    /** Primary text: `#f5f1ea`, warm white rather than `#fff`. */
    val Ink = Color(0xFFF5F1EA)

    /** Secondary text: `#b4aca1`. */
    val InkSecondary = Color(0xFFB4ACA1)

    /** Tertiary text and captions: `#79726a`. */
    val InkTertiary = Color(0xFF79726A)

    // -- accent -----------------------------------------------------------
    /** The one accent: `#e1a95f`, an amber that reads as a safelight. */
    val Accent = Color(0xFFE1A95F)

    /** A progress fill: `bg-[rgba(225,169,95,0.9)]`. */
    val AccentFill = Color(0xE6E1A95F)

    /** A scrim over a photo: `rgba(16,14,12,0.8)`, the ground at 80 %. */
    val Scrim = Color(0xCC100E0C)

    // -- type -------------------------------------------------------------
    /** Inter in the file; the platform sans until the font decision is made. */
    val Sans = FontFamily.SansSerif

    /** Cascadia Mono in the file; the platform mono meanwhile. */
    val Mono = FontFamily.Monospace

    /** The type scale, as (size, weight) pairs the file actually uses. */
    object Type {
        val ScreenTitle = 22.sp to FontWeight.SemiBold   // "SpektraLab"
        val SectionTitle = 13.sp to FontWeight.Bold      // "我的胶卷"
        val CardTitle = 16.sp to FontWeight.SemiBold     // "DSC03710.ARW"
        val RollTitle = 12.sp to FontWeight.SemiBold     // "Portra 160"
        val Body = 10.sp to FontWeight.Normal            // "Portra 400 · Supra Endura"
        val Caption = 9.sp to FontWeight.Normal          // "17 张 · 今天"
        val Micro = 7.5.sp to FontWeight.SemiBold        // "NEF"
        val PillLabel = 9.5.sp to FontWeight.SemiBold    // "继续编辑"
        val ButtonLabel = 12.sp to FontWeight.SemiBold   // "从相册导入"
        val Eyebrow = 10.sp to FontWeight.SemiBold       // "继续冲洗"
    }

    // -- shape ------------------------------------------------------------
    object Radius {
        val Screen = 44.dp
        val Card = 18.dp
        val Tile = 16.dp
        val Thumb = 14.dp
        val Pill = 22.dp
        val Chip = 14.dp
        val Track = 2.dp
    }

    /** The frame width every offset in the screens is authored against. */
    val ReferenceWidth = 412.dp
}
