package com.spektralab.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.spektralab.app.EditorViewModel
import kotlin.math.min

/**
 * The canvas (plan S5 item 2): pinch zoom anchored at the pointer, drag to
 * pan, double tap for fit / 100%, long press for the original frame, the tier
 * badge and the histogram.
 *
 * The transform is one `graphicsLayer` over an `Image` with
 * `ContentScale.Fit`, which is why the zoom is expressed *relative to fit*:
 * 1.0 is fit, and 100% is `1 / fittedScale` — a number this composable can
 * compute because it knows both the frame's size and its own viewport. Keeping
 * the anchor is then arithmetic, not a library's business:
 * `t1 = (p − c)·(1 − k) + t0·k + pan`, with `k = s1/s0`.
 */
@Composable
fun CanvasView(
    state: EditorViewModel.UiState,
    onTierChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var translation by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var showOriginal by remember { mutableStateOf(false) }

    val bitmap = state.bitmap
    val uri = state.source?.uri

    // The original frame, decoded only while the finger is down (see
    // SourceFrames for why it is the platform's decoder and not the engine).
    val original = SourceFrames.previewWhilePressed(context, uri, showOriginal)

    Box(
        modifier
            .background(Color(0xFF0B0B0B))
            .clipToBounds(),
    ) {
        if (bitmap == null) {
            EmptyCanvas(state = state, onPickImage = onPickImage, modifier = Modifier.align(Alignment.Center))
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewport = it }
                    .pointerInput(bitmap) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            val k = next / scale
                            val centre = Offset(viewport.width / 2f, viewport.height / 2f)
                            val anchored = (centroid - centre) * (1f - k) + translation * k + pan
                            translation = clamp(anchored, next, viewport)
                            scale = next
                        }
                    }
                    .pointerInput(bitmap) {
                        detectTapGestures(
                            onDoubleTap = {
                                val fitted = fittedScale(bitmap, viewport)
                                val hundredPercent = if (fitted > 0f) (1f / fitted).coerceIn(MIN_ZOOM, MAX_ZOOM) else 1f
                                scale = when {
                                    scale > 1.02f -> 1f
                                    hundredPercent > 1.02f -> hundredPercent
                                    else -> 2f
                                }
                                translation = Offset.Zero
                            },
                            onLongPress = { showOriginal = true },
                            onPress = { tryAwaitRelease(); showOriginal = false },
                        )
                    },
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = state.source?.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = translation.x,
                            translationY = translation.y,
                            transformOrigin = TransformOrigin.Center,
                        ),
                )
                original?.let { frame ->
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "original frame",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().testTag("original-frame"),
                    )
                }
            }

            CanvasBadges(
                state = state,
                onTierChange = onTierChange,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )

            Row(
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showOriginal) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                        Text("original", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(6.dp, 2.dp))
                    }
                }
                Surface(color = Color(0xCC000000), shape = MaterialTheme.shapes.small) {
                    Box(Modifier.padding(8.dp, 2.dp)) { HistogramCaption() }
                }
            }

            HistogramOverlay(
                bitmap = bitmap,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(width = 220.dp, height = 64.dp),
            )

            if (state.busy) {
                Row(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = when (state.phase) {
                            EditorViewModel.Phase.Exporting -> state.exportProgress?.let { "exporting: $it" } ?: "exporting…"
                            EditorViewModel.Phase.Opening -> "decoding…"
                            else -> "rendering ${state.tier}…"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Cancel, contentDescription = "cancel the render")
                    }
                }
            }
        }
    }
}

/** The tier badge and the frame's numbers, top-right over the canvas. */
@Composable
private fun CanvasBadges(
    state: EditorViewModel.UiState,
    onTierChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val last = state.lastRender
    val highWater = last?.memory?.optJSONObject("pool")?.optLong("frame_high_water_bytes") ?: 0L
    Column(modifier, horizontalAlignment = Alignment.End) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TIERS.forEach { tier ->
                    val selected = state.tier == tier
                    TextButton(
                        onClick = { if (!selected) onTierChange(tier) },
                        enabled = state.bitmap != null && state.tier != tier,
                        modifier = Modifier.testTag("tier-$tier"),
                    ) {
                        Text(
                            text = tier,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        Surface(color = Color(0xCC000000), shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 4.dp)) {
            Column(Modifier.padding(6.dp)) {
                Text(
                    text = "${last?.width ?: 0}×${last?.height ?: 0}" +
                        (last?.elapsedMs?.let { "  ${"%.0f".format(it)} ms" } ?: "") +
                        (if (last?.reprint == true) "  reprint" else ""),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = "peak ${highWater / (1 shl 20)} MB",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun EmptyCanvas(state: EditorViewModel.UiState, onPickImage: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Filled.PhotoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = when (state.phase) {
                EditorViewModel.Phase.Opening -> "decoding…"
                EditorViewModel.Phase.Booting, EditorViewModel.Phase.Rendering -> "starting the engine…"
                else -> "no photograph open"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        AssistChip(
            onClick = onPickImage,
            label = { Text("Open a photograph") },
            modifier = Modifier.padding(top = 8.dp).testTag("open-button"),
        )
    }
}

private val TIERS = listOf("live", "preview", "full")
private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 8f

/** How far the fitted image is scaled to fill a viewport of this size. */
private fun fittedScale(bitmap: Bitmap, viewport: IntSize): Float {
    if (viewport.width == 0 || viewport.height == 0) return 1f
    return min(viewport.width / bitmap.width.toFloat(), viewport.height / bitmap.height.toFloat())
}

/**
 * A drag may not pull the frame off the canvas: at 1× there is no slack
 * (`translation` is the layer's own offset, so a fit image is already centred),
 * and above 1× the slack is half of what the scaled layer overhangs.
 */
private fun clamp(offset: Offset, scale: Float, viewport: IntSize): Offset {
    if (scale <= 1f) return Offset.Zero
    val slackX = (scale - 1f) * viewport.width / 2f
    val slackY = (scale - 1f) * viewport.height / 2f
    return Offset(offset.x.coerceIn(-slackX, slackX), offset.y.coerceIn(-slackY, slackY))
}
