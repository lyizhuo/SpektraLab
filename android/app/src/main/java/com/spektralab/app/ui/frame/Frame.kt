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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.safeDrawingPadding
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
        modifier
            .fillMaxSize()
            .background(background)
            // The system bars are part of the problem, not decoration: the MI 8
            // gives ~766 dp of usable height, and guessing it was what put C3's
            // shutter 90 dp above its place.
            .safeDrawingPadding(),
    ) {
        // One picture, one scale. The frames are 412×915 and every offset in
        // every screen is written in that space; drawing them at their own
        // coordinates and scaling the *result* keeps every proportion, whereas
        // capping the width (the previous attempt) fixed the right edge and left
        // the bottom 100 dp off a 766 dp screen.
        //
        // The trade is honest and worth naming: type scales with the frame, so
        // a 13 sp label renders at 11.9 sp on a 393 dp phone instead of the
        // system's chosen size. A layout built from anchors and weights would
        // not do that, and that conversion is the next step — but it is a
        // rewrite of eight screens, and this makes every one of them correct on
        // every device today.
        val scale = minOf(
            maxWidth / Tokens.ReferenceWidth,
            maxHeight / ReferenceHeight,
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .width(Tokens.ReferenceWidth * scale)
                    .height(ReferenceHeight * scale),
            ) {
                Box(
                    Modifier
                        .width(Tokens.ReferenceWidth)
                        .height(ReferenceHeight)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            transformOrigin = TransformOrigin(0f, 0f),
                        ),
                ) {
                    content()
                }
            }
        }
    }
}

/** The frames' own height, in the same space as [Tokens.ReferenceWidth]. */
val ReferenceHeight: Dp = 915.dp
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
    fillWidth: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    var modifier: Modifier = Modifier.offset(x = x, y = y)
    // A screen's full-width bands must follow the *band*, never the frame's
    // 412: the MI 8 is 393 dp wide, and a child that insists on 412 hangs 19 dp
    // off the right edge — which is the "width is wrong" report, verbatim.
    modifier = when {
        fillWidth -> modifier.fillMaxWidth()
        width != null -> modifier.width(width)
        else -> modifier
    }
    if (height != null) modifier = modifier.height(height)
    Box(modifier, content = content)
}

/** A one-pixel rule across the band, at the frame's y. */
@Composable
fun Rule(y: Dp, color: Color = Tokens.Hairline) {
    Box(Modifier.offset(y = y).fillMaxWidth().height(1.dp).background(color))
}
