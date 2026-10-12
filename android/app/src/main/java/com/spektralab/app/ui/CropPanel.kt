package com.spektralab.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.spektralab.app.EditorViewModel
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Crop & rotate (plan S5 item 4).
 *
 * The eight fields are the shoot layer's (`engine/src/core/params.cpp`), which
 * is what shapes the interaction: changing one does not repaint the current
 * frame, it re-develops the negative. So this page keeps its own *pending*
 * geometry, previews it in Compose, and hands the whole block to `setParams`
 * in one call behind an explicit **Re-develop** button — the re-develop is the
 * button, not a background surprise.
 *
 * The preview follows the engine's own order (`pipeline.cpp`'s
 * `node_geometry`): the crop is normalized on the source frame, the quarter
 * turns swap the axes of the *output* size, then the rotation and the flips
 * land. The map is drawn in frame space (that is where the finger is), and the
 * result is drawn at the size the engine will produce.
 */
@Composable
fun CropPanel(
    viewModel: EditorViewModel,
    state: EditorViewModel.UiState,
    schema: SchemaView,
    modifier: Modifier = Modifier,
) {
    val applied = remember(state.params) { Geometry.from(state.params) }
    var pending by remember { mutableStateOf(applied) }
    LaunchedEffect(applied) { pending = applied }
    val rendered = state.bitmap
    // The map is the engine's *source* frame: the crop is normalized there,
    // and the rendered frame already has the applied crop in it.
    val context = LocalContext.current
    val bitmap = SourceFrames.map(context, state.source?.uri, rendered)
    val dirty = pending != applied

    Column(modifier.verticalScroll(rememberScrollState())) {
        if (bitmap == null) {
            Text(
                text = "Open a photograph to crop it.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
            )
            return@Column
        }

        Text(
            text = if (dirty) {
                "Shoot-layer change pending: the negative is re-developed when you press Re-develop."
            } else {
                "The map is the decoded frame; the result is the crop, turns, rotation and flips the engine will apply on the next render."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (dirty) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp).testTag("crop-banner"),
        )

        CropMap(
            bitmap = bitmap,
            geometry = pending,
            enabled = !state.busy,
            onChange = { pending = it },
            modifier = Modifier.fillMaxWidth().height(240.dp).padding(8.dp).testTag("crop-map"),
        )

        Text("result", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
        CropResult(
            bitmap = bitmap,
            geometry = pending,
            modifier = Modifier.fillMaxWidth().height(180.dp).padding(8.dp).testTag("crop-result"),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { viewModel.setParams(pending.toDelta()) },
                enabled = dirty && !state.busy,
                modifier = Modifier.testTag("crop-redevelop"),
            ) { Text("Re-develop") }
            OutlinedButton(onClick = { pending = Geometry.full() }, enabled = dirty) { Text("Reset crop") }
            OutlinedButton(onClick = { pending = applied }, enabled = dirty) { Text("Discard") }
        }

        for (field in ParamsCatalog.fieldsOf(ParamGroup.CropRotate)) {
            when (field.kind) {
                ParamKind.Slider -> {
                    val range = schema.range(field.wire) ?: continue
                    val value = pending.valueOf(field.wire)
                    PendingSlider(
                        label = field.label,
                        range = range,
                        value = value,
                        integer = schema.isInteger(field.wire),
                        enabled = !state.busy,
                        testTag = "crop-field-${field.wire}",
                        onChange = { pending = pending.with(field.wire, it) },
                    )
                }
                ParamKind.Toggle -> Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(field.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = pending.booleanOf(field.wire),
                        enabled = !state.busy,
                        onCheckedChange = { pending = pending.with(field.wire, if (it) 1.0 else 0.0) },
                        modifier = Modifier.testTag("crop-field-${field.wire}"),
                    )
                }
                ParamKind.Choice -> Unit
            }
        }
    }
}

/**
 * The frame with the crop rectangle on it, draggable by its corners and its
 * middle. Everything is in normalized frame coordinates — the same space the
 * wire fields use — so a drag is exactly what `set_params` will be told.
 */
@Composable
private fun CropMap(
    bitmap: Bitmap,
    geometry: Geometry,
    enabled: Boolean,
    onChange: (Geometry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = bitmap.asImageBitmap()
    var size by remember { mutableStateOf(Size.Zero) }
    var mode by remember { mutableStateOf(DragMode.Move) }
    // The gesture is keyed on the frame, not on the geometry it produces: a key
    // that changed per drag frame would restart the detector mid-drag.
    val current = rememberUpdatedState(geometry)
    Canvas(
        modifier
            .onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(bitmap, enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { start -> mode = pickMode(start, current.value, frameRect(bitmap, size)) },
                    onDrag = { change, drag ->
                        change.consume()
                        val frame = frameRect(bitmap, size)
                        if (frame.width <= 0f || frame.height <= 0f) return@detectDragGestures
                        onChange(current.value.dragged(mode, drag.x / frame.width, drag.y / frame.height))
                    },
                )
            },
    ) {
        val frame = frameRect(bitmap, size)
        if (frame.width <= 0f) return@Canvas
        drawImage(
            image = image,
            dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
            dstSize = IntSize(frame.width.roundToInt(), frame.height.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
        val crop = geometry.rectIn(frame)
        shadeOutside(frame, crop)
        drawRect(color = Color.White, topLeft = crop.topLeft, size = crop.size, style = Stroke(width = 2f))
        for (corner in CORNERS) {
            val point = corner.pointOf(crop)
            drawRect(
                color = Color.White,
                topLeft = Offset(point.x - HANDLE / 2f, point.y - HANDLE / 2f),
                size = Size(HANDLE, HANDLE),
            )
        }
    }
}

/** What the engine will produce: the crop, then the turns, then the rotation. */
@Composable
private fun CropResult(bitmap: Bitmap, geometry: Geometry, modifier: Modifier = Modifier) {
    val image = bitmap.asImageBitmap()
    val turns = ((geometry.quarterTurns % 4) + 4) % 4
    val sourceWidth = (geometry.cropW * bitmap.width).roundToInt().coerceAtLeast(1)
    val sourceHeight = (geometry.cropH * bitmap.height).roundToInt().coerceAtLeast(1)
    val outputWidth = if (turns % 2 == 0) sourceWidth else sourceHeight
    val outputHeight = if (turns % 2 == 0) sourceHeight else sourceWidth
    Box(modifier.background(Color(0xFF0B0B0B)), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer(
                    rotationZ = turns * 90f + geometry.rotationDeg,
                    scaleX = if (geometry.flipH) -1f else 1f,
                    scaleY = if (geometry.flipV) -1f else 1f,
                    transformOrigin = TransformOrigin.Center,
                ),
        ) {
            val scale = min(size.width / outputWidth, size.height / outputHeight)
            val width = outputWidth * scale
            val height = outputHeight * scale
            drawImage(
                image = image,
                srcOffset = IntOffset(
                    (geometry.cropX * bitmap.width).roundToInt(),
                    (geometry.cropY * bitmap.height).roundToInt(),
                ),
                srcSize = IntSize(sourceWidth, sourceHeight),
                dstOffset = IntOffset(
                    ((size.width - width) / 2f).roundToInt(),
                    ((size.height - height) / 2f).roundToInt(),
                ),
                dstSize = IntSize(width.roundToInt(), height.roundToInt()),
                filterQuality = FilterQuality.Low,
            )
        }
    }
}

/** The pending geometry, in the units the wire fields use. */
internal data class Geometry(
    val cropX: Float,
    val cropY: Float,
    val cropW: Float,
    val cropH: Float,
    val rotationDeg: Float,
    val quarterTurns: Int,
    val flipH: Boolean,
    val flipV: Boolean,
) {
    fun rectIn(frame: Rect): Rect = Rect(
        frame.left + cropX * frame.width,
        frame.top + cropY * frame.height,
        frame.left + (cropX + cropW) * frame.width,
        frame.top + (cropY + cropH) * frame.height,
    )

    fun valueOf(wire: String): Float = when (wire) {
        Wire.CropX -> cropX
        Wire.CropY -> cropY
        Wire.CropW -> cropW
        Wire.CropH -> cropH
        Wire.RotationDeg -> rotationDeg
        Wire.QuarterTurns -> quarterTurns.toFloat()
        else -> 0f
    }

    fun booleanOf(wire: String): Boolean = if (wire == Wire.FlipH) flipH else flipV

    fun with(wire: String, value: Double): Geometry = when (wire) {
        Wire.CropX -> copy(cropX = value.toFloat()).normalized()
        Wire.CropY -> copy(cropY = value.toFloat()).normalized()
        Wire.CropW -> copy(cropW = value.toFloat()).normalized()
        Wire.CropH -> copy(cropH = value.toFloat()).normalized()
        Wire.RotationDeg -> copy(rotationDeg = value.toFloat())
        Wire.QuarterTurns -> copy(quarterTurns = value.roundToInt())
        Wire.FlipH -> copy(flipH = value != 0.0)
        Wire.FlipV -> copy(flipV = value != 0.0)
        else -> this
    }

    /** The whole geometry block, so a re-develop is one engine call and one undo step. */
    fun toDelta(): JSONObject = JSONObject().apply {
        put(Wire.CropX, cropX.toDouble())
        put(Wire.CropY, cropY.toDouble())
        put(Wire.CropW, cropW.toDouble())
        put(Wire.CropH, cropH.toDouble())
        put(Wire.RotationDeg, rotationDeg.toDouble())
        put(Wire.QuarterTurns, quarterTurns)
        put(Wire.FlipH, flipH)
        put(Wire.FlipV, flipV)
    }

    /** A drag on the map, in normalized frame units. */
    fun dragged(mode: DragMode, dx: Float, dy: Float): Geometry {
        val moved = when (mode) {
            DragMode.Move -> copy(cropX = cropX + dx, cropY = cropY + dy)
            DragMode.TopLeft -> copy(
                cropX = cropX + dx, cropY = cropY + dy,
                cropW = cropW - dx, cropH = cropH - dy,
            )
            DragMode.TopRight -> copy(cropY = cropY + dy, cropW = cropW + dx, cropH = cropH - dy)
            DragMode.BottomLeft -> copy(cropX = cropX + dx, cropW = cropW - dx, cropH = cropH + dy)
            DragMode.BottomRight -> copy(cropW = cropW + dx, cropH = cropH + dy)
        }
        return moved.normalized()
    }

    /** Keeps the rectangle inside the frame and above the minimum size. */
    private fun normalized(): Geometry {
        val width = cropW.coerceIn(MIN_CROP, 1f)
        val height = cropH.coerceIn(MIN_CROP, 1f)
        return copy(
            cropW = width,
            cropH = height,
            cropX = cropX.coerceIn(0f, 1f - width),
            cropY = cropY.coerceIn(0f, 1f - height),
        )
    }

    companion object {
        /** Small enough for a corner drag, large enough not to collapse to a line. */
        const val MIN_CROP = 0.02f

        fun full() = Geometry(0f, 0f, 1f, 1f, 0f, 0, false, false)

        /** From the session's params, or the schema's documented defaults. */
        fun from(params: JSONObject?): Geometry = Geometry(
            cropX = params?.optDouble(Wire.CropX, 0.0)?.toFloat() ?: 0f,
            cropY = params?.optDouble(Wire.CropY, 0.0)?.toFloat() ?: 0f,
            cropW = params?.optDouble(Wire.CropW, 1.0)?.toFloat() ?: 1f,
            cropH = params?.optDouble(Wire.CropH, 1.0)?.toFloat() ?: 1f,
            rotationDeg = params?.optDouble(Wire.RotationDeg, 0.0)?.toFloat() ?: 0f,
            quarterTurns = params?.optInt(Wire.QuarterTurns, 0) ?: 0,
            flipH = params?.optBoolean(Wire.FlipH, false) ?: false,
            flipV = params?.optBoolean(Wire.FlipV, false) ?: false,
        )
    }
}

internal enum class DragMode { Move, TopLeft, TopRight, BottomLeft, BottomRight }

private val CORNERS = listOf(DragMode.TopLeft, DragMode.TopRight, DragMode.BottomLeft, DragMode.BottomRight)

/** Handle size and grab radius in pixels, at the map's scale. */
private const val HANDLE = 18f
private const val GRAB_RADIUS = 56f

private fun DragMode.pointOf(crop: Rect): Offset = when (this) {
    DragMode.TopLeft -> crop.topLeft
    DragMode.TopRight -> Offset(crop.right, crop.top)
    DragMode.BottomLeft -> Offset(crop.left, crop.bottom)
    DragMode.BottomRight -> crop.bottomRight
    DragMode.Move -> crop.center
}

/** The fitted frame rectangle inside the map's canvas. */
private fun frameRect(bitmap: Bitmap, size: Size): Rect {
    if (size.width <= 0f || size.height <= 0f || bitmap.width == 0 || bitmap.height == 0) return Rect.Zero
    val scale = min(size.width / bitmap.width, size.height / bitmap.height)
    val width = bitmap.width * scale
    val height = bitmap.height * scale
    val left = (size.width - width) / 2f
    val top = (size.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

private fun pickMode(start: Offset, geometry: Geometry, frame: Rect): DragMode {
    if (frame.width <= 0f) return DragMode.Move
    val crop = geometry.rectIn(frame)
    val nearest = CORNERS.minByOrNull { (it.pointOf(crop) - start).getDistance() }
    if (nearest != null && (nearest.pointOf(crop) - start).getDistance() <= GRAB_RADIUS) return nearest
    return DragMode.Move
}

/** The four strips of the frame the crop does not cover. */
private fun DrawScope.shadeOutside(frame: Rect, crop: Rect) {
    val shade = Color(0x99000000)
    drawRect(shade, topLeft = frame.topLeft, size = Size(frame.width, crop.top - frame.top))
    drawRect(shade, topLeft = Offset(frame.left, crop.bottom), size = Size(frame.width, frame.bottom - crop.bottom))
    drawRect(shade, topLeft = Offset(frame.left, crop.top), size = Size(crop.left - frame.left, crop.height))
    drawRect(shade, topLeft = Offset(crop.right, crop.top), size = Size(frame.right - crop.right, crop.height))
}

@Composable
private fun PendingSlider(
    label: String,
    range: ClosedFloatingPointRange<Float>,
    value: Float,
    integer: Boolean,
    enabled: Boolean,
    testTag: String,
    onChange: (Double) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(
                text = if (integer) "${local.roundToInt()}" else "%.3f".format(local),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        val span = range.endInclusive - range.start
        Slider(
            value = local,
            onValueChange = { local = if (integer) it.roundToInt().toFloat() else it },
            valueRange = range,
            steps = if (integer && span <= 8f) max(0, span.roundToInt() - 1) else 0,
            enabled = enabled,
            onValueChangeFinished = { onChange(if (integer) local.roundToInt().toDouble() else local.toDouble()) },
            modifier = Modifier.fillMaxWidth().testTag(testTag),
        )
    }
}
