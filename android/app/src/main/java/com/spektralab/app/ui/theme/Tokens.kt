package com.spektralab.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The design's own numbers, taken from the Figma file itself.
 *
 * **Where these came from.** `get_design_context` on the "SpektraLab · Android
 * 原型" frames returns each layer's exact Tailwind classes and geometry, so
 * every value below is read off the file rather than eyeballed: `#100e0c` is
 * the C1 background's `bg-[#100e0c]`, `#e1a95f` is `action/import-plus`'s fill,
 * 18 dp is `card/continue`'s `rounded-[18px]`, 116 dp is the recent grid's tile.
 *
 * **The type scale is the file's, not Material's.** Every screen used to take
 * its sizes from `MaterialTheme.typography`, whose `bodyLarge` is 14 sp against
 * the file's 10 — the screens then read a size and a half too large and the
 * layout around them had to grow to match, which is why the first pass looked
 * nothing like the prototype. [Type] names the sizes the file actually uses and
 * the screens use only these.
 *
 * **What is not read off the file.** The design specifies Inter and Cascadia
 * Mono. Neither ships with Android, and bundling them is a separate decision
 * (two font files, their licences, and the APK size) that has not been taken.
 * `Sans` and `Mono` therefore select the **platform** faces, which are
 * metrically close at these sizes but are not the design's. Everything else --
 * colour, size, weight, spacing -- is the file's.
 *
 * **Why a reference width.** The frames are 412 wide, which is the Pixel-class
 * width this app is designed against; every offset in the prototype is authored
 * in that space. Screens lay out in the file's own dp values inside
 * [ReferenceWidth] and are centred on wider devices rather than stretched, so a
 * 20 dp margin stays 20 dp and a 116 dp tile stays a 116 dp tile.
 */
object Tokens {

    // -- surfaces ---------------------------------------------------------
    /** The app's ground. C1's `bg-[#100e0c]`, and every screen's backdrop. */
    val Ground = Color(0xFF100E0C)

    /** Behind the frame on A1 and A5: `bg-[#0c0b0a]`, a stop darker. */
    val Canvas = Color(0xFF0C0B0A)

    /** A raised card: `card/continue`'s `bg-[#191512]`. */
    val Card = Color(0xFF191512)

    /** A card's hairline: `border-[rgba(255,255,255,0.08)]`. */
    val Hairline = Color(0x14FFFFFF)

    /** A hairline drawn as a 1 dp line: `bg-[rgba(255,255,255,0.08)]`. */
    val Rule = Color(0x14FFFFFF)

    /** The bottom bar's fill: `bg-[rgba(255,255,255,0.05)]`. */
    val Bar = Color(0x0DFFFFFF)

    /** A tile or the filmstrip's own fill: same 5 % white. */
    val Raise = Color(0x0DFFFFFF)

    /** A pill's fill inside a card: `bg-[rgba(255,255,255,0.06)]`. */
    val Pill = Color(0x0FFFFFFF)

    /** A secondary button's border: `border-[rgba(255,255,255,0.1)]`. */
    val PillHairline = Color(0x1AFFFFFF)

    /** An unselected track: `bg-[rgba(255,255,255,0.1)]`. */
    val Track = Color(0x1AFFFFFF)

    /** A sheet's own surface, one step above the ground. */
    val Sheet = Color(0xFF17130F)

    /** A modal scrim over the screen behind a sheet. */
    val Scrim = Color(0xB3100E0C)

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

    /** A selected pill's wash: `bg-[rgba(225,169,95,0.14)]`. */
    val AccentGhost = Color(0x24E1A95F)

    /** A selected pill's border: `border-[rgba(225,169,95,0.45)]`. */
    val AccentBorder = Color(0x73E1A95F)

    /** Ink on an amber button: `#24180b` on C1's camera pill. */
    val OnAccent = Color(0xFF24180B)

    // -- film -------------------------------------------------------------
    /** The negative's base, A1's `from-[#7a4022]`. */
    val FilmBase = Color(0xFF7A4022)

    /** Its shadowed edge, A1's `to-[#552c17]`. */
    val FilmShade = Color(0xFF552C17)

    /** Edge printing on the negative: `rgba(42,20,9,0.85)`. */
    val EdgeInk = Color(0xD92A1409)

    /** A sprocket hole: `bg-[rgba(22,13,6,0.9)]`. */
    val Sprocket = Color(0xE6160D06)

    // -- type -------------------------------------------------------------
    /** Inter in the file; the platform sans until the font decision is made. */
    val Sans = FontFamily.SansSerif

    /** Cascadia Mono in the file; the platform mono meanwhile. */
    val Mono = FontFamily.Monospace

    /**
     * Every text style the frames use, named by the job it does.
     *
     * Line heights are the file's `leading-[normal]` where it says so (which
     * Compose reproduces by leaving `lineHeight` unset) and the explicit
     * leading where the frame sets one.
     */
    object Type {
        /** C1's "SpektraLab": 22 sp semibold. */
        val Display = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

        /** A screen's title inside a bar (A5's 裁剪): 13 sp medium. */
        val BarTitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)

        /** A file name in A1's top bar: 13 sp semibold. */
        val FileName = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

        /** A section heading (我的胶卷): 13 sp bold. */
        val Section = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold)

        /** A card's title (DSC03710.ARW): 16 sp semibold. */
        val CardTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

        /** A roll's name (Portra 160): 12 sp semibold. */
        val RollName = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

        /** A sheet's heading (胶片与相纸): 15 sp semibold. */
        val SheetTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

        /** Body copy and row labels: 10 sp. */
        val Body = TextStyle(fontSize = 10.sp)

        /**
         * A parameter page's row label: 11.5 sp.
         *
         * One step larger than [Body] because A3 and A4 are pages of controls
         * rather than of cards -- the label is what the finger aims at, and the
         * file sets it at 11.5 where C1's captions are 10.
         */
        val RowLabel = TextStyle(fontSize = 11.5.sp)

        /** A parameter page's value column: 11 sp mono. */
        val RowValue = TextStyle(fontSize = 11.sp, fontFamily = Mono)

        /** A parameter page's section heading: 13 sp semibold. */
        val RowSection = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

        /** A row's value column: 10 sp mono, which is what the file sets. */
        val Value = TextStyle(fontSize = 10.sp, fontFamily = Mono)

        /** Captions and counts: 9 sp. */
        val Caption = TextStyle(fontSize = 9.sp)

        /** A pill's label inside a card: 9.5 sp semibold. */
        val PillLabel = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)

        /** A toolbar icon's label: 9.5 sp. */
        val ToolLabel = TextStyle(fontSize = 9.5.sp)

        /** A big button's label (相机, 相册): 14 sp medium. */
        val Button = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)

        /** A primary action's label (导出): 12.5 sp semibold. */
        val Action = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)

        /** The eyebrow above a card's title (继续冲洗): 10 sp semibold. */
        val Eyebrow = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold)

        /** A segmented control's label: 10.5 sp. */
        val Segment = TextStyle(fontSize = 10.5.sp)

        /** A chip on a photograph (NEF): 7.5 sp semibold. */
        val Micro = TextStyle(fontSize = 7.5.sp, fontWeight = FontWeight.SemiBold)

        /** A mono readout inside a badge (full · 6048×4024): 8.5 sp. */
        val Readout = TextStyle(fontSize = 8.5.sp, fontFamily = Mono)

        /** A mono subtitle under a title (PORTRA 400 · SUPRA ENDURA): 8 sp. */
        val MonoSub = TextStyle(fontSize = 8.sp, fontFamily = Mono, letterSpacing = 0.5.sp)

        /** The negative's own edge printing: 6.5 sp mono, tracked. */
        val Edge = TextStyle(fontSize = 6.5.sp, fontFamily = Mono, letterSpacing = 0.6.sp)

        /** The clock in the status area: 11 sp mono. */
        val Clock = TextStyle(fontSize = 11.sp, fontFamily = Mono)
    }

    // -- shape ------------------------------------------------------------
    object Radius {
        val Card = 18.dp
        val Tile = 16.dp
        val Thumb = 14.dp
        val Pill = 22.dp
        val Chip = 14.dp
        val Track = 2.dp
    }

    /** The file's horizontal margin, used by every screen. */
    val Margin = 20.dp

    /** The frame width every offset in the screens is authored against. */
    val ReferenceWidth = 412.dp
}
