package com.spektralab.app.ui.components

import android.content.Context
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.app.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The prototype's own widgets, drawn rather than borrowed.
 *
 * Every screen in the file is built from a handful of shapes: a 22 dp-high
 * pill, a 2 dp track with a 12 dp knob, a 26 dp switch, a hairline, and a
 * negative with sprockets and edge printing. Material 3's equivalents are the
 * wrong size in every case -- its `Button` is 40 dp tall with 24 dp of
 * horizontal padding, its `Slider` is 48 dp tall with a 20 dp thumb, its
 * `Switch` is 52×32 against the file's 26×16 -- so using them is what made the
 * first pass read as "a Material app in the right colours" instead of the
 * prototype.
 *
 * These are the file's geometry in the file's units, with no Material layout
 * underneath.
 */

/** A 1 dp hairline, the separator the file draws between regions. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Tokens.Rule) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * The file's big action pill: 44 dp high, 22 dp radius.
 *
 * C1's 相机 and 相册 are the same shape in two fills, which is why the fill is
 * a parameter rather than two composables; A5's 完成 and A6's 导出 are the same
 * composable at 48 and 44.
 */
@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    filled: Boolean = true,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier
            .height(height)
            .clip(shape)
            .background(
                when {
                    !filled -> Tokens.Raise
                    enabled -> Tokens.Accent
                    else -> Tokens.Accent.copy(alpha = 0.35f)
                },
            )
            .then(if (filled) Modifier else Modifier.border(1.dp, Tokens.PillHairline, shape))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = Tokens.Type.Button,
            color = if (filled) Tokens.OnAccent else Tokens.Ink,
            maxLines = 1,
        )
    }
}

/**
 * A small pill inside a card: 28 dp high, 14 dp radius, 6 % white.
 *
 * `action/continue` on C1 and the pill row on A5 are this shape; the selected
 * state is the amber wash and border `pill-配光` uses on A1.
 */
@Composable
fun ChipPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    selected: Boolean = false,
    style: TextStyle = Tokens.Type.PillLabel,
) {
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(if (selected) Tokens.AccentGhost else Tokens.Pill)
            .border(1.dp, if (selected) Tokens.AccentBorder else Tokens.PillHairline, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = style,
            color = if (selected) Tokens.Accent else Tokens.Ink,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A section heading with its right-hand link: the 13 sp bold + 10 sp pair the
 * file uses above 我的胶卷 and 最近照片.
 */
@Composable
fun SectionHeading(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    trailingAccent: Boolean = false,
    onTrailing: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = Tokens.Type.Section, color = Tokens.Ink)
        if (trailing != null) {
            Text(
                text = trailing,
                style = Tokens.Type.Body,
                color = if (trailingAccent) Tokens.Accent else Tokens.InkSecondary,
                modifier = Modifier.then(
                    if (onTrailing != null) Modifier.clickable(onClick = onTrailing) else Modifier,
                ),
            )
        }
    }
}

/**
 * The file's slider: a 2 dp track with a 12 dp knob, 14 dp of total height.
 *
 * A3's 胶片曝光 and A4's nine rows are all this. The track is 10 % white, the
 * filled part is the accent, and the knob is warm white with an amber centre --
 * which is the one place the amber appears on those pages.
 */
@Composable
fun TrackSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    centerTick: Boolean = false,
) {
    // Material 3's `Slider`, not a hand-drawn Canvas.
    //
    // The Canvas version drew a correct track and mapped x to a value, and it
    // never received a touch inside A3/A4's scrolling column: the tap and the
    // drag were registered on a node the scroll container claimed first. Every
    // parameter row therefore read as "not wired" while the wire was fine — the
    // deltas were simply never produced. `Slider` owns its own gesture arena,
    // its own 48 dp target and its own accessibility semantics, which is exactly
    // the kind of thing not to re-implement.
    Slider(
        value = value,
        onValueChange = { v ->
            onValueChange(v)
        },
        // The end of the gesture, not every frame: the caller commits the
        // engine delta here so a drag is one render instead of a queue.
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(44.dp),
        colors = SliderDefaults.colors(
            thumbColor = Tokens.Ink,
            activeTrackColor = Tokens.Accent,
            inactiveTrackColor = Tokens.Track,
            disabledThumbColor = Tokens.InkTertiary,
            disabledActiveTrackColor = Tokens.Track,
            disabledInactiveTrackColor = Tokens.Track.copy(alpha = 0.5f),
        ),
    )
}

/**
 * Where a pointer x lands on a [TrackSlider], in the slider's own range.
 *
 * The knob is 12 dp wide, so a track that filled its whole box would have its
 * end stops half a knob off the finger. Insetting by the knob's radius is what
 * puts the value under the touch at 0 % and at 100 % alike.
 */
private fun valueAt(
    x: Float,
    width: Int,
    knobPx: Float,
    range: ClosedFloatingPointRange<Float>,
): Float {
    if (width <= 0) return range.start
    val inset = knobPx / 2f
    val usable = (width - 2 * inset).coerceAtLeast(1f)
    val at = ((x - inset) / usable).coerceIn(0f, 1f)
    return range.start + at * (range.endInclusive - range.start)
}

/**
 * The file's switch: a 26×16 track that fills with the accent and a 12 dp knob. *
 * Material's `Switch` is 52×32 -- twice the size -- and it is the control A3's
 * 颗粒 and 光晕 rows use, so it is drawn here at the file's geometry.
 */
@Composable
fun SpektraSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val trackWidth = 26.dp
    val trackHeight = 16.dp
    val knobSize = 12.dp
    Box(
        modifier
            .size(trackWidth, trackHeight)
            .clip(RoundedCornerShape(trackHeight / 2))
            .background(if (checked && enabled) Tokens.Accent else Tokens.Track)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = if (checked) trackWidth - knobSize - 4.dp else 2.dp)
                .size(knobSize)
                .clip(CircleShape)
                .background(if (enabled) Tokens.Ink else Tokens.InkTertiary),
        )
    }
}

/**
 * A label column and a value column, the row A3 and A4 repeat many times.
 *
 * The value is monospace and right-aligned because the file sets it that way:
 * it is a readout, not prose, and it has to hold `+0.25` and `-18` at the same
 * width.
 */
@Composable
fun ReadoutRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelColor: Color = Tokens.InkSecondary,
    valueColor: Color = Tokens.Accent,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Tokens.Type.Body, color = labelColor, modifier = Modifier.weight(1f))
        Text(value, style = Tokens.Type.Value, color = valueColor, maxLines = 1)
    }
}

/**
 * A negative: the film base, its sprocket holes, and the edge printing.
 *
 * A1, B1 and A5 all show the frame inside this; C2 shows the same belt around
 * an empty slot while it imports. The geometry is A1's -- a 22 dp band at the
 * top and bottom that carries the edge, and the photo inset by 8 dp.
 */
@Composable
fun FilmFrame(
    modifier: Modifier = Modifier,
    edgeTop: String = "",
    edgeTopRight: String = "",
    edgeBottomLeft: String = "",
    edgeBottomRight: String = "",
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Brush.horizontalGradient(listOf(Tokens.FilmBase, Tokens.FilmShade))),
    ) {
        Column(Modifier.fillMaxWidth()) {
            FilmEdge(label = edgeTop, right = edgeTopRight)
            // The window takes whatever height the frame leaves it, so the
            // negative keeps its 364:528 shape on any screen instead of
            // collapsing to its content's size.
            Box(Modifier.padding(horizontal = 8.dp).weight(1f)) { content() }
            FilmEdge(label = edgeBottomLeft, right = edgeBottomRight)
        }
    }
}

/** One 22 dp band of a negative: edge printing over a row of sprocket holes. */
@Composable
private fun FilmEdge(label: String, right: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .padding(horizontal = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = Tokens.Type.Edge, color = Tokens.EdgeInk, maxLines = 1)
            if (right.isNotEmpty()) {
                Text(right, style = Tokens.Type.Edge, color = Tokens.EdgeInk, maxLines = 1)
            }
        }
        // Nine holes at 13 dp on a 39.53 dp pitch is A1's own spacing; the row
        // is drawn rather than laid out so the pitch stays uniform at any width.
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val pitch = size.width / 9.4f
            val holeWidth = 13.dp.toPx()
            val holeHeight = 8.dp.toPx()
            for (index in 0 until 9) {
                drawRoundRect(
                    color = Tokens.Sprocket,
                    topLeft = Offset(index * pitch, size.height - holeHeight - 3.dp.toPx()),
                    size = GeomSize(holeWidth, holeHeight),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
        }
    }
}

/**
 * The histogram A1 floats over the frame (78×44) and the taller one A3 puts in
 * its latitude card.
 *
 * `levels` is normalised 0..1; the bars are accent at 75 %, which is the file's
 * `rgba(225,169,95,0.75)`.
 */
@Composable
fun HistogramBars(
    levels: List<Float>,
    modifier: Modifier = Modifier,
    barCorner: Dp = 1.dp,
    color: Color = Tokens.Accent.copy(alpha = 0.75f),
) {
    Canvas(modifier) {
        if (levels.isEmpty()) return@Canvas
        val pitch = size.width / levels.size
        val barWidth = (pitch - 1.dp.toPx()).coerceAtLeast(1f)
        for ((index, level) in levels.withIndex()) {
            val height = (size.height * level.coerceIn(0f, 1f)).coerceAtLeast(1f)
            drawRoundRect(
                color = color,
                topLeft = Offset(index * pitch, size.height - height),
                size = GeomSize(barWidth, height),
                cornerRadius = CornerRadius(barCorner.toPx()),
            )
        }
    }
}

/**
 * The `full · 6048×4024` badge in the canvas's bottom-right corner.
 *
 * It reports what the canvas is holding -- full resolution or a preview --
 * which is the same fact the macOS app's badge carries (`Session.canvasBadges`,
 * `ARCHITECTURE.md` §7.7). 18 dp high and 8.5 sp mono, so it reads as an
 * instrument rather than a button.
 */
@Composable
fun FrameBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(18.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Tokens.Pill)
            .border(1.dp, Tokens.Ink.copy(alpha = 0.16f), RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Tokens.Type.Readout, color = Tokens.Ink.copy(alpha = 0.9f), maxLines = 1)
    }
}

/** The `NEF`/`ARW` chip in a recent tile's corner: 36×16 at 8,8. */
@Composable
fun FormatChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Tokens.Scrim)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Tokens.Type.Micro, color = Tokens.InkSecondary, maxLines = 1)
    }
}

/**
 * A roll's label band, in place of the file's photographed box art.
 *
 * The prototype shows Kodak's and Fuji's packaging, which this product does not
 * license; what it shows instead is the stock's identity -- its name in the
 * display face over a wash of the film's colour -- so the tile still reads at a
 * glance as "that roll" without copying a trade dress.
 */
@Composable
fun RollLabel(
    name: String,
    tint: Color,
    modifier: Modifier = Modifier,
    ink: Color = Color.White,
    detail: String? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.95f), tint.copy(alpha = 0.55f))))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(
                text = name,
                style = Tokens.Type.CardTitle.copy(fontSize = 15.sp),
                color = ink.copy(alpha = 0.94f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = Tokens.Type.MonoSub.copy(fontSize = 6.5.sp),
                    color = ink.copy(alpha = 0.72f),
                    maxLines = 1,
                )
            }
        }
    }
}

/** A thumbnail of a content URI, decoded off the main thread. */
@Composable
fun UriThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val context: Context = LocalContext.current.applicationContext
    val bitmap by produceState<ImageBitmap?>(null, uri.toString()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(384, 384), null).asImageBitmap()
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(Tokens.Raise))
    }
}

/** The home indicator bar the frames draw at the bottom of a 412-wide screen. */
@Composable
fun HomeIndicator(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(120.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Tokens.Ink.copy(alpha = 0.35f)),
        )
    }
}

/**
 * The card a sheet's rows sit in: 6 % white, 1 dp hairline, 14 dp radius.
 *
 * A6's five rows and A3's grouped rows are this shape, flat against the sheet
 * rather than the raised `#191512` cards on the ground.
 */
@Composable
fun RowCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Tokens.Pill)
            .border(1.dp, Tokens.Hairline, RoundedCornerShape(14.dp)),
    ) {
        content()
    }
}
