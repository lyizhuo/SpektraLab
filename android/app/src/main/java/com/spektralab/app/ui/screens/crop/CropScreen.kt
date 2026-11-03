package com.spektralab.app.ui.screens.crop

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.app.R
import com.spektralab.app.ui.components.TrackSlider
import com.spektralab.app.ui.theme.Tokens

/**
 * A5 — crop, as a screen rather than a sheet.
 *
 * The prototype gives cropping the whole window: a 取消 / 裁剪 / 完成 bar, the
 * frame on a black canvas with a crop box whose corners are drawn as crosshairs,
 * the six aspect pills, the straighten slider, and the four rotate/flip pills
 * with the output size under them.
 *
 * **What moves and what does not.** The aspect pills and the four transform
 * pills write the engine's geometry fields, so the picture behind the box is the
 * frame the export will write. The box itself is a *preview* of that crop: its
 * inset is drawn from the aspect the pills set, and dragging the box is a
 * separate question (the engine takes a normalised rect, so the four numbers are
 * there) left for the next pass rather than faked here.
 */
@Composable
fun CropScreen(
    bitmap: Bitmap?,
    aspect: String,
    rotation: Float,
    outputLabel: String,
    onAspect: (String) -> Unit,
    onRotate: (Boolean) -> Unit,
    onFlip: (Boolean) -> Unit,
    onStraighten: (Float) -> Unit,
    /** The engine's crop rect, normalised; the canvas edits these four. */
    cropX: Float = 0f,
    cropY: Float = 0f,
    cropW: Float = 1f,
    cropH: Float = 1f,
    onCrop: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Tokens.Ground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .testTag("crop"),
    ) {
        CropBar(onCancel = onCancel, onDone = onDone)
        CropCanvas(
            bitmap = bitmap,
            aspect = aspect,
            cropX = cropX,
            cropY = cropY,
            cropW = cropW,
            cropH = cropH,
            onCrop = onCrop,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        CropTools(
            aspect = aspect,
            rotation = rotation,
            outputLabel = outputLabel,
            onAspect = onAspect,
            onRotate = onRotate,
            onFlip = onFlip,
            onStraighten = onStraighten,
        )
    }
}

/** 52 dp: 取消, 裁剪, 完成 — done is the amber one because it commits. */
@Composable
private fun CropBar(onCancel: () -> Unit, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = Tokens.Margin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "取消",
            style = Tokens.Type.Body.copy(fontSize = 10.5.sp),
            color = Tokens.InkSecondary,
            modifier = Modifier.clickable(onClick = onCancel).testTag("crop-cancel"),
        )
        Text(
            text = "裁剪",
            style = Tokens.Type.BarTitle,
            color = Tokens.Ink,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            text = "完成",
            style = Tokens.Type.RowLabel,
            color = Tokens.Accent,
            modifier = Modifier.clickable(onClick = onDone).testTag("crop-done"),
        )
    }
}

/**
 * The canvas: the frame, the crop box over it, the thirds and four corners.
 *
 * **The box is the engine's crop.** The wire takes a normalised rect
 * (`geometry_crop_x/y/w/h`), so this draws exactly that rect and the two
 * gestures edit the same four numbers: a drag moves the rect, a pinch resizes it
 * around its own centre. Nothing here is a preview of a different crop than the
 * one the export will write — the round trip is the point.
 *
 * Compose's own gestures do the work (`detectDragGestures`, `transformable`):
 * a hand-rolled pointer loop on this surface is what left A3/A4's sliders dead.
 */
@Composable
private fun CropCanvas(
    bitmap: Bitmap?,
    aspect: String,
    cropX: Float,
    cropY: Float,
    cropW: Float,
    cropH: Float,
    onCrop: (Float, Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.background(Tokens.Canvas), contentAlignment = Alignment.Center) {
        // The frame keeps the photograph's own shape, like the editor's does: a
        // landscape file gets a landscape frame, so the crop rect's fractions are
        // the ones the export will use rather than an artefact of the window.
        val photoRatio = if (bitmap != null && bitmap.height > 0) {
            bitmap.width.toFloat() / bitmap.height
        } else {
            3f / 2f
        }
        val frameHeight = minOf(maxHeight - 40.dp, (maxWidth - 40.dp) / photoRatio)
        val frameWidth = frameHeight * photoRatio
        val frameWidthPx = with(LocalDensity.current) { frameWidth.toPx() }
        val frameHeightPx = with(LocalDensity.current) { frameHeight.toPx() }

        fun clampX(value: Float, width: Float) = value.coerceIn(0f, (1f - width).coerceAtLeast(0f))
        fun clampY(value: Float, height: Float) = value.coerceIn(0f, (1f - height).coerceAtLeast(0f))

        // The finger moves a local rect; the engine sees one delta when the
        // gesture settles. Committing every frame queued a render per frame, which
        // is exactly what made the parameter page's sliders feel broken.
        var rect by remember { mutableStateOf(CropRect(cropX, cropY, cropW, cropH)) }

        // Anything that changes the rect from outside (an aspect pill, undo, a
        // re-open) wins, unless we are the ones mid-gesture.
        LaunchedEffect(cropX, cropY, cropW, cropH) {
            val fresh = CropRect(cropX, cropY, cropW, cropH)
            if (fresh != rect) rect = fresh
        }
        LaunchedEffect(rect) {
            if (rect == CropRect(cropX, cropY, cropW, cropH)) return@LaunchedEffect
            delay(120)
            onCrop(rect.x, rect.y, rect.w, rect.h)
        }

        Box(
            Modifier
                .width(frameWidth)
                .height(frameHeight)
                .clip(RoundedCornerShape(2.dp))
                // One detector, not two. `detectDragGestures` and `transformable`
                // on the same node compete for the same pointers and the drag
                // detector claims the first one, so a pinch never arrived. The old
                // `pointerInput` keys were also the crop values themselves, which
                // tore the handler down on the first update of every gesture.
                .pointerInput(frameWidthPx, frameHeightPx) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val r = rect
                        val width = (r.w / zoom).coerceIn(0.05f, 1f)
                        val height = (r.h / zoom).coerceIn(0.05f, 1f)
                        // Scale around the box's own centre, then pan.
                        val centreX = r.x + r.w / 2f
                        val centreY = r.y + r.h / 2f
                        rect = CropRect(
                            clampX(centreX - width / 2f + pan.x / frameWidthPx, width),
                            clampY(centreY - height / 2f + pan.y / frameHeightPx, height),
                            width,
                            height,
                        )
                    }
                },
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(Tokens.Raise))
            }

            val boxWidthDp = frameWidth * rect.w
            val boxHeightDp = frameHeight * rect.h
            val leftDp = frameWidth * rect.x
            val topDp = frameHeight * rect.y

            // Everything outside the rect, dimmed: four bands rather than one
            // rectangle so the frame's own light still reads around the keep.
            Box(
                Modifier.width(frameWidth).height(topDp).align(Alignment.TopStart)
                    .background(Tokens.Scrim),
            )
            Box(
                Modifier.width(frameWidth)
                    .height((frameHeight - topDp - boxHeightDp).coerceAtLeast(0.dp))
                    .align(Alignment.TopStart).offset(y = topDp + boxHeightDp)
                    .background(Tokens.Scrim),
            )
            Box(
                Modifier.width(leftDp).height(boxHeightDp).align(Alignment.TopStart)
                    .offset(y = topDp)
                    .background(Tokens.Scrim),
            )
            Box(
                Modifier.width((frameWidth - leftDp - boxWidthDp).coerceAtLeast(0.dp))
                    .height(boxHeightDp).align(Alignment.TopStart)
                    .offset(x = leftDp + boxWidthDp, y = topDp)
                    .background(Tokens.Scrim),
            )

            val boxShape = RoundedCornerShape(1.dp)
            Box(
                Modifier
                    .offset(x = leftDp, y = topDp)
                    .width(boxWidthDp)
                    .height(boxHeightDp)
                    .border(1.4.dp, Tokens.Ink.copy(alpha = 0.95f), boxShape)
                    .testTag("crop-box"),
            ) {
                // The rule of thirds, a hairline at 28 %.
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val third = Tokens.Ink.copy(alpha = 0.28f)
                    for (index in 1..2) {
                        val x = size.width * index / 3f
                        val y = size.height * index / 3f
                        drawLine(third, Offset(x, 0f), Offset(x, size.height), 1f)
                        drawLine(third, Offset(0f, y), Offset(size.width, y), 1f)
                    }
                }
                CropCorners()
            }
        }
    }
}

private data class CropRect(val x: Float, val y: Float, val w: Float, val h: Float)

/** Four crosshair corners in the file's own shape: a 26 dp bar crossing a 4 dp one. */
@Composable
private fun CropCorners() {
    val arm = 26.dp
    val thickness = 4.dp
    val color = Tokens.Ink
    Box(Modifier.fillMaxSize()) {
        listOf(
            Alignment.TopStart,
            Alignment.TopEnd,
            Alignment.BottomStart,
            Alignment.BottomEnd,
        ).forEach { corner ->
            Box(Modifier.fillMaxSize().padding(10.dp).testTag("crop-corner")) {
                Box(Modifier.align(corner).width(arm).height(thickness).background(color))
                Box(Modifier.align(corner).width(thickness).height(arm).background(color))
            }
        }
    }
}

/**
 * The tools under the canvas: six aspect pills, the straighten slider, the four
 * transform pills, and what the crop will write.
 */
@Composable
private fun CropTools(
    aspect: String,
    rotation: Float,
    outputLabel: String,
    onAspect: (String) -> Unit,
    onRotate: (Boolean) -> Unit,
    onFlip: (Boolean) -> Unit,
    onStraighten: (Float) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Tokens.Ground)
            .padding(horizontal = Tokens.Margin)
            .padding(top = 18.dp, bottom = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("自由", "原始", "1:1", "3:2", "4:3", "16:9").forEach { option ->
                val selected = option == aspect
                Box(
                    Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) Tokens.Ink.copy(alpha = 0.14f) else Tokens.Raise)
                        .border(
                            1.dp,
                            if (selected) Tokens.Ink.copy(alpha = 0.2f) else Tokens.Ink.copy(alpha = 0.06f),
                            RoundedCornerShape(14.dp),
                        )
                        .clickable { onAspect(option) }
                        .testTag("aspect-$option"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option,
                        style = Tokens.Type.Caption.copy(fontSize = 9.5.sp),
                        color = if (selected) Tokens.Ink else Tokens.InkSecondary,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("拉直", style = Tokens.Type.RowLabel, color = Tokens.InkSecondary, modifier = Modifier.weight(1f))
            Text(
                text = String.format("%.1f°", rotation),
                style = Tokens.Type.Value,
                color = Tokens.Ink,
            )
        }
        Spacer(Modifier.height(6.dp))
        TrackSlider(
            value = rotation,
            onValueChange = onStraighten,
            valueRange = -45f..45f,
            centerTick = true,
            modifier = Modifier.testTag("crop-straighten"),
        )

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            TransformPill("左转", R.drawable.ic_rotate_left, Modifier.weight(1f)) { onRotate(false) }
            TransformPill("右转", R.drawable.ic_rotate_right, Modifier.weight(1f)) { onRotate(true) }
            TransformPill("水平", R.drawable.ic_flip_horizontal, Modifier.weight(1f)) { onFlip(true) }
            TransformPill("垂直", R.drawable.ic_flip_vertical, Modifier.weight(1f)) { onFlip(false) }
        }

        Spacer(Modifier.height(14.dp))
        Text(outputLabel, style = Tokens.Type.Caption.copy(fontFamily = Tokens.Mono), color = Tokens.InkSecondary)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "片边开启时比例由片边锁定，请在片边中调整。",
            style = Tokens.Type.Caption.copy(fontSize = 8.5.sp),
            color = Tokens.InkTertiary.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun TransformPill(label: String, icon: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Tokens.Raise)
            .border(1.dp, Tokens.Ink.copy(alpha = 0.12f), RoundedCornerShape(15.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = Tokens.Ink, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Tokens.Type.Caption.copy(fontSize = 9.5.sp), color = Tokens.Ink, maxLines = 1)
    }
}

/** The width/height ratio the aspect pill asks for, or the frame's own. */
private fun cropRatio(aspect: String, native: Float): Float = when (aspect) {
    "1:1" -> 1f
    "3:2" -> 3f / 2f
    "4:3" -> 4f / 3f
    "16:9" -> 16f / 9f
    else -> native
}
