package com.spektralab.app.ui.screens.export

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.app.EditorViewModel.UiState
import com.spektralab.app.export.ExportColorSpace
import com.spektralab.app.export.ExportFormat
import com.spektralab.app.export.ExportSpec
import com.spektralab.app.ui.frame.Layer
import com.spektralab.app.ui.frame.ScreenBand
import com.spektralab.app.ui.theme.Tokens

/**
 * A6 — 导出, transcribed from the frame `5:508`.
 *
 * The screen's argument is the soft proof: the picture at the top is what the
 * *file* will look like, the line under it is the file's own description, and
 * the line under that is what the destination could not hold — "超出目标色域
 * 1.8% · 被映射 0.6% · 被夹限 0.1%". Those three numbers come from the engine's
 * `GamutReport`, not from a guess, because a colour app that silently clips is
 * the one thing this product cannot be.
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

    ScreenBand {
        // ---- topbar --------------------------------------------------------
        Layer(x = 20.dp, y = 52.dp) {
            Text(
                text = "关闭",
                style = TextStyle(fontFamily = Tokens.Sans, fontSize = 12.sp, color = Tokens.InkSecondary),
                modifier = Modifier.clickable(onClick = onClose).testTag("export-close"),
            )
        }
        Box(Modifier.offset(y = 51.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Text(
                text = "导出",
                style = TextStyle(
                    fontFamily = Tokens.Sans,
                    fontSize = 15.sp,
                    color = Tokens.Ink,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
        Box(Modifier.offset(y = 54.dp).fillMaxWidth().padding(end = 20.dp), contentAlignment = Alignment.TopEnd) {
            Text(
                text = "$exportCount 张",
                style = TextStyle(fontFamily = Tokens.Sans, fontSize = 10.sp, color = Tokens.InkTertiary),
            )
        }

        // ---- soft proof ----------------------------------------------------
        Layer(x = 20.dp, y = 100.dp, width = 372.dp, height = 300.dp) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0C0B0A)),
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
            Box(
                Modifier
                    .offset(x = 310.dp, y = 12.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0x14FFFFFF))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "文件尺寸 1:1",
                    style = TextStyle(fontFamily = Tokens.Mono, fontSize = 8.5.sp, color = Tokens.InkSecondary),
                )
            }
        }

        // ---- the file's own description ------------------------------------
        val width = state.lastRender?.width ?: state.source?.width ?: 0
        val height = state.lastRender?.height ?: state.source?.height ?: 0
        Text(
            text = "$width × $height · ${space.wire} · ${format.bits}-bit",
            style = TextStyle(fontFamily = Tokens.Mono, fontSize = 11.5.sp, color = Tokens.Ink),
            modifier = Modifier.offset(x = 20.dp, y = 414.dp).testTag("export-summary"),
        )
        Text(
            text = gamutLine(state),
            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 9.5.sp, color = Tokens.InkTertiary),
            modifier = Modifier.offset(x = 20.dp, y = 434.dp).testTag("export-gamut"),
        )

        // ---- recipe --------------------------------------------------------
        Text(
            text = "配方",
            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 10.sp, color = Tokens.InkTertiary),
            modifier = Modifier.offset(x = 20.dp, y = 464.dp),
        )
        Row(Modifier.offset(x = 20.dp, y = 484.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(
                "TIFF 16" to ExportFormat.Tiff16,
                "JPEG P3" to ExportFormat.Jpeg8,
                "PNG sRGB" to ExportFormat.Png16,
                "HEIC 10" to ExportFormat.Heic10,
            ).forEach { (label, value) ->
                val selected = format == value
                Box(
                    Modifier
                        .width(88.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) Color(0x21FFFFFF) else Color(0x0AFFFFFF))
                        .clickable {
                            format = value
                            // The pill's own name says which space it means;
                            // making the user set it twice is how a file ends up
                            // tagged sRGB while holding P3 numbers.
                            space = when (label) {
                                "JPEG P3" -> ExportColorSpace.DisplayP3
                                "PNG sRGB" -> ExportColorSpace.Srgb
                                else -> space
                            }
                        }
                        .testTag("export-format-$label"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = TextStyle(
                            fontFamily = Tokens.Sans,
                            fontSize = 10.5.sp,
                            color = if (selected) Tokens.Ink else Tokens.InkTertiary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }

        // ---- rows ----------------------------------------------------------
        SettingRow("格式", format.name, 534.dp)
        SettingRow("位深", "${format.bits}-bit", 578.dp)
        SettingRow("色域", space.wire, 622.dp, onClick = {
            space = when (space) {
                ExportColorSpace.Srgb -> ExportColorSpace.DisplayP3
                ExportColorSpace.DisplayP3 -> ExportColorSpace.ProPhoto
                ExportColorSpace.ProPhoto -> ExportColorSpace.Srgb
            }
        })
        SettingRow("长边", if (longEdge == 0) "原始尺寸" else "$longEdge px", 666.dp, onClick = {
            longEdge = when (longEdge) {
                0 -> 4096
                4096 -> 2048
                else -> 0
            }
        })
        SettingRow("位置", directoryLabel ?: "Pictures/SpektraLab", 710.dp, onClick = onPickDirectory)

        // ---- actions -------------------------------------------------------
        Layer(x = 0.dp, y = 829.dp, width = 412.dp, height = 86.dp) {
            Box(Modifier.fillMaxSize().background(Color(0x0FFFFFFF)))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Hairline))
            Box(
                Modifier
                    .offset(x = 22.dp, y = 20.dp)
                    .size(width = 368.dp, height = 46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(Tokens.Accent)
                    .clickable {
                        onExport(
                            ExportSpec(
                                format = format,
                                colorSpace = space,
                                longEdge = longEdge,
                            ),
                        )
                    }
                    .testTag("export-primary"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "导出 $exportCount 张",
                    style = TextStyle(
                        fontFamily = Tokens.Sans,
                        fontSize = 13.5.sp,
                        color = Color(0xFF231708),
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String, y: androidx.compose.ui.unit.Dp, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .offset(x = 20.dp, y = y)
            .size(width = 372.dp, height = 40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0x0AFFFFFF))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 11.5.sp, color = Tokens.InkSecondary),
        )
        Text(
            text = value,
            style = TextStyle(fontFamily = Tokens.Mono, fontSize = 11.sp, color = Tokens.Ink),
        )
    }
}

/**
 * What the destination could not hold, from the engine's own gamut report.
 *
 * The counts belong to *a file* — the engine counts them on the buffer it has
 * already converted to the destination space — so before anything has been
 * exported there is nothing to report and the line says so. `GamutReport`
 * already formats itself; duplicating that format here would be a second copy
 * of the same convention, which is AGENTS.md trap 28's shape.
 */
private fun gamutLine(state: UiState): String {
    val report = (state.exportOutcome as? com.spektralab.app.export.ExportOutcome.Success)?.gamut
        ?: return "色域报告尚未生成 · 导出后可见"
    return report.label()
}
