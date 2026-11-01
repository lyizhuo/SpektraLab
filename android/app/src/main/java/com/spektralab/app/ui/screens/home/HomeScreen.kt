package com.spektralab.app.ui.screens.home

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spektralab.app.R
import com.spektralab.app.rolls.Roll
import com.spektralab.app.ui.components.ActionButton
import com.spektralab.app.ui.components.FormatChip
import com.spektralab.app.ui.components.RollLabel
import com.spektralab.app.ui.components.SectionHeading
import com.spektralab.app.ui.components.UriThumbnail
import com.spektralab.app.ui.stockPairLabel
import com.spektralab.app.ui.stockStyle
import com.spektralab.app.ui.theme.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One frame on the recent grid: the gallery's own entry, plus its format badge. */
data class RecentFrame(val uri: Uri, val name: String, val badge: String)

/**
 * C1 — the library.
 *
 * Written to the frame's own geometry: a 20 dp margin, a 34 dp amber plus
 * button, a 372 wide continue card carrying the open frame's photograph, two
 * 178 dp roll tiles with a 3 dp progress bar, and a 116 dp square grid of the
 * gallery. The type is the file's (22 sp title, 13 sp section, 10 sp body, 9 sp
 * caption), not Material's — see `Tokens.Type`.
 *
 * **What is deliberately not the file's.** The status bar and the 44 dp corner
 * radius belong to the device that shot the mock, not to this app: the real
 * system bar draws over the ground, and the window's own shape is the device's.
 * The bottom bar keeps the file's 26 dp inset above its buttons and takes the
 * system's navigation inset below them, so the bar sits on the physical bottom
 * edge of whatever phone it runs on.
 *
 * Content scrolls: the frame is 915 dp tall and phones are shorter, so the
 * column between the header and the bar is a scroll container rather than a
 * painting whose bottom third falls off the screen.
 */
@Composable
fun HomeScreen(
    rolls: List<Roll>,
    recent: List<RecentFrame>,
    onOpenRoll: (Roll) -> Unit,
    onPickAlbum: () -> Unit,
    onCamera: () -> Unit,
    onOpenRecent: (RecentFrame) -> Unit,
    onSearch: () -> Unit = {},
    selection: Set<Int> = emptySet(),
    onToggleSelection: (Int) -> Unit = {},
    onExitSelection: () -> Unit = {},
    onEditSelected: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val current = rolls.firstOrNull { !it.isFull } ?: rolls.firstOrNull()
    val selecting = selection.isNotEmpty()

    Column(
        modifier
            .fillMaxSize()
            .background(Tokens.Ground)
            .testTag("home"),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = Tokens.Margin),
        ) {
            Spacer(Modifier.height(13.dp))
            Identity(onSearch = onSearch)
            Spacer(Modifier.height(16.dp))

            if (current != null) {
                ContinueCard(
                    roll = current,
                    frameName = recent.firstOrNull()?.name,
                    heroUri = recent.firstOrNull { frame -> current.frameIds.any { it == frame.uri.toString() } }?.uri
                        ?: recent.firstOrNull()?.uri,
                    onClick = { onOpenRoll(current) },
                )
                Spacer(Modifier.height(22.dp))
            } else {
                EmptyHint("还没有胶卷 · 按下面的相机装第一卷")
                Spacer(Modifier.height(22.dp))
            }

            SectionHeading(
                title = if (selecting) "已选 ${selection.size} 张" else "我的胶卷",
                trailing = if (selecting) "退出" else "全部",
                trailingAccent = !selecting,
                onTrailing = if (selecting) onExitSelection else null,
            )
            Spacer(Modifier.height(4.dp))

            if (rolls.isEmpty()) {
                EmptyHint("还没有胶卷")
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    rolls.take(2).forEach { roll ->
                        RollTile(roll = roll, onClick = { onOpenRoll(roll) }, modifier = Modifier.weight(1f))
                    }
                    repeat((2 - rolls.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionHeading(
                title = if (selecting) "已选 ${selection.size} 张" else "最近照片",
                trailing = if (selecting) "退出" else "选择",
                trailingAccent = !selecting,
                onTrailing = if (selecting) onExitSelection else null,
            )
            Spacer(Modifier.height(8.dp))

            if (recent.isEmpty()) {
                EmptyHint("还没有照片 · 拍一张，或者从相册导入")
            } else {
                val frames = if (selecting) recent else recent.take(6)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    frames.chunked(3).forEachIndexed { rowIndex, chunk ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            chunk.forEachIndexed { column, frame ->
                                val index = rowIndex * 3 + column
                                RecentTile(
                                    frame = frame,
                                    selected = index in selection,
                                    selectable = selecting,
                                    onClick = {
                                        if (selecting) onToggleSelection(index) else onOpenRecent(frame)
                                    },
                                    // B1 needs a door: a long press is how a grid on
                                    // this platform enters multi-select, and without
                                    // one the 已选 bar and the batch screen it opens
                                    // had no way in at all.
                                    onLongClick = { onToggleSelection(index) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Text(
                text = "支持 RAW · TIFF · JPEG · HEIF",
                style = Tokens.Type.Caption,
                color = Tokens.InkTertiary,
            )
            Spacer(Modifier.height(10.dp))
        }

        HomeBar(
            selecting = selecting,
            count = selection.size,
            onCamera = onCamera,
            onPickAlbum = onPickAlbum,
            onEditSelected = onEditSelected,
            onExitSelection = onExitSelection,
        )
    }
}

/** The title, the slogan, and the two doors into a new photograph. */
@Composable
private fun Identity(onSearch: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text("Latent", style = Tokens.Type.Display, color = Tokens.Ink)
            Spacer(Modifier.height(2.dp))
            Text("把数码照片，洗成胶片", style = Tokens.Type.Body, color = Tokens.InkTertiary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = "搜索",
                tint = Tokens.InkSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(onClick = onSearch)
                    .testTag("home-search"),
            )
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(Tokens.Accent)
                    .clickable(onClick = onSearch),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = "导入",
                    tint = Tokens.OnAccent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * The frame in progress: the photograph on the right under a scrim, and four
 * lines of fact on the left.
 *
 * The gradient runs the card's own colour out over the photograph's left edge so
 * the two halves read as one surface, which is what the file's
 * `hero-photo-scrim` does.
 */
@Composable
private fun ContinueCard(
    roll: Roll,
    frameName: String?,
    heroUri: Uri?,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(154.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Tokens.Card)
            .border(1.dp, Tokens.Hairline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("home-continue"),
    ) {
        if (heroUri != null) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxWidth(0.487f)
                    .height(154.dp)
                    .clip(RoundedCornerShape(14.dp)),
            ) {
                UriThumbnail(heroUri, Modifier.fillMaxSize())
            }
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(158.dp)
                    .height(154.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Tokens.Card, Tokens.Card.copy(alpha = 0.68f), Color.Transparent),
                        ),
                    ),
            )
        }
        Column(Modifier.padding(start = 15.dp, top = 14.dp)) {
            Text("继续冲洗", style = Tokens.Type.Eyebrow, color = Tokens.Accent)
            Spacer(Modifier.height(6.dp))
            Text(
                text = frameName ?: "新的胶卷",
                style = Tokens.Type.CardTitle,
                color = Tokens.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.52f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stockPairLabel(roll.filmStockId, roll.paperStockId),
                style = Tokens.Type.Body,
                color = Tokens.InkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.55f),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${roll.count} / ${roll.capacity} 格",
                style = Tokens.Type.Caption,
                color = Tokens.InkTertiary,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .width(102.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Tokens.Pill)
                    .border(1.dp, Tokens.PillHairline, RoundedCornerShape(14.dp))
                    .clickable(onClick = onClick)
                    .testTag("home-continue-button"),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("继续编辑", style = Tokens.Type.PillLabel, color = Tokens.Ink)
                Spacer(Modifier.width(6.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_right),
                    contentDescription = null,
                    tint = Tokens.Ink,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * A roll: 178×130 with the stock's label band, its name, when it was loaded, and
 * how full it is.
 *
 * The progress bar is the file's 3 dp track at 10 % white with a 90 % amber fill
 * -- the one place C1 shows progress, and the reason the card is a card rather
 * than a line of text.
 */
@Composable
private fun RollTile(roll: Roll, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val style = stockStyle(roll.filmStockId)
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Tokens.Raise)
            .border(1.dp, Tokens.Hairline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(11.dp)
            .testTag("home-roll-${roll.id}"),
    ) {
        // The band carries the stock's art and its frame count; the label under
        // it carries the name, which C1 prints once and not twice.
        RollLabel(
            name = "",
            tint = style.tint,
            ink = style.ink,
            detail = "${roll.capacity} EXP",
        )
        Spacer(Modifier.height(9.dp))
        Text(
            text = style.title,
            style = Tokens.Type.RollName,
            color = Tokens.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${roll.count} 张 · ${relativeDay(roll.createdAtMillis)}",
            style = Tokens.Type.Caption,
            color = Tokens.InkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(9.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Tokens.Track),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(roll.progress())
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Tokens.AccentFill),
            )
        }
    }
}

/**
 * A recent frame: a square, the photograph, and its format in the corner.
 *
 * The selection affordance is the file's own: an amber ring around a picked
 * tile, and a filled circle with a check in it, rather than a Material chip.
 */
@Composable
private fun RecentTile(
    frame: RecentFrame,
    selected: Boolean,
    selectable: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(Tokens.Raise)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        UriThumbnail(frame.uri, Modifier.fillMaxSize())
        if (selected) {
            Box(Modifier.fillMaxSize().border(2.dp, Tokens.Accent, shape))
        }
        if (frame.badge.isNotEmpty()) {
            FormatChip(
                text = frame.badge,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
        }
        if (selectable) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (selected) Tokens.Accent else Tokens.Scrim)
                    .border(1.dp, if (selected) Tokens.Accent else Tokens.Ink.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = Tokens.OnAccent,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
    }
}

/** The file's bottom bar: 相机 filled, 相册 outlined, 26 dp above the buttons. */
@Composable
private fun HomeBar(
    selecting: Boolean,
    count: Int,
    onCamera: () -> Unit,
    onPickAlbum: () -> Unit,
    onEditSelected: () -> Unit,
    onExitSelection: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Tokens.Bar)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
    ) {
        if (selecting) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Tokens.Margin, vertical = 22.dp)
                    .testTag("home-selection-actions"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionButton(
                    label = "编辑 $count 张",
                    onClick = onEditSelected,
                    modifier = Modifier.weight(1f).testTag("home-edit-selected"),
                )
                ActionButton(
                    label = "退出",
                    onClick = onExitSelection,
                    filled = false,
                    modifier = Modifier.weight(1f).testTag("home-exit-selection"),
                )
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = Tokens.Margin, end = Tokens.Margin, top = 26.dp, bottom = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                ActionButton(
                    label = "相机",
                    onClick = onCamera,
                    modifier = Modifier.weight(1f).testTag("home-camera"),
                    leading = {
                        Icon(
                            painter = painterResource(R.drawable.ic_camera),
                            contentDescription = null,
                            tint = Tokens.OnAccent,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
                ActionButton(
                    label = "相册",
                    onClick = onPickAlbum,
                    filled = false,
                    modifier = Modifier.weight(1f).testTag("home-album"),
                    leading = {
                        Icon(
                            painter = painterResource(R.drawable.ic_image),
                            contentDescription = null,
                            tint = Tokens.Ink,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = Tokens.Type.Caption,
        color = Tokens.InkTertiary,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/** `今天` / `10月28日`, the way the roll tile says when it was loaded. */
private fun relativeDay(millis: Long): String {
    val now = System.currentTimeMillis()
    val day = 24L * 60 * 60 * 1000
    return if (now - millis < day) {
        "今天"
    } else {
        SimpleDateFormat("M月d日", Locale.CHINA).format(Date(millis))
    }
}
