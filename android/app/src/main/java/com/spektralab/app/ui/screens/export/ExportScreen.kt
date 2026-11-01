package com.spektralab.app.ui.screens.export

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.app.EditorViewModel.UiState
import com.spektralab.app.export.ExportColorSpace
import com.spektralab.app.export.ExportFormat
import com.spektralab.app.export.ExportOutcome
import com.spektralab.app.export.ExportSpec
import com.spektralab.app.ui.components.ActionButton
import com.spektralab.app.ui.components.FrameBadge
import com.spektralab.app.ui.theme.Tokens

/**
 * A6 — export, with the soft proof on top.
 *
 * The frame's layout: a 关闭 / 导出 / N 张 bar, the proof at 372×300 with a
 * 文件尺寸 badge, the three-part size line and the gamut line under it, the 配方
 * pills, five rows of what the file will be, and the amber 导出 action.
 *
 * The proof and the gamut line are the screen's argument: what you see is what
 * the file will look like, and the counts under it are the engine's own gamut
 * report rather than an estimate. A colour product that silently clips is the
 * one thing this cannot be, so when nothing has been exported yet the line says
 * so instead of inventing a zero.
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
    var space by remember { mutableStateOf(ExportColorSpace.ProPhoto) }
    // Which *chip* was chosen, not which format it happens to mean: TIFF 16 and
    // DI Cineon are both a 16-bit TIFF in the working space, so matching on the
    // pair lit two chips at once and made the row read as two selections.
    var recipe by remember { mutableStateOf("TIFF 16") }
    var longEdge by remember { mutableStateOf(0) }

    val width = state.lastRender?.width ?: state.source?.width ?: 0
    val height = state.lastRender?.height ?: state.source?.height ?: 0

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Ground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .testTag("export"),
    ) {
        ExportBar(count = exportCount, onClose = onClose)

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Tokens.Margin),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Tokens.Canvas)
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
                FrameBadge(
                    text = "文件尺寸",
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "$width × $height · ${space.wire} · ${format.bits}-bit",
                style = Tokens.Type.Value.copy(fontSize = 10.sp),
                color = Tokens.Ink,
                modifier = Modifier.testTag("export-summary"),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = gamutLine(state),
                style = Tokens.Type.Caption,
                color = Tokens.InkTertiary,
                modifier = Modifier.testTag("export-gamut"),
            )

            Spacer(Modifier.height(16.dp))
            Text("配方", style = Tokens.Type.Caption.copy(fontSize = 9.5.sp), color = Tokens.InkTertiary)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                recipes.forEach { option ->
                    val chosen = option.id == recipe
                    Box(
                        Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (chosen) Tokens.AccentGhost else Tokens.Raise)
                            .border(
                                1.dp,
                                if (chosen) Tokens.AccentBorder else Tokens.Ink.copy(alpha = 0.06f),
                                RoundedCornerShape(14.dp),
                            )
                            .clickable {
                                recipe = option.id
                                format = option.format
                                space = option.space
                            }
                            .testTag("export-format-${option.id}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = option.id,
                            style = Tokens.Type.Caption.copy(fontSize = 9.sp),
                            color = if (chosen) Tokens.Accent else Tokens.InkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SpecRow("格式", formatRowName(format))
                SpecRow("位深", "${format.bits}-bit")
                SpecRow("色域", space.wire, onClick = {
                    space = when (space) {
                        ExportColorSpace.Srgb -> ExportColorSpace.DisplayP3
                        ExportColorSpace.DisplayP3 -> ExportColorSpace.ProPhoto
                        ExportColorSpace.ProPhoto -> ExportColorSpace.Srgb
                    }
                })
                SpecRow(
                    "长边",
                    if (longEdge == 0) "原始尺寸" else "$longEdge px",
                    onClick = {
                        longEdge = when (longEdge) {
                            0 -> 4096
                            4096 -> 2048
                            else -> 0
                        }
                    },
                )
                SpecRow(
                    "位置",
                    directoryLabel ?: "Pictures/Latent",
                    onClick = onPickDirectory,
                )
            }

            Spacer(Modifier.height(16.dp))
            (state.exportOutcome as? ExportOutcome.Refused)?.let {
                Text(
                    text = it.reason,
                    style = Tokens.Type.Caption,
                    color = Tokens.Accent,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        ActionButton(
            label = "导出 $exportCount 张",
            onClick = { onExport(ExportSpec(format = format, colorSpace = space, longEdge = longEdge)) },
            height = 44.dp,
            enabled = state.exportGate?.allowed != false,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Tokens.Margin, end = Tokens.Margin, top = 8.dp, bottom = 24.dp)
                .testTag("export-run"),
        )
    }
}

/** 52 dp: 关闭 on the left, 导出 in the middle, how many on the right. */
@Composable
private fun ExportBar(count: Int, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = Tokens.Margin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "关闭",
            style = Tokens.Type.Body.copy(fontSize = 10.5.sp),
            color = Tokens.InkSecondary,
            modifier = Modifier.clickable(onClick = onClose).testTag("export-close"),
        )
        Text(
            text = "导出",
            style = Tokens.Type.BarTitle,
            color = Tokens.Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count 张",
            style = Tokens.Type.Caption.copy(fontFamily = Tokens.Mono),
            color = Tokens.InkTertiary,
        )
    }
}

/** One row of what the file will be: label left, mono value right. */
@Composable
private fun SpecRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Tokens.Ink.copy(alpha = 0.04f))
            .border(1.dp, Tokens.Ink.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Tokens.Type.Body, color = Tokens.InkSecondary, modifier = Modifier.weight(1f))
        Text(value, style = Tokens.Type.Value, color = Tokens.Ink, maxLines = 1)
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

/** The file's three-letter name for a format, as A6's first row prints it. */
private fun formatRowName(format: ExportFormat): String = when (format) {
    ExportFormat.Tiff16 -> "TIFF"
    ExportFormat.Png16, ExportFormat.Png8 -> "PNG"
    ExportFormat.Jpeg8 -> "JPEG"
    ExportFormat.Heic10, ExportFormat.Heic8 -> "HEIC"
}

/**
 * A6's four recipes, in the frame's order.
 *
 * A recipe is a format *and* a colour space: "TIFF 16" means a 16-bit TIFF in
 * the working space, "PNG sRGB" means a PNG in sRGB, and picking one sets both.
 * Offering them as four independent chips would let a person ask for a
 * combination the product does not claim to support.
 */
private data class Recipe(val id: String, val format: ExportFormat, val space: ExportColorSpace)

private val recipes = listOf(
    Recipe("TIFF 16", ExportFormat.Tiff16, ExportColorSpace.ProPhoto),
    Recipe("JPEG P3", ExportFormat.Jpeg8, ExportColorSpace.DisplayP3),
    Recipe("PNG sRGB", ExportFormat.Png16, ExportColorSpace.Srgb),
    // DI Cineon writes the same container as TIFF 16 and differs in what the
    // pixels mean; the wire has one field for that (`format`), so the two chips
    // land on the same request until the engine grows a log-encoded writer.
    Recipe("DI Cineon", ExportFormat.Tiff16, ExportColorSpace.ProPhoto),
)
