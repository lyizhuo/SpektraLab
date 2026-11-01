package com.spektralab.app.ui.screens.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.app.EditorViewModel.UiState
import com.spektralab.app.ui.frame.Layer
import com.spektralab.app.ui.frame.ScreenBand
import com.spektralab.app.ui.theme.Tokens

/** Which panel the toolbar has open. A1 is what they all overlay. */
enum class EditorTool(val label: String) { Film("胶片"), Params("参数"), Crop("裁剪") }

/**
 * A1 — 编辑器, transcribed from the frame `5:8`.
 *
 * The screen is two horizontal bands and a film edge:
 *
 *  * `topbar` at 40, 52 tall — back, the file name, the stock pair, the 配光
 *    pill, the overflow;
 *  * `canvas` 92…697, `#0c0b0a`, holding the darkroom glow behind a **film
 *    frame**: a 364×528 strip with sprocket holes down both edges, the stock
 *    printed at the top edge and the frame number at the bottom. That frame is
 *    the product's whole visual argument — the photograph is presented as a
 *    piece of film, not as a picture in an app;
 *  * `filmstrip` 757…833, five 62×44 thumbnails;
 *  * `toolbar` 833…905 with the accent 导出 button and the three tools.
 *
 * The panels A2/A3/A4/A5/A6 are overlays on this screen, which is why they are
 * [EditorSheet]s rather than activities: the frame keeps the canvas behind
 * them, and switching tools must not lose the frame.
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
    ScreenBand {
        // ---- topbar --------------------------------------------------------
        Layer(x = 0.dp, y = 40.dp, width = 412.dp, height = 52.dp) {
            Box(
                Modifier
                    .offset(x = 12.dp, y = 12.dp)
                    .size(24.dp)
                    .clickable(onClick = onClose)
                    .testTag("editor-back"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    null,
                    tint = Tokens.Ink,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.offset(x = 52.dp, y = 10.dp)) {
                Text(
                    text = state.source?.name ?: "未打开照片",
                    style = TextStyle(
                        fontFamily = Tokens.Sans,
                        fontSize = 13.sp,
                        color = Tokens.Ink,
                    ),
                    modifier = Modifier.testTag("editor-name"),
                )
                Text(
                    text = filmLine(state),
                    style = TextStyle(
                        fontFamily = Tokens.Sans,
                        fontSize = 8.sp,
                        color = Tokens.InkTertiary,
                    ),
                )
            }
            // pill-配光
            Box(
                Modifier
                    .offset(x = 236.dp, y = 13.dp)
                    .size(width = 58.dp, height = 26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color(0x24E1A95F))
                    .clickable { onTool(EditorTool.Film) }
                    .testTag("editor-lightbalance"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "配光",
                    style = TextStyle(
                        fontFamily = Tokens.Sans,
                        fontSize = 10.5.sp,
                        color = Tokens.Accent,
                    ),
                )
            }
            Box(
                Modifier
                    .offset(x = 309.dp, y = 11.dp)
                    .size(18.dp)
                    .clickable { onTool(EditorTool.Params) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.MoreHoriz, null, tint = Tokens.InkSecondary, modifier = Modifier.size(18.dp))
            }
        }

        // ---- canvas --------------------------------------------------------
        Layer(x = 0.dp, y = 92.dp, width = 412.dp, height = 605.dp) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0C0B0A)))
            Safelight()
            FilmFrame(
                bitmap = state.bitmap,
                stock = stockOf(state),
                frameNumber = state.source?.let { frameNumber(it.name) } ?: "—",
                modifier = Modifier.offset(x = 24.dp, y = 40.dp),
            )
            Histogram(
                bitmap = state.bitmap,
                modifier = Modifier.offset(x = 316.dp, y = 18.dp),
            )
            TierBadge(
                state = state,
                modifier = Modifier.offset(x = 300.dp, y = 566.dp),
            )
        }

        // ---- filmstrip -----------------------------------------------------
        Layer(x = 0.dp, y = 757.dp, width = 412.dp, height = 76.dp) {
            Box(Modifier.fillMaxSize().background(Color(0xFF151210)))
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 5.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Tokens.Ink.copy(alpha = 0.18f)),
            )
            Row(
                Modifier.offset(x = 31.dp, y = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                strip.take(5).forEachIndexed { index, entry ->
                    val active = index == selectedIndex
                    Box(
                        Modifier
                            .size(width = 62.dp, height = 44.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF241D18))
                            .border(
                                width = if (active) 1.5.dp else 1.dp,
                                color = if (active) Tokens.Accent else Tokens.Hairline,
                                shape = RoundedCornerShape(3.dp),
                            )
                            .clickable { onSelectFrame(index) }
                            .testTag("strip-$index"),
                    ) {
                        StripThumbnail(entry, Modifier.fillMaxSize())
                    }
                }
                if (strip.isEmpty()) {
                    Box(
                        Modifier
                            .size(width = 62.dp, height = 44.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF241D18))
                            .border(1.dp, Tokens.Hairline, RoundedCornerShape(3.dp))
                            .clickable(onClick = onOpenPhotos)
                            .testTag("strip-add"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "+",
                            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 16.sp, color = Tokens.InkTertiary),
                        )
                    }
                }
            }
        }

        // ---- toolbar -------------------------------------------------------
        Layer(x = 0.dp, y = 833.dp, width = 412.dp, height = 72.dp) {
            Box(Modifier.fillMaxSize().background(Tokens.Bar))
            // 导出
            Box(
                Modifier
                    .offset(x = 290.dp, y = 17.dp)
                    .size(width = 104.dp, height = 38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(Tokens.Accent)
                    .clickable(onClick = onExport)
                    .testTag("editor-export"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "导出",
                    style = TextStyle(
                        fontFamily = Tokens.Sans,
                        fontSize = 12.5.sp,
                        color = Color(0xFF231708),
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
            Row(
                Modifier.offset(x = 24.dp, y = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                ToolButton("胶片", Icons.Outlined.Tune, tool == EditorTool.Film) {
                    onTool(if (tool == EditorTool.Film) null else EditorTool.Film)
                }
                ToolButton("参数", Icons.Outlined.Tune, tool == EditorTool.Params) {
                    onTool(if (tool == EditorTool.Params) null else EditorTool.Params)
                }
                ToolButton("裁剪", Icons.Outlined.Crop, tool == EditorTool.Crop) {
                    onTool(if (tool == EditorTool.Crop) null else EditorTool.Crop)
                }
            }
        }

        // Home indicator.
        Box(
            Modifier
                .offset(x = 146.dp, y = 892.dp)
                .size(width = 120.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Tokens.Ink.copy(alpha = 0.35f)),
        )

        sheet()
    }
}

@Composable
private fun ToolButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(52.dp)
            .clickable(onClick = onClick)
            .testTag("tool-$label"),
        horizontalAlignment = Alignment.Start,
    ) {
        Icon(
            icon,
            null,
            tint = if (active) Tokens.Accent else Tokens.InkSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = TextStyle(
                fontFamily = Tokens.Sans,
                fontSize = 9.5.sp,
                color = if (active) Tokens.Accent else Tokens.InkTertiary,
            ),
        )
    }
}

/** The darkroom glow behind the frame; a radial wash, not an image. */
@Composable
private fun Safelight() {
    Canvas(
        Modifier
            .offset(x = 20.dp, y = 40.dp)
            .size(width = 372.dp, height = 420.dp),
    ) {
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(Color(0x33E1A95F), Color(0x00000000)),
                center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f),
                radius = size.width * 0.7f,
            ),
        )
    }
}

/**
 * The frame carries the photograph and its film edge.
 *
 * Sprocket holes on both long edges, the stock printed top-left and the frame
 * number top-right and bottom-left/bottom-right — the layout of a 135 frame.
 */
@Composable
private fun FilmFrame(
    bitmap: Bitmap?,
    stock: String,
    frameNumber: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(width = 364.dp, height = 528.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF141009)),
    ) {
        Text(
            text = stock,
            style = TextStyle(fontFamily = Tokens.Mono, fontSize = 6.5.sp, color = Color(0xFFC9A16A)),
            modifier = Modifier.offset(x = 8.dp, y = 6.dp),
        )
        Box(
            Modifier.offset(y = 6.dp).fillMaxWidth().padding(end = 8.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            Text(
                text = frameNumber,
                style = TextStyle(fontFamily = Tokens.Mono, fontSize = 6.5.sp, color = Color(0xFFC9A16A)),
            )
        }
        // Sprockets: nine across the top and nine across the bottom.
        repeat(9) { index ->
            val x = 8.dp + (39.53 * index).dp
            Box(
                Modifier
                    .offset(x = x, y = 11.dp)
                    .size(width = 13.dp, height = 8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xE6160D06)),
            )
            Box(
                Modifier
                    .offset(x = x, y = 509.dp)
                    .size(width = 13.dp, height = 8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xE6160D06)),
            )
        }
        // The photograph itself.
        Box(
            Modifier
                .offset(x = 8.dp, y = 22.dp)
                .size(width = 348.dp, height = 484.dp)
                .background(Color(0xFF0A0908)),
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().testTag("canvas-photo"),
                )
            }
        }
        Text(
            text = "16A",
            style = TextStyle(fontFamily = Tokens.Mono, fontSize = 6.5.sp, color = Color(0xFFC9A16A)),
            modifier = Modifier.offset(x = 8.dp, y = 515.dp),
        )
        Box(
            Modifier.offset(y = 515.dp).fillMaxWidth().padding(end = 8.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            Text(
                text = frameNumber,
                style = TextStyle(fontFamily = Tokens.Mono, fontSize = 6.5.sp, color = Color(0xFFC9A16A)),
            )
        }
    }
}

/** 78×44 of luminance histogram, drawn from the rendered frame. */
@Composable
private fun Histogram(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    val bins = remember(bitmap) { lumaBins(bitmap, 26) }
    Box(
        modifier
            .size(width = 78.dp, height = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x0FFFFFFF))
            .testTag("histogram"),
    ) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            if (bins.isEmpty()) return@Canvas
            val peak = bins.max().coerceAtLeast(1)
            val step = size.width / bins.size
            bins.forEachIndexed { index, value ->
                val h = size.height * (value.toFloat() / peak)
                drawRect(
                    color = Color(0xBFE1A95F),
                    topLeft = androidx.compose.ui.geometry.Offset(index * step, size.height - h),
                    size = androidx.compose.ui.geometry.Size(step - 0.6f, h),
                )
            }
        }
    }
}

@Composable
private fun TierBadge(state: UiState, modifier: Modifier = Modifier) {
    val info = state.lastRender
    val label = if (info != null) "${state.tier} · ${info.width}×${info.height}" else state.tier
    Box(
        modifier
            .height(18.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0x14FFFFFF))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(fontFamily = Tokens.Mono, fontSize = 8.5.sp, color = Tokens.InkSecondary),
            modifier = Modifier.testTag("tier-badge"),
        )
    }
}

/** "PORTRA 400 · SUPRA ENDURA", from the session's params. */
internal fun filmLine(state: UiState): String {
    val film = state.params?.optString("film_stock")?.takeIf { it.isNotEmpty() }
    val paper = state.params?.optString("paper_stock")?.takeIf { it.isNotEmpty() }
    val parts = listOfNotNull(film, paper).map { stockLabel(it) }
    return if (parts.isEmpty()) "—" else parts.joinToString(" · ")
}

internal fun stockOf(state: UiState): String {
    val film = state.params?.optString("film_stock")?.takeIf { it.isNotEmpty() } ?: return "KODAK PORTRA 400"
    return "KODAK " + stockLabel(film)
}

internal fun frameNumber(name: String): String {
    val digits = name.filter { it.isDigit() }.takeLast(2)
    return digits.ifEmpty { "—" }
}

private fun stockLabel(id: String): String =
    com.spektralab.app.rolls.stockLabel(id)

private fun lumaBins(bitmap: Bitmap?, count: Int): IntArray {
    if (bitmap == null) return IntArray(0)
    val bins = IntArray(count)
    val stepX = (bitmap.width / 64).coerceAtLeast(1)
    val stepY = (bitmap.height / 64).coerceAtLeast(1)
    var x = 0
    while (x < bitmap.width) {
        var y = 0
        while (y < bitmap.height) {
            val pixel = bitmap.getPixel(x, y)
            val luma = (
                (0.2126 * ((pixel shr 16) and 0xFF) +
                    0.7152 * ((pixel shr 8) and 0xFF) +
                    0.0722 * (pixel and 0xFF)) / 255.0 * (count - 1)
                ).toInt().coerceIn(0, count - 1)
            bins[luma]++
            y += stepY
        }
        x += stepX
    }
    return bins
}
