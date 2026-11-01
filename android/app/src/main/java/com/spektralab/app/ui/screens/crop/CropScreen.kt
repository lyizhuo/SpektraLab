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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
        CropCanvas(bitmap = bitmap, aspect = aspect, modifier = Modifier.weight(1f).fillMaxWidth())
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
 * The canvas: the frame, the box over it, the thirds, and four crosshair corners.
 *
 * The box is inset from the frame by whatever the chosen aspect leaves over, and
 * what is outside it is dimmed — the same "this is what you keep" reading the
 * file draws.
 */
@Composable
private fun CropCanvas(bitmap: Bitmap?, aspect: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(Tokens.Canvas), contentAlignment = Alignment.Center) {
        // The frame keeps the photograph's own shape, like the editor's does: a
        // landscape file gets a landscape frame, so the crop box's insets are
        // the ones the export will use rather than an artefact of the window.
        val photoRatio = if (bitmap != null && bitmap.height > 0) {
            bitmap.width.toFloat() / bitmap.height
        } else {
            3f / 2f
        }
        val frameHeight = minOf(maxHeight - 40.dp, (maxWidth - 40.dp) / photoRatio)
        val frameWidth = frameHeight * photoRatio

        Box(
            Modifier
                .width(frameWidth)
                .height(frameHeight)
                .clip(RoundedCornerShape(2.dp)),
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

            val ratio = cropRatio(aspect, photoRatio)
            val boxWidth = if (ratio > photoRatio) frameWidth else frameHeight * ratio
            val boxHeight = if (ratio > photoRatio) frameWidth / ratio else frameHeight
            val insetX = (frameWidth - boxWidth) / 2f
            val insetY = (frameHeight - boxHeight) / 2f

            // Everything outside the box, dimmed: four bands rather than one
            // rectangle so the frame's own light still reads around the keep.
            Box(Modifier.fillMaxWidth().height(insetY).align(Alignment.TopCenter).background(Tokens.Scrim))
            Box(Modifier.fillMaxWidth().height(insetY).align(Alignment.BottomCenter).background(Tokens.Scrim))
            Box(Modifier.width(insetX).height(boxHeight).align(Alignment.CenterStart).background(Tokens.Scrim))
            Box(Modifier.width(insetX).height(boxHeight).align(Alignment.CenterEnd).background(Tokens.Scrim))

            val boxShape = RoundedCornerShape(1.dp)
            Box(
                Modifier
                    .width(boxWidth)
                    .height(boxHeight)
                    .border(1.4.dp, Tokens.Ink.copy(alpha = 0.95f), boxShape),
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
