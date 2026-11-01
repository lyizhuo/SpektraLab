package com.spektralab.app.ui.screens.batch

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
import com.spektralab.app.ui.frame.Layer
import com.spektralab.app.ui.frame.ScreenBand
import com.spektralab.app.ui.screens.editor.StripEntry
import com.spektralab.app.ui.screens.editor.StripThumbnail
import com.spektralab.app.ui.theme.Tokens

/**
 * B1 — 多选与同步, transcribed from the frame `5:560`.
 *
 * The screen exists to answer one question honestly: *what travels* when
 * settings are copied between photographs. The frame answers it with the
 * "携带的分组" list, and it says out loud what does **not** travel — "不同步裁剪与
 * 显影后调整，且没有批量撤销". Both of those are real: cropping is per-frame
 * geometry and the undo stack is per-session, so a batch that claimed to carry
 * them would be lying.
 */
@Composable
fun BatchScreen(
    strip: List<StripEntry>,
    selected: Set<Int>,
    bitmap: Bitmap?,
    stockLabel: String,
    frameNumber: String,
    onToggle: (Int) -> Unit,
    onExit: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSync: (Set<String>) -> Unit,
    onExport: () -> Unit,
) {
    var groups by remember {
        mutableStateOf(
            setOf("胶片与相纸", "曝光", "白平衡", "胶片效果", "相纸效果", "场景置位"),
        )
    }
    val count = selected.size

    ScreenBand {
        // ---- topbar --------------------------------------------------------
        Text(
            text = "已选 $count 张",
            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 14.sp, color = Tokens.Ink),
            modifier = Modifier.offset(x = 20.dp, y = 56.dp).testTag("batch-count"),
        )
        Box(Modifier.offset(y = 57.dp).fillMaxWidth().padding(end = 20.dp), contentAlignment = Alignment.TopEnd) {
            Text(
                text = "退出",
                style = TextStyle(fontFamily = Tokens.Sans, fontSize = 12.sp, color = Tokens.Accent),
                modifier = Modifier.clickable(onClick = onExit).testTag("batch-exit"),
            )
        }

        // ---- canvas: the current frame inside a film edge ------------------
        Layer(x = 0.dp, y = 92.dp, width = 412.dp, height = 520.dp) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0C0B0A)))
            Layer(x = 44.dp, y = 30.dp, width = 324.dp, height = 460.dp) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF141009)),
                ) {
                    Text(
                        text = stockLabel,
                        style = TextStyle(fontFamily = Tokens.Mono, fontSize = 6.5.sp, color = Color(0xFFC9A16A)),
                        modifier = Modifier.offset(x = 8.dp, y = 6.dp),
                    )
                    Box(Modifier.offset(y = 6.dp).fillMaxWidth().padding(end = 8.dp), contentAlignment = Alignment.TopEnd) {
                        Text(
                            text = frameNumber,
                            style = TextStyle(fontFamily = Tokens.Mono, fontSize = 6.5.sp, color = Color(0xFFC9A16A)),
                        )
                    }
                    repeat(9) { index ->
                        val x = 8.dp + (34.88 * index).dp
                        Sprocket(x, 11.dp)
                        Sprocket(x, 441.dp)
                    }
                    Box(
                        Modifier
                            .offset(x = 8.dp, y = 22.dp)
                            .size(width = 308.dp, height = 416.dp)
                            .background(Color(0xFF0A0908)),
                    ) {
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        text = "16A",
                        style = TextStyle(fontFamily = Tokens.Mono, fontSize = 6.5.sp, color = Color(0xFFC9A16A)),
                        modifier = Modifier.offset(x = 8.dp, y = 447.dp),
                    )
                    Box(Modifier.offset(y = 447.dp).fillMaxWidth().padding(end = 8.dp), contentAlignment = Alignment.TopEnd) {
                        Text(
                            text = "$frameNumber ▮▮▮",
                            style = TextStyle(fontFamily = Tokens.Mono, fontSize = 6.5.sp, color = Color(0xFFC9A16A)),
                        )
                    }
                }
            }
        }

        // ---- filmstrip, with the selected frames marked --------------------
        Layer(x = 0.dp, y = 612.dp, width = 412.dp, height = 62.dp) {
            Box(Modifier.fillMaxSize().background(Color(0xFF151210)))
            Row(
                Modifier.offset(x = 16.dp, y = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                strip.take(6).forEachIndexed { index, entry ->
                    val picked = index in selected
                    Box(
                        Modifier
                            .size(width = 58.dp, height = 46.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .border(
                                width = if (picked) 2.dp else 1.dp,
                                color = if (picked) Tokens.Accent else Tokens.Hairline,
                                shape = RoundedCornerShape(3.dp),
                            )
                            .clickable { onToggle(index) }
                            .testTag("batch-frame-$index"),
                    ) {
                        StripThumbnail(entry, Modifier.fillMaxSize())
                        if (picked) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Tokens.Accent),
                            )
                        }
                    }
                }
            }
        }

        // ---- select-actions ------------------------------------------------
        Layer(x = 0.dp, y = 674.dp, width = 412.dp, height = 241.dp) {
            Box(Modifier.fillMaxSize().background(Color(0xF7181513)))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.PillHairline))
            Text(
                text = "同步设置会写入选中的 $count 张照片。",
                style = TextStyle(fontFamily = Tokens.Sans, fontSize = 11.sp, color = Tokens.InkSecondary),
                modifier = Modifier.offset(x = 20.dp, y = 18.dp),
            )
            Text(
                text = "不同步裁剪与显影后调整，且没有批量撤销。",
                style = TextStyle(fontFamily = Tokens.Sans, fontSize = 10.sp, color = Tokens.InkTertiary),
                modifier = Modifier.offset(x = 20.dp, y = 36.dp),
            )
            Row(
                Modifier.offset(x = 20.dp, y = 66.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                ActionPill("拷贝", onClick = onCopy, tag = "batch-copy")
                ActionPill("粘贴", onClick = onPaste, tag = "batch-paste")
                ActionPill(
                    "同步到 $count 张",
                    accent = true,
                    enabled = count > 0,
                    onClick = { onSync(groups) },
                    tag = "batch-sync",
                )
                ActionPill("导出", onClick = onExport, tag = "batch-export")
            }
            Text(
                text = "携带的分组",
                style = TextStyle(fontFamily = Tokens.Sans, fontSize = 10.sp, color = Tokens.InkTertiary),
                modifier = Modifier.offset(x = 20.dp, y = 124.dp),
            )
            val labels = listOf(
                "胶片与相纸" to 0,
                "曝光" to 1,
                "白平衡" to 2,
                "胶片效果" to 3,
                "相纸效果" to 4,
                "场景置位" to 5,
            )
            labels.forEach { (label, index) ->
                val x = if (index % 2 == 0) 20.dp else 206.dp
                val y = 148.dp + (30 * (index / 2)).dp
                val on = label in groups
                Box(
                    Modifier
                        .offset(x = x, y = y)
                        .size(14.dp)
                        .clip(RoundedCornerShape(3.5.dp))
                        .background(if (on) Color(0xE6E1A95F) else Color(0x14FFFFFF))
                        .clickable {
                            groups = if (on) groups - label else groups + label
                        }
                        .testTag("carry-$label"),
                )
                Text(
                    text = label,
                    style = TextStyle(fontFamily = Tokens.Sans, fontSize = 10.5.sp, color = Tokens.InkSecondary),
                    modifier = Modifier.offset(x = x + 22.dp, y = y - 1.dp),
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

@Composable
private fun Sprocket(x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .offset(x = x, y = y)
            .size(width = 13.dp, height = 8.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xE6160D06)),
    )
}

@Composable
private fun ActionPill(
    label: String,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    tag: String,
) {
    Box(
        Modifier
            .width(88.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    accent && enabled -> Color(0xF2E1A95F)
                    else -> Color(0x0DFFFFFF)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = Tokens.Sans,
                fontSize = 11.sp,
                color = if (accent && enabled) Color(0xFF231708) else Tokens.InkSecondary,
                fontWeight = if (accent && enabled) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}
