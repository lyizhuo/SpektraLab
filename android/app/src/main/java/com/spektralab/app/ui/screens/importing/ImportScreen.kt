package com.spektralab.app.ui.screens.importing

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
import com.spektralab.app.ui.frame.Layer
import com.spektralab.app.ui.frame.ScreenBand
import com.spektralab.app.ui.theme.Tokens

/** One file on its way in. */
data class ImportRow(val name: String, val state: State) {
    enum class State { Done, Working, Waiting }
}

/**
 * C2 — 导入处理.
 *
 * **Where the numbers come from.** Unlike the other screens, `get_design_context`
 * returns nothing for this node — Figma reports it as having no context to
 * give — so the offsets below are read off `get_screenshot`'s render at
 * 527×1024 rather than out of the frame's own JSX. They are to the nearest
 * couple of dp and should be re-derived from the file the moment the node
 * answers; the colours, radii and type sizes are the shared tokens and are
 * exact.
 *
 * The screen's job is to be honest about a long, interruptible operation: which
 * files are done, which is being decoded, and that cancelling is allowed. The
 * frame's own line — 导入完成后将自动进入编辑器 — is the promise that makes the
 * wait tolerable, so it is kept verbatim.
 */
@Composable
fun ImportScreen(
    rows: List<ImportRow>,
    preview: android.graphics.Bitmap?,
    onCancel: () -> Unit,
) {
    val done = rows.count { it.state == ImportRow.State.Done }
    val current = rows.firstOrNull { it.state == ImportRow.State.Working }?.name
        ?: rows.firstOrNull { it.state == ImportRow.State.Waiting }?.name
        ?: rows.lastOrNull()?.name
        ?: ""

    ScreenBand {
        Text(
            text = "导入",
            style = TextStyle(
                fontFamily = Tokens.Sans,
                fontSize = 15.sp,
                color = Tokens.Ink,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.offset(x = 20.dp, y = 54.dp),
        )
        Box(Modifier.offset(y = 55.dp).fillMaxWidth().padding(end = 20.dp), contentAlignment = Alignment.TopEnd) {
            Text(
                text = "取消",
                style = TextStyle(fontFamily = Tokens.Sans, fontSize = 12.sp, color = Tokens.Accent),
                modifier = Modifier.clickable(onClick = onCancel).testTag("import-cancel"),
            )
        }

        // The frame in its perforated border, as C2 draws the first file.
        Layer(x = 108.dp, y = 104.dp, width = 196.dp, height = 296.dp) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF141009))
                    .border(1.dp, Color(0x40C9A16A), RoundedCornerShape(10.dp)),
            ) {
                Box(
                    Modifier
                        .offset(x = 8.dp, y = 18.dp)
                        .size(width = 180.dp, height = 260.dp)
                        .background(Color(0xFF0A0908)),
                ) {
                    preview?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                repeat(7) { index ->
                    val x = 10.dp + (26 * index).dp
                    Box(
                        Modifier
                            .offset(x = x, y = 6.dp)
                            .size(width = 14.dp, height = 6.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0x66C9A16A)),
                    )
                    Box(
                        Modifier
                            .offset(x = x, y = 284.dp)
                            .size(width = 14.dp, height = 6.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0x66C9A16A)),
                    )
                }
            }
        }

        Box(Modifier.offset(y = 424.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Text(
                text = "正在导入 ${rows.size} 张",
                style = TextStyle(
                    fontFamily = Tokens.Sans,
                    fontSize = 20.sp,
                    color = Tokens.Ink,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.testTag("import-title"),
            )
        }
        Box(Modifier.offset(y = 452.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Text(
                text = "正在解码 RAW 并生成首张预览",
                style = TextStyle(fontFamily = Tokens.Sans, fontSize = 10.sp, color = Tokens.InkTertiary),
            )
        }

        // ---- progress ------------------------------------------------------
        Layer(x = 20.dp, y = 486.dp, width = 372.dp) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Tokens.Track),
            ) {
                val fraction = if (rows.isEmpty()) 0f else done.toFloat() / rows.size
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(Tokens.AccentFill),
                )
            }
        }
        Text(
            text = "$done / ${rows.size}",
            style = TextStyle(fontFamily = Tokens.Mono, fontSize = 9.5.sp, color = Tokens.InkSecondary),
            modifier = Modifier.offset(x = 20.dp, y = 496.dp),
        )
        Box(Modifier.offset(y = 496.dp).fillMaxWidth().padding(end = 20.dp), contentAlignment = Alignment.TopEnd) {
            Text(
                text = current,
                style = TextStyle(fontFamily = Tokens.Mono, fontSize = 9.5.sp, color = Tokens.InkTertiary),
            )
        }

        // ---- the files -----------------------------------------------------
        Column(
            Modifier.offset(x = 20.dp, y = 522.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            rows.forEach { row ->
                Row(
                    Modifier
                        .size(width = 372.dp, height = 40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (row.state == ImportRow.State.Working) Color(0x14FFFFFF) else Color(0x0AFFFFFF))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when (row.state) {
                                        ImportRow.State.Done -> Tokens.Accent
                                        ImportRow.State.Working -> Color(0x00FFFFFF)
                                        ImportRow.State.Waiting -> Color(0x14FFFFFF)
                                    },
                                )
                                .border(
                                    width = if (row.state == ImportRow.State.Working) 1.5.dp else 0.dp,
                                    color = if (row.state == ImportRow.State.Working) Tokens.Accent else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (row.state == ImportRow.State.Done) {
                                Text(
                                    text = "✓",
                                    style = TextStyle(fontFamily = Tokens.Sans, fontSize = 9.sp, color = Tokens.Ground),
                                )
                            }
                        }
                        Text(
                            text = row.name,
                            style = TextStyle(fontFamily = Tokens.Mono, fontSize = 10.5.sp, color = Tokens.Ink),
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    Text(
                        text = when (row.state) {
                            ImportRow.State.Done -> "已完成"
                            ImportRow.State.Working -> "处理中"
                            ImportRow.State.Waiting -> "等待"
                        },
                        style = TextStyle(
                            fontFamily = Tokens.Sans,
                            fontSize = 9.5.sp,
                            color = if (row.state == ImportRow.State.Working) Tokens.Accent else Tokens.InkTertiary,
                        ),
                    )
                }
            }
        }

        Box(Modifier.offset(y = 680.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Text(
                text = "导入完成后将自动进入编辑器",
                style = TextStyle(fontFamily = Tokens.Sans, fontSize = 9.sp, color = Tokens.InkTertiary),
            )
        }
        Box(Modifier.offset(y = 720.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .size(width = 160.dp, height = 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Tokens.Pill)
                    .border(1.dp, Tokens.PillHairline, RoundedCornerShape(20.dp))
                    .clickable(onClick = onCancel)
                    .testTag("import-cancel-button"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "取消导入",
                    style = TextStyle(fontFamily = Tokens.Sans, fontSize = 11.sp, color = Tokens.InkSecondary),
                )
            }
        }

        Box(
            Modifier
                .offset(x = 146.dp, y = 892.dp)
                .size(width = 120.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Tokens.Ink.copy(alpha = 0.35f)),
        )
    }
}
