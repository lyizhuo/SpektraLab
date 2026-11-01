@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spektralab.app.ui.screens.batch

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import com.spektralab.app.ui.screens.editor.StripEntry
import com.spektralab.app.ui.screens.editor.StripThumbnail

/**
 * B1 — multi-select and sync.
 *
 * The screen answers one question honestly: *what travels* when settings are
 * copied between photographs. The frame answers it with the 携带的分组 list and
 * states what does **not** travel — crops and post-develop adjustments stay per
 * frame, and there is no batch undo. Both are true: geometry is per-frame and
 * the undo stack is per-session, so a batch claiming otherwise would lie.
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
    val all = listOf("胶片与相纸", "曝光", "白平衡", "胶片效果", "相纸效果", "场景置位")
    var groups by remember { mutableStateOf(all.toSet()) }

    Scaffold(
        modifier = Modifier.testTag("batch"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("已选 ${selected.size} 张", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    TextButton(onClick = onExit, modifier = Modifier.testTag("batch-exit")) {
                        Text("退出", color = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = "同步设置会写入选中的 ${selected.size} 张照片。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "不同步裁剪与显影后调整，且没有批量撤销。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onCopy, modifier = Modifier.testTag("batch-copy")) {
                            Text("拷贝", style = MaterialTheme.typography.labelMedium)
                        }
                        FilledTonalButton(onClick = onPaste, modifier = Modifier.testTag("batch-paste")) {
                            Text("粘贴", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = { onSync(groups) },
                            enabled = selected.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier.testTag("batch-sync"),
                        ) {
                            Text(
                                "同步到 ${selected.size} 张",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        TextButton(onClick = onExport, modifier = Modifier.testTag("batch-export")) {
                            Text("导出", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "携带的分组",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    all.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            pair.forEach { label ->
                                Row(
                                    Modifier
                                        .weight(1f)
                                        .clickable {
                                            groups = if (label in groups) groups - label else groups + label
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = label in groups,
                                        onCheckedChange = {
                                            groups = if (label in groups) groups - label else groups + label
                                        },
                                        modifier = Modifier.testTag("carry-$label"),
                                    )
                                    Text(label, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0C0B0A)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.86f)
                        .aspectRatio(
                            if (bitmap != null && bitmap.height > 0) {
                                bitmap.width.toFloat() / bitmap.height
                            } else {
                                3f / 2f
                            },
                        )
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF141009)),
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
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stockLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = frameNumber,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            LazyRow(
                Modifier.fillMaxWidth().height(72.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(strip) { index, entry ->
                    val picked = index in selected
                    Box(
                        Modifier
                            .size(width = 58.dp, height = 58.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onToggle(index) }
                            .testTag("batch-frame-$index"),
                    ) {
                        StripThumbnail(entry, Modifier.fillMaxSize())
                        if (picked) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(11.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
