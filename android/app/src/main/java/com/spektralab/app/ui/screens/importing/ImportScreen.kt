package com.spektralab.app.ui.screens.importing

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.app.R
import com.spektralab.app.ui.components.FilmFrame
import com.spektralab.app.ui.theme.Tokens

/** One file on its way in. */
data class ImportRow(val name: String, val state: State) {
    enum class State { Done, Working, Waiting }
}

/**
 * C2 — import.
 *
 * The frame's own anatomy: 导入 and 取消 in a 52 dp bar, the first frame arriving
 * inside a strip of film, 正在导入 N 张 over a 4 dp progress bar with the file being
 * decoded named beside the count, one 36 dp row per file with its own state, and
 * the promise under them — 导入完成后将自动进入编辑器 — because that is what makes
 * the wait tolerable.
 *
 * A long, interruptible operation has to say three things (which files are done,
 * which one is being decoded, that cancelling is allowed) and this screen says
 * all three in the frame's own words.
 */
@Composable
fun ImportScreen(
    rows: List<ImportRow>,
    preview: Bitmap?,
    onCancel: () -> Unit,
) {
    val done = rows.count { it.state == ImportRow.State.Done }
    val current = rows.firstOrNull { it.state == ImportRow.State.Working }?.name
        ?: rows.firstOrNull { it.state == ImportRow.State.Waiting }?.name
        ?: rows.lastOrNull()?.name
        ?: ""
    val total = rows.size.coerceAtLeast(1)
    val fraction = done.toFloat() / total

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Ground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .testTag("import"),
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = Tokens.Margin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("导入", style = Tokens.Type.RowSection, color = Tokens.Ink)
            Spacer(Modifier.weight(1f))
            Text(
                text = "取消",
                style = Tokens.Type.Body.copy(fontSize = 10.5.sp),
                color = Tokens.InkTertiary,
                modifier = Modifier.clickable(onClick = onCancel),
            )
        }

        // The first frame, still on its strip: the same negative the editor draws,
        // so the wait already shows what is being developed.
        Box(Modifier.fillMaxWidth().padding(top = 36.dp), contentAlignment = Alignment.Center) {
            FilmFrame(
                modifier = Modifier.width(200.dp).height(286.dp),
                edgeTop = "KODAK PORTRA 400",
                edgeTopRight = "${rows.size}",
                edgeBottomLeft = "16A",
                edgeBottomRight = "▮▮▮",
            ) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0A0908))) {
                    if (preview != null) {
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "正在导入 ${rows.size} 张",
            style = Tokens.Type.CardTitle.copy(fontSize = 17.sp),
            color = Tokens.Ink,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "正在解码 RAW 并生成首张预览",
            style = Tokens.Type.Caption,
            color = Tokens.InkTertiary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Tokens.Track),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Tokens.AccentFill),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(
                text = "$done / $total",
                style = Tokens.Type.Value,
                color = Tokens.InkSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = current,
                style = Tokens.Type.Value,
                color = Tokens.InkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(20.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            rows.take(6).forEach { row -> ImportRowLine(row) }
        }

        Spacer(Modifier.weight(1f))
        Text(
            text = "导入完成后将自动进入编辑器",
            style = Tokens.Type.Caption,
            color = Tokens.InkTertiary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .width(160.dp)
                .height(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(Tokens.Raise)
                .border(1.dp, Tokens.PillHairline, RoundedCornerShape(21.dp))
                .clickable(onClick = onCancel)
                .testTag("import-cancel"),
            contentAlignment = Alignment.Center,
        ) {
            Text("取消导入", style = Tokens.Type.PillLabel.copy(fontSize = 10.5.sp), color = Tokens.Ink)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * One file: its state's marker, its name, and the state in words.
 *
 * The marker is the frame's own — a filled amber ring with a check when done, a
 * hollow ring while it is being decoded — rather than a progress spinner: the
 * list is a strip of frames, and a spinner would be a second language.
 */
@Composable
private fun ImportRowLine(row: ImportRow) {
    val active = row.state == ImportRow.State.Working
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Tokens.Ink.copy(alpha = 0.06f) else Color.Transparent)
            .then(if (active) Modifier.border(1.dp, Tokens.Ink.copy(alpha = 0.1f), RoundedCornerShape(10.dp)) else Modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(if (row.state == ImportRow.State.Done) Tokens.Accent else Color.Transparent)
                .border(
                    width = 1.dp,
                    color = if (row.state == ImportRow.State.Done) Tokens.Accent else Tokens.InkTertiary,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (row.state) {
                ImportRow.State.Done -> Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = Tokens.OnAccent,
                    modifier = Modifier.size(10.dp),
                )
                ImportRow.State.Working -> Box(
                    Modifier.size(4.dp).clip(CircleShape).background(Tokens.Accent),
                )
                ImportRow.State.Waiting -> Unit
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = row.name,
            style = Tokens.Type.Body,
            color = Tokens.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = when (row.state) {
                ImportRow.State.Done -> "已完成"
                ImportRow.State.Working -> "处理中"
                ImportRow.State.Waiting -> "等待中"
            },
            style = Tokens.Type.Caption.copy(fontSize = 8.5.sp),
            color = if (active) Tokens.Accent else Tokens.InkTertiary,
        )
    }
}
