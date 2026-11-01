@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spektralab.app.ui.screens.importing

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One file on its way in. */
data class ImportRow(val name: String, val state: State) {
    enum class State { Done, Working, Waiting }
}

/**
 * C2 — import.
 *
 * A long, interruptible operation has to say three things: which files are done,
 * which is being decoded, and that cancelling is allowed. The frame's own
 * promise — 导入完成后将自动进入编辑器 — is what makes the wait tolerable, so it
 * stays.
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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp)
            .testTag("import"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("导入", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onCancel, modifier = Modifier.testTag("import-cancel")) {
                Text("取消", color = MaterialTheme.colorScheme.primary)
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth(0.56f)
                    .aspectRatio(
                        if (preview != null && preview.height > 0) {
                            preview.width.toFloat() / preview.height
                        } else {
                            3f / 4f
                        },
                    )
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF141009)),
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
        }

        Text(
            text = "正在导入 ${rows.size} 张",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .testTag("import-title"),
        )
        Text(
            text = "正在解码 RAW 并生成首张预览",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { if (rows.isEmpty()) 0f else done.toFloat() / rows.size },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$done / ${rows.size}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = current,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(rows) { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (row.state == ImportRow.State.Working) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (row.state) {
                            ImportRow.State.Done -> Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            ImportRow.State.Working -> CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            ImportRow.State.Waiting -> Box(
                                Modifier
                                    .size(14.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                        }
                        Text(
                            text = row.name,
                            style = MaterialTheme.typography.bodySmall
                                .copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    Text(
                        text = when (row.state) {
                            ImportRow.State.Done -> "已完成"
                            ImportRow.State.Working -> "处理中"
                            ImportRow.State.Waiting -> "等待"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (row.state == ImportRow.State.Working) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    )
                }
            }
        }

        Text(
            text = "导入完成后将自动进入编辑器",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp),
        )
        OutlinedButton(
            onClick = onCancel,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 16.dp)
                .testTag("import-cancel-button"),
        ) {
            Text("取消导入", style = MaterialTheme.typography.labelMedium)
        }
    }
}
