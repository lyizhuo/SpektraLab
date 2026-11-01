@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spektralab.app.ui.screens.export

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.spektralab.app.EditorViewModel.UiState
import com.spektralab.app.export.ExportColorSpace
import com.spektralab.app.export.ExportFormat
import com.spektralab.app.export.ExportOutcome
import com.spektralab.app.export.ExportSpec

/**
 * A6 — export.
 *
 * The screen's argument is the soft proof: the picture at the top is what the
 * file will look like, the line under it describes the file, and the line under
 * that is what the destination could not hold. Those numbers are the engine's
 * `GamutReport`, not an estimate — a colour product that silently clips is the
 * one thing this cannot be.
 */
@Composable
fun ExportScreen(
    state: UiState,
    exportCount: Int,
    directoryLabel: String?,
    onClose: () -> Unit,
    onPickDirectory: () -> Unit,
    onExport: (ExportSpec) -> Unit,
) {
    var format by remember { mutableStateOf(ExportFormat.Tiff16) }
    var space by remember { mutableStateOf(ExportColorSpace.DisplayP3) }
    var longEdge by remember { mutableStateOf(0) }

    val width = state.lastRender?.width ?: state.source?.width ?: 0
    val height = state.lastRender?.height ?: state.source?.height ?: 0

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("export"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("导出 $exportCount 张", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("export-close")) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "关闭")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { insets ->
        LazyColumn(
            Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            item {
                // A proof, not a canvas: the frame gives it a fixed slot so the
                // numbers below it stay on screen whatever the picture's shape.
                // A fixed slot with the picture centred inside it. A portrait
                // proof and a landscape one both land in the same 260 dp band,
                // so the numbers under it never move and never overlap — the
                // first version let a 3:4 frame size itself and it covered them.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0C0B0A))
                        .testTag("export-proof"),
                    contentAlignment = Alignment.Center,
                ) {
                    state.bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            item {
                Text(
                    text = "$width × $height · ${space.wire} · ${format.bits}-bit",
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(top = 12.dp).testTag("export-summary"),
                )
            }
            item {
                Text(
                    text = gamutLine(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp).testTag("export-gamut"),
                )
            }
            item {
                Text(
                    text = "配方",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    exportFormats.forEach { (label, value) ->
                        FilterChip(
                            selected = format == value,
                            onClick = { format = value },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary
                                    .copy(alpha = 0.2f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.testTag("export-format-$label"),
                        )
                    }
                }
            }
            items(
                listOf(
                    "格式" to format.name,
                    "位深" to "${format.bits}-bit",
                    "色域" to space.wire,
                    "长边" to if (longEdge == 0) "原始尺寸" else "$longEdge px",
                    "位置" to (directoryLabel ?: "Pictures/Latent"),
                ),
            ) { row ->
                ListItem(
                    headlineContent = { Text(row.first, style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = {
                        Text(
                            row.second,
                            style = MaterialTheme.typography.labelMedium
                                .copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = {
                        space = when (space) {
                            ExportColorSpace.Srgb -> ExportColorSpace.DisplayP3
                            ExportColorSpace.DisplayP3 -> ExportColorSpace.ProPhoto
                            ExportColorSpace.ProPhoto -> ExportColorSpace.Srgb
                        }
                    }) { Text("换色域", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = {
                        longEdge = when (longEdge) {
                            0 -> 4096
                            4096 -> 2048
                            else -> 0
                        }
                    }) { Text("换长边", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = onPickDirectory) {
                        Text("选位置", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * What the destination could not hold, from the engine's own gamut report.
 *
 * The counts belong to *a file* — the engine counts them on the buffer it has
 * already converted to the destination space — so before any export there is
 * nothing to report, and the line says so rather than inventing a zero.
 */
private fun gamutLine(state: UiState): String {
    val report = (state.exportOutcome as? ExportOutcome.Success)?.gamut
        ?: return "色域报告尚未生成 · 导出后可见"
    return report.label()
}

internal val exportFormats: List<Pair<String, ExportFormat>> = listOf(
    "TIFF 16" to ExportFormat.Tiff16,
    "JPEG P3" to ExportFormat.Jpeg8,
    "PNG sRGB" to ExportFormat.Png16,
    "HEIC 10" to ExportFormat.Heic10,
)
