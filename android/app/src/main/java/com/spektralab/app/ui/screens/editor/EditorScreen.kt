package com.spektralab.app.ui.screens.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.TransformableState
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spektralab.app.EditorViewModel.UiState
import com.spektralab.app.R
import com.spektralab.app.rolls.stockLabel
import com.spektralab.app.ui.components.ChipPill
import com.spektralab.app.ui.components.FilmFrame
import com.spektralab.app.ui.components.FrameBadge
import com.spektralab.app.ui.components.Hairline
import com.spektralab.app.ui.components.HistogramBars
import com.spektralab.app.ui.theme.Tokens

/** A1's three tools, and the only three the frame draws. */
enum class EditorTool(val label: String) {
    Film("胶片"),
    Params("参数"),
    Crop("裁剪"),
}

/**
 * A1 — the editor.
 *
 * The frame's own geometry: a 52 dp bar carrying the file name and its stock, a
 * canvas holding the negative with a histogram floating top-right and the
 * `full · 6048×4024` badge bottom-right, a 76 dp filmstrip, and a 72 dp toolbar
 * whose three labelled icons sit at the left and whose amber 导出 pill sits at
 * the right.
 *
 * **Where the file places things and where this does not.** The frame is a
 * 412×915 painting; the canvas between the bars takes what is left, and the
 * negative is sized from the canvas (a 364:528 box centred in it) rather than
 * from an absolute offset, so a taller phone shows a taller negative instead of
 * a gap. The toolbar's 33 dp inset and 46 dp pitch are the file's.
 *
 * The engine's own message gets a line under the bar: a canvas that fails to
 * open must say why, or black is indistinguishable from a bug.
 */
@Composable
fun EditorScreen(
    state: UiState,
    strip: List<StripEntry>,
    selectedIndex: Int,
    onSelectFrame: (Int) -> Unit,
    onOpenPhotos: () -> Unit,
    onClose: () -> Unit,
    onTool: (EditorTool?) -> Unit,
    tool: EditorTool?,
    onExport: () -> Unit,
    sheet: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Ground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .testTag("editor"),
    ) {
        EditorBar(
            state = state,
            onClose = onClose,
            onLook = { onTool(EditorTool.Film) },
            onMore = { onTool(EditorTool.Params) },
        )

        (state.error ?: state.message)?.let { notice ->
            Text(
                text = notice,
                style = Tokens.Type.Caption,
                color = Tokens.Accent,
                modifier = Modifier.padding(horizontal = Tokens.Margin, vertical = 2.dp)
                    .testTag("editor-notice"),
            )
        }

        CanvasArea(state = state, modifier = Modifier.weight(1f).fillMaxWidth())

        FilmStrip(
            strip = strip,
            selectedIndex = selectedIndex,
            onSelectFrame = onSelectFrame,
            onOpenPhotos = onOpenPhotos,
        )

        Toolbar(
            tool = tool,
            onTool = onTool,
            onExport = onExport,
            exportEnabled = state.exportGate?.allowed != false,
        )
    }
    sheet()
}

/** 52 dp: back, the file and its stock, 配光, and the overflow. */
@Composable
private fun EditorBar(
    state: UiState,
    onClose: () -> Unit,
    onLook: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_left),
            contentDescription = "返回",
            tint = Tokens.Ink,
            modifier = Modifier
                .size(24.dp)
                .clickable(onClick = onClose)
                .testTag("editor-close"),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = state.source?.name ?: "未打开照片",
                style = Tokens.Type.FileName,
                color = Tokens.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = filmLine(state),
                style = Tokens.Type.MonoSub,
                color = Tokens.InkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(58.dp)) {
            ChipPill(
                label = "配光",
                onClick = onLook,
                height = 26.dp,
                selected = true,
                style = Tokens.Type.Segment.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth().testTag("editor-look"),
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            painter = painterResource(R.drawable.ic_ellipsis),
            contentDescription = "更多",
            tint = Tokens.InkSecondary,
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onMore),
        )
    }
}

/**
 * The light box: a warm glow, the negative centred in it, and the two
 * instruments that sit over the corner.
 *
 * The negative is sized from the canvas rather than from the file's absolute
 * 364×528: it takes 364/528 of the height it is given when that is the smaller
 * constraint, and the width it is given less 44 otherwise, so the frame is
 * always fully visible and always the same shape.
 */
@Composable
private fun CanvasArea(state: UiState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(Tokens.Canvas)) {
        // The window is the photograph's own shape, not the mock's: the frame is
        // the window plus its 22 dp edges and 8 dp sides, so a landscape file
        // gets a landscape negative instead of a portrait one with black bars
        // inside it. The instruments above and below the frame keep their own
        // room -- 62 dp at the top for the histogram, 39 dp at the bottom for the
        // badge -- so nothing overlaps the edge printing.
        val photoRatio = state.bitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1.5f
        val horizontal = maxWidth - 44.dp
        val vertical = maxHeight - 62.dp - 39.dp
        val windowHeight = minOf(vertical, (horizontal - 16.dp) / photoRatio)
        val windowWidth = windowHeight * photoRatio
        val frameWidth = windowWidth + 16.dp
        val frameHeight = windowHeight + 44.dp

        // A soft safelight behind the negative, which is what keeps the canvas
        // from reading as a black rectangle with a picture in it.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF2A2018), Color(0xFF15110D), Tokens.Canvas),
                        radius = maxWidth.value * 1.1f,
                    ),
                ),
        )

        FilmFrame(
            modifier = Modifier
                .width(frameWidth)
                .height(frameHeight)
                .align(Alignment.Center),
            edgeTop = filmEdgeName(state),
            edgeTopRight = frameNumberOf(state),
            edgeBottomLeft = "16A",
            edgeBottomRight = "${frameNumberOf(state)} ▮▮▮",
        ) {
            Photo(state = state)
        }

        Histogram(
            state = state,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 18.dp),
        )
        FrameBadge(
            text = canvasBadge(state),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 21.dp),
        )
    }
}

/**
 * `full · 6048×4024` — the tier the canvas is holding and its real pixel size.
 *
 * The same fact the macOS badge carries (`Session.canvasBadges`): a frame that is
 * a preview must not be mistaken for the full render, and the only honest way to
 * say so is to print both the tier and the pixels behind it.
 */
private fun canvasBadge(state: UiState): String {
    val info = state.lastRender ?: return state.tier
    return "${state.tier} · ${info.width}×${info.height}"
}

/**
 * The photograph itself, fitted inside the negative's window.
 *
 * **Pinch to inspect, hold to see the frame as it was opened.** Both are
 * Compose's own components: `transformable` owns the pinch and the pan, and
 * `detectTapGestures` owns the press. Nothing divides pixels by hand — the scale
 * and the translation go into a `graphicsLayer`, which is also what keeps the
 * gesture at the compositor's frame rate instead of the engine's.
 *
 * The long press shows `state.original`, the first frame of the session. It is
 * deliberately *not* a second render with the current parameters: "before" has to
 * mean what came out of the camera, or it is just another after.
 */
@Composable
private fun Photo(state: UiState) {
    val bitmap = state.bitmap
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var holding by remember { mutableStateOf(false) }

    // A new frame starts at 1×: a zoom that survived an open would land on
    // somebody else's composition.
    LaunchedEffect(state.source?.uri) {
        scale = 1f
        offset = Offset.Zero
    }
    val transform = remember {
        TransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 8f)
            offset = if (scale > 1.001f) offset + panChange else Offset.Zero
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0908))
            .testTag("editor-frame")
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { holding = true },
                    onPress = {
                        tryAwaitRelease()
                        holding = false
                    },
                )
            }
            .transformable(state = transform),
    ) {
        val shown = if (holding) state.original ?: bitmap else bitmap
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
        if (holding) {
            // The badge is the difference between "this is the before" and "the
            // app forgot my edits".
            FrameBadge(
                text = "原图",
                modifier = Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 18.dp),
            )
        }
        if (scale > 1.001f) {
            FrameBadge(
                text = scale.roundToInt().toString() + "×",
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 21.dp),
            )
        }
    }
}

/**
 * The histogram: 78×44, blurred glass, twelve amber bars.
 *
 * The bins come from the bitmap the canvas is already holding, so the reading
 * describes what is on screen rather than a second render.
 */
@Composable
private fun Histogram(state: UiState, modifier: Modifier = Modifier) {
    val bins = remember(state.bitmap) { lumaBins(state.bitmap, 12) }
    Box(
        modifier
            .size(width = 78.dp, height = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Tokens.Pill)
            .border(1.dp, Tokens.Ink.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        HistogramBars(levels = bins, modifier = Modifier.fillMaxSize())
    }
}

/** The filmstrip: 76 dp, one 62×44 thumb per frame, the open one ringed. */
@Composable
private fun FilmStrip(
    strip: List<StripEntry>,
    selectedIndex: Int,
    onSelectFrame: (Int) -> Unit,
    onOpenPhotos: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Color(0xE6181513))) {
        Hairline(Modifier.padding(top = 0.dp), color = Tokens.Ink.copy(alpha = 0.06f))
        LazyRow(
            Modifier.fillMaxWidth().height(76.dp).testTag("film-strip"),
            contentPadding = PaddingValues(horizontal = 31.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(strip) { index, entry ->
                val selected = index == selectedIndex
                Box(
                    Modifier
                        .size(width = 62.dp, height = 44.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Tokens.Raise)
                        .then(
                            if (selected) {
                                Modifier.border(1.5.dp, Tokens.Accent, RoundedCornerShape(3.dp))
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onSelectFrame(index) }
                        .testTag("strip-$index"),
                ) {
                    StripThumbnail(entry, Modifier.fillMaxSize())
                }
            }
            item {
                Box(
                    Modifier
                        .size(width = 62.dp, height = 44.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Tokens.Raise)
                        .clickable(onClick = onOpenPhotos)
                        .testTag("strip-add-photos"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_plus),
                        contentDescription = "添加照片",
                        tint = Tokens.InkTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** 72 dp: three labelled tools at 33 dp in, the amber 导出 pill at the right. */
@Composable
private fun Toolbar(
    tool: EditorTool?,
    onTool: (EditorTool?) -> Unit,
    onExport: () -> Unit,
    exportEnabled: Boolean,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Tokens.Bar),
    ) {
        Hairline(color = Tokens.Ink.copy(alpha = 0.1f))
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 33.dp),
            horizontalArrangement = Arrangement.spacedBy(46.dp),
        ) {
            ToolButton(
                label = EditorTool.Film.label,
                icon = R.drawable.ic_film,
                active = tool == EditorTool.Film,
                onClick = { onTool(if (tool == EditorTool.Film) null else EditorTool.Film) },
                tag = "tool-film",
            )
            ToolButton(
                label = EditorTool.Params.label,
                icon = R.drawable.ic_sliders,
                active = tool == EditorTool.Params,
                onClick = { onTool(if (tool == EditorTool.Params) null else EditorTool.Params) },
                tag = "tool-params",
            )
            ToolButton(
                label = EditorTool.Crop.label,
                icon = R.drawable.ic_crop,
                active = tool == EditorTool.Crop,
                onClick = { onTool(if (tool == EditorTool.Crop) null else EditorTool.Crop) },
                tag = "tool-crop",
            )
        }
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 18.dp)
                .width(104.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(if (exportEnabled) Tokens.Accent else Tokens.Accent.copy(alpha = 0.35f))
                .clickable(enabled = exportEnabled, onClick = onExport)
                .testTag("tool-export"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("导出", style = Tokens.Type.Action, color = Tokens.OnAccent)
        }
    }
}

@Composable
private fun ToolButton(
    label: String,
    icon: Int,
    active: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    val tint = if (active) Tokens.Accent else Tokens.InkTertiary
    Column(
        Modifier.clickable(onClick = onClick).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(label, style = Tokens.Type.ToolLabel, color = tint)
    }
}

/** 24 bins of luminance, read from the canvas bitmap. */
private fun lumaBins(bitmap: Bitmap?, bins: Int): List<Float> {
    if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
        return List(bins) { 0f }
    }
    val counts = IntArray(bins)
    var total = 0
    // A stride keeps this cheap: the histogram is a shape, not a measurement.
    val stepX = (bitmap.width / 96).coerceAtLeast(1)
    val stepY = (bitmap.height / 96).coerceAtLeast(1)
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val luma =
                ((pixel shr 16 and 0xFF) * 299 + (pixel shr 8 and 0xFF) * 587 + (pixel and 0xFF) * 114) / 1000
            counts[(luma * (bins - 1) / 255).coerceIn(0, bins - 1)]++
            total++
            x += stepX
        }
        y += stepY
    }
    if (total == 0) return List(bins) { 0f }
    val peak = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
    return counts.map { it.toFloat() / peak }
}

private fun filmOf(state: UiState): String? =
    state.params?.optString("film_stock")?.takeIf { it.isNotEmpty() }

/** The negative's edge prints the stock in the manufacturer's own spelling. */
private fun filmEdgeName(state: UiState): String {
    val film = filmOf(state) ?: return "KODAK PORTRA 400"
    val tokens = film.split('_')
    val maker = tokens.firstOrNull()?.uppercase() ?: ""
    val rest = tokens.drop(1).joinToString(" ") { it.uppercase() }
    return listOf(maker, rest).filter { it.isNotBlank() }.joinToString(" ")
}

private fun filmLine(state: UiState): String {
    val film = filmOf(state)
    val paper = state.params?.optString("print_stock")?.takeIf { it.isNotEmpty() }
    return listOfNotNull(film?.let(::stockLabel), paper?.let(::stockLabel))
        .joinToString(" · ")
        .ifEmpty { "未选择胶片" }
}

private fun frameNumberOf(state: UiState): String = frameNumber(state.source?.name ?: "")

/**
 * `DSC03710.ARW` → `17`.
 *
 * The frame's number is printed on the negative's edge, so it has to be stable
 * for a given file. It is a hash of the name rather than a counter: a counter
 * would renumber every frame when one was added, and the edge printing would
 * stop matching photographs already developed.
 */
fun frameNumber(name: String): String {
    if (name.isEmpty()) return "17"
    var hash = 0
    for (char in name) hash = hash * 31 + char.code
    return ((hash and 0x7FFFFFFF) % 36 + 1).toString()
}

/** The film stock a frame is developed with, as the sheet prints it. */
fun stockOf(state: UiState): String {
    val film = state.params?.optString("film_stock")?.takeIf { it.isNotEmpty() } ?: return "PORTRA 400"
    return stockLabel(film) ?: film
}
