package com.spektralab.app.ui.screens.batch

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.app.R
import com.spektralab.app.ui.components.FilmFrame
import com.spektralab.app.ui.components.Hairline
import com.spektralab.app.ui.theme.Tokens
import com.spektralab.app.ui.screens.editor.StripEntry
import com.spektralab.app.ui.screens.editor.StripThumbnail

/**
 * B1 — 多选与同步.
 *
 * The frame's own screen: 已选 N 张 and 退出 in a 52 dp bar, the negative with the
 * frame being looked at, the strip under it with a dot on every picked frame, the
 * two-line promise (what sync writes, and that it does not carry crop or
 * post-development edits and has no batch undo), then 拷贝 / 粘贴 / 同步到 N 张 /
 * 导出 and the six groups a sync can carry.
 *
 * The note is not decoration: it is the only place a person is told that this
 * operation cannot be undone, and the engine's own delta is why it is true.
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
    note: String? = null,
) {
    val groupsAvailable = listOf("胶片与相纸", "曝光", "白平衡", "胶片效果", "相纸效果", "场景置位")
    var groups by remember { mutableStateOf(groupsAvailable.toSet()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Ground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .testTag("batch"),
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = Tokens.Margin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已选 ${selected.size} 张", style = Tokens.Type.RowSection, color = Tokens.Ink)
            Spacer(Modifier.weight(1f))
            Text(
                text = "退出",
                style = Tokens.Type.Body.copy(fontSize = 10.5.sp),
                color = Tokens.Accent,
                modifier = Modifier.clickable(onClick = onExit).testTag("batch-exit"),
            )
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val photoRatio = bitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1.5f
            val frameWidth = minOf(maxWidth - 48.dp, (maxHeight - 60.dp) * photoRatio + 16.dp)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FilmFrame(
                    modifier = Modifier.width(frameWidth).aspectRatio(photoRatio),
                    edgeTop = stockLabel,
                    edgeTopRight = frameNumber,
                    edgeBottomLeft = "16A",
                    edgeBottomRight = "$frameNumber ▮▮▮",
                ) {
                    Box(Modifier.fillMaxSize().background(Tokens.Canvas)) {
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }

        // The strip: one 62×44 thumb per frame, the picked ones dotted.
        Column(Modifier.fillMaxWidth().background(Tokens.Bar)) {
            Hairline(color = Tokens.Ink.copy(alpha = 0.06f))
            LazyRow(
                Modifier.fillMaxWidth().height(76.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(strip) { index, entry ->
                    val picked = index in selected
                    Box(
                        Modifier
                            .size(width = 62.dp, height = 44.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Tokens.Raise)
                            .border(
                                width = if (picked) 1.5.dp else 1.dp,
                                color = if (picked) Tokens.Accent else Tokens.Ink.copy(alpha = 0.1f),
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
                                    .size(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Tokens.Accent),
                            )
                        }
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Margin).padding(top = 14.dp)) {
            Text(
                text = "同步设置会写入选中的 ${selected.size} 张照片。",
                style = Tokens.Type.Caption.copy(fontSize = 9.5.sp),
                color = Tokens.InkSecondary,
            )
            Text(
                text = "不同步裁剪与显影后调整，且没有批量撤销。",
                style = Tokens.Type.Caption.copy(fontSize = 8.5.sp),
                color = Tokens.InkTertiary,
            )

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BatchPill("拷贝", Modifier.weight(1f).testTag("batch-copy"), onCopy)
                BatchPill("粘贴", Modifier.weight(1f).testTag("batch-paste"), onPaste)
                BatchPill(
                    label = "同步到 ${selected.size} 张",
                    modifier = Modifier.weight(1.6f).testTag("batch-sync"),
                    onClick = { onSync(groups) },
                    accent = true,
                )
                BatchPill("导出", Modifier.weight(1f).testTag("batch-export"), onExport)
            }

            Spacer(Modifier.height(14.dp))
            Text("携带的分组", style = Tokens.Type.Caption.copy(fontSize = 9.sp), color = Tokens.InkTertiary)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                groupsAvailable.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth()) {
                        pair.forEach { label ->
                            val on = label in groups
                            Row(
                                Modifier
                                    .weight(1f)
                                    .clickable {
                                        groups = if (on) groups - label else groups + label
                                    }
                                    .testTag("carry-$label"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (on) Tokens.Accent else Tokens.Raise)
                                        .border(
                                            1.dp,
                                            if (on) Tokens.Accent else Tokens.Ink.copy(alpha = 0.25f),
                                            RoundedCornerShape(4.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (on) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_check),
                                            contentDescription = null,
                                            tint = Tokens.OnAccent,
                                            modifier = Modifier.size(9.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    style = Tokens.Type.Caption.copy(fontSize = 9.5.sp),
                                    color = if (on) Tokens.Ink else Tokens.InkTertiary,
                                )
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** A 28 dp pill: 拷贝 / 粘贴 / 导出 are quiet, 同步到 N 张 is the amber one. */
@Composable
private fun BatchPill(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    accent: Boolean = false,
) {
    Box(
        modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (accent) Tokens.AccentGhost else Tokens.Pill)
            .border(
                1.dp,
                if (accent) Tokens.AccentBorder else Tokens.PillHairline,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = Tokens.Type.PillLabel.copy(fontSize = 9.5.sp),
            color = if (accent) Tokens.Accent else Tokens.Ink,
            maxLines = 1,
        )
    }
}
