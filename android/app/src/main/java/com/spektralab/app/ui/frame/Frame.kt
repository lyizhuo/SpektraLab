package com.spektralab.app.ui.frame

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spektralab.app.ui.theme.Tokens

/**
 * The prototype's coordinate space.
 *
 * Every Figma frame is 412×915 and places its elements at absolute x/y. These
 * helpers let a screen be transcribed as the numbers the frame actually uses,
 * instead of being re-derived as a constraint layout — which is how the first
 * attempt ended up with the design's words and none of its geometry.
 *
 * Two things the frames assume and this makes explicit:
 *
 *  * the band is `min(deviceWidth, 412)`, centred. The MI 8 is 393 dp wide, so
 *    a fixed 412 band clipped 19 dp off the right edge — "全部" lost half of
 *    itself. Scaling instead would render 22 sp type at 19.2 sp and make every
 *    number in the file a lie;
 *  * a screen is `786 dp of content + a 129 dp action bar = 915`. A shorter
 *    phone scrolls the content rather than pushing the bar off the bottom,
 *    which is what a fixed 786 height did.
 */
@Composable
fun ScreenBand(
    modifier: Modifier = Modifier,
    background: Color = Tokens.Ground,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier.fillMaxSize().background(background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.width(minOf(maxWidth, Tokens.ReferenceWidth)).fillMaxHeight()) {
            content()
        }
    }
}

/**
 * A layer at the frame's `x`/`y`, optionally sized.
 *
 * Written as a function rather than a modifier chain so that a screen reads
 * like the frame: `Layer(20.dp, 116.dp, 372.dp, 154.dp) { … }`.
 */
@Composable
fun Layer(
    x: Dp,
    y: Dp,
    width: Dp? = null,
    height: Dp? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var modifier: Modifier = Modifier.offset(x = x, y = y)
    if (width != null) modifier = modifier.width(width)
    if (height != null) modifier = modifier.height(height)
    Box(modifier, content = content)
}

/** A one-pixel rule across the band, at the frame's y. */
@Composable
fun Rule(y: Dp, color: Color = Tokens.Hairline) {
    Box(Modifier.offset(y = y).fillMaxWidth().height(1.dp).background(color))
}
