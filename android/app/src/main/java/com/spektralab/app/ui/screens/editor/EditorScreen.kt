@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spektralab.app.ui.screens.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spektralab.app.EditorViewModel.UiState
import com.spektralab.app.rolls.stockLabel

/** A1's three tools, and the only three the frame draws. */
enum class EditorTool(val label: String) {
    Film("胶片"),
    Params("参数"),
    Crop("裁剪"),
}

/**
 * A1 — the editor.
 *
 * Material 3 structure: `TopAppBar`, a canvas that takes what is left between the
 * bars, a `LazyRow` filmstrip, a bottom action row. The film frame's furniture —
 * the perforation rows, the edge printing, the amber numbers — is kept because
 * it is the product's own language, but it is drawn inside the canvas band
 * rather than at screen coordinates, so it survives any window size.
 *
 * The engine's own message gets a line under the app bar: a canvas that fails to
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
    Scaffold(
        modifier = Modifier.testTag("editor"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.source?.name ?: "未打开照片",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = filmLine(state),
                            style = MaterialTheme.typography.labelSmall
                                .copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("editor-close")) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    AssistChip(
                        onClick = { onTool(EditorTool.Film) },
                        label = { Text("配光", style = MaterialTheme.typography.labelMedium) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            labelColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.testTag("editor-look"),
                    )
                    IconButton(onClick = { onTool(EditorTool.Params) }) {
                        Icon(Icons.Outlined.MoreHoriz, contentDescription = "更多")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            EditorBar(
                tool = tool,
                onTool = onTool,
                onExport = onExport,
                exportEnabled = state.exportGate?.allowed != false,
            )
        },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 12.dp),
        ) {
            (state.error ?: state.message)?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp).testTag("editor-notice"),
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                FilmFrame(state = state)
                Histogram(state = state, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
            }

            FilmStrip(
                strip = strip,
                selectedIndex = selectedIndex,
                onSelectFrame = onSelectFrame,
                onOpenPhotos = onOpenPhotos,
            )
        }
    }
    sheet()
}

/** The negative on the light box: photo, perforations, edge printing. */
@Composable
private fun FilmFrame(state: UiState) {
    val bitmap = state.bitmap
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF141009))
            .testTag("editor-frame"),
    ) {
        EdgeRow(
            leading = filmOf(state)?.let(::stockLabel) ?: "KODAK PORTRA 400",
            trailing = frameNumberOf(state),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(
                    if (bitmap != null && bitmap.height > 0) {
                        bitmap.width.toFloat() / bitmap.height
                    } else {
                        3f / 2f
                    },
                )
                .background(Color(0xFF0A0908)),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        EdgeRow(
            leading = "16A",
            trailing = "${frameNumberOf(state)} ▮▮▮",
            perforationsBelow = false,
        )
    }
}

@Composable
private fun EdgeRow(leading: String, trailing: String, perforationsBelow: Boolean = true) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF141009))) {
        if (perforationsBelow) PerforationRow()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = leading,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFC9A16A),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFC9A16A),
                maxLines = 1,
            )
        }
        if (!perforationsBelow) PerforationRow()
    }
}

@Composable
private fun PerforationRow() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(9) {
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xE6160D06)),
            )
        }
    }
}

@Composable
private fun Histogram(state: UiState, modifier: Modifier = Modifier) {
    val bins = remember(state.bitmap) { lumaBins(state.bitmap) }
    Surface(
        modifier = modifier.size(width = 92.dp, height = 54.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    ) {
        Box(Modifier.fillMaxSize().padding(8.dp)) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                bins.forEach { value ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height((3 + value * 30).dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
                    )
                }
            }
            Text(
                text = state.tier ?: "live",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilmStrip(
    strip: List<StripEntry>,
    selectedIndex: Int,
    onSelectFrame: (Int) -> Unit,
    onOpenPhotos: () -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().height(76.dp).testTag("film-strip"),
        contentPadding = PaddingValues(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(strip) { index, entry ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .size(width = 62.dp, height = 64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelectFrame(index) }
                    .testTag("strip-$index"),
            ) {
                StripThumbnail(entry, Modifier.fillMaxSize())
                if (selected) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        item {
            Box(
                Modifier
                    .size(width = 62.dp, height = 64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onOpenPhotos)
                    .testTag("strip-add-photos"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "＋",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun EditorBar(
    tool: EditorTool?,
    onTool: (EditorTool?) -> Unit,
    onExport: () -> Unit,
    exportEnabled: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolButton(
                label = EditorTool.Film.label,
                icon = { Icon(Icons.Outlined.Movie, contentDescription = null) },
                active = tool == EditorTool.Film,
                onClick = { onTool(if (tool == EditorTool.Film) null else EditorTool.Film) },
                tag = "tool-film",
            )
            ToolButton(
                label = EditorTool.Params.label,
                icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                active = tool == EditorTool.Params,
                onClick = { onTool(if (tool == EditorTool.Params) null else EditorTool.Params) },
                tag = "tool-params",
            )
            ToolButton(
                label = EditorTool.Crop.label,
                icon = { Icon(Icons.Outlined.Crop, contentDescription = null) },
                active = tool == EditorTool.Crop,
                onClick = { onTool(if (tool == EditorTool.Crop) null else EditorTool.Crop) },
                tag = "tool-crop",
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onExport,
                enabled = exportEnabled,
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.height(44.dp).testTag("tool-export"),
            ) {
                Text("导出", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ToolButton(
    label: String,
    icon: @Composable () -> Unit,
    active: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    val tint = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CompositionLocalProvider(LocalContentColor provides tint) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { icon() }
            }
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = tint)
        }
    }
}

/** 24 bins of luminance, read from the canvas bitmap. */
private fun lumaBins(bitmap: Bitmap?, bins: Int = 24): List<Float> {
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
            val luma = ((pixel shr 16 and 0xFF) * 299 + (pixel shr 8 and 0xFF) * 587 + (pixel and 0xFF) * 114) / 1000
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

private fun filmLine(state: UiState): String {
    val film = filmOf(state)
    val paper = state.params?.optString("paper_stock")?.takeIf { it.isNotEmpty() }
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
