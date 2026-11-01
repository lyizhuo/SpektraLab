package com.spektralab.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The product's theme, expressed as Material 3 rather than as coordinates.
 *
 * **What changed and why.** The first pass took the Figma frames literally: every
 * element was placed with `Modifier.offset(x, y)` in the frames' 412×915 space.
 * That is not a layout, it is a painting — nothing reflows, a 393 dp phone got a
 * band 19 dp wider than the screen, and everything the file puts below y=766
 * fell off a 766 dp viewport. The palette and the type scale below are still the
 * file's (they are colour and typography, which do not depend on a screen size);
 * the *positions* now come from Material 3 and from Compose's own layout
 * primitives, which is what makes them adapt.
 *
 * The one thing deliberately kept from the frames is the ground: this is a
 * darkroom, so the scheme is dark and the accent is the safelight amber.
 */
private val Darkroom = darkColorScheme(
    primary = Color(0xFFE1A95F),
    onPrimary = Color(0xFF231708),
    primaryContainer = Color(0xFF3A2A12),
    onPrimaryContainer = Color(0xFFF5D9AE),
    secondary = Color(0xFFB4ACA1),
    secondaryContainer = Color(0xFFE1A95F),
    onSecondaryContainer = Color(0xFF231708),
    onSecondary = Color(0xFF241D18),
    tertiary = Color(0xFF79726A),
    background = Color(0xFF100E0C),
    onBackground = Color(0xFFF5F1EA),
    surface = Color(0xFF191512),
    onSurface = Color(0xFFF5F1EA),
    surfaceVariant = Color(0xFF221D19),
    onSurfaceVariant = Color(0xFFB4ACA1),
    surfaceContainer = Color(0xFF17130F),
    surfaceContainerHigh = Color(0xFF1F1A16),
    outline = Color(0xFF3A332D),
    outlineVariant = Color(0xFF262220),
    error = Color(0xFFFFB4A2),
    onError = Color(0xFF3B0A00),
    scrim = Color(0xCC000000),
    inverseSurface = Color(0xFFF5F1EA),
    inverseOnSurface = Color(0xFF191512),
)

/**
 * The frame's type scale, mapped onto M3's roles.
 *
 * A role is chosen by how the text behaves, not by its size in one frame: the
 * file's 22 sp screen title is `headlineSmall`, its 13 sp section heading is
 * `titleSmall`, its 9–10 sp captions are `labelMedium`/`labelSmall`. Mono and
 * numeric readouts get `FontFamily.Monospace`, which carries the film-edge
 * character without a bundled font.
 */
private val SpektraTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 9.5.sp, lineHeight = 13.sp),
)

@Composable
fun SpektraLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Darkroom,
        typography = SpektraTypography,
        content = content,
    )
}
