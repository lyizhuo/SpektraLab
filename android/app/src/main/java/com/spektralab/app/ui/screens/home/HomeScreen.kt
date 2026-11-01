package com.spektralab.app.ui.screens.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import com.spektralab.app.ui.frame.ScreenBand
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.app.rolls.Roll
import com.spektralab.app.rolls.stockLabel
import com.spektralab.app.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import android.net.Uri
import androidx.compose.ui.platform.LocalContext

/**
 * One photograph from the device's gallery.
 *
 * It used to be a [File] pointing into the app's own capture directory, so on a
 * phone full of photographs the grid was empty — which is exactly what the
 * first real-device run showed. It is a `content://` URI now, because that is
 * what the gallery hands out and what the editor opens.
 */
data class RecentFrame(val uri: Uri, val name: String, val badge: String)

/**
 * C1 — 首页 · 胶卷库.
 *
 * The layout is transcribed from the Figma frame `SCREEN C1 首页 胶卷库`
 * (node `31:2`, 412×915) **by offset**, not by eye: every `x`/`y` below is the
 * frame's own value and every colour is the file's. The frame is 412 wide,
 * which is the width these offsets assume, so [HomeScreen] paints into a
 * 412-wide band centred in whatever the device is — see [ScreenBand].
 *
 * What is *not* the file's: the typefaces (Inter and Cascadia Mono are not on
 * Android; see [Tokens]), the photographs (the frame's are Figma asset URLs;
 * here they are the user's own frames), and the two roll tiles, which render
 * only when there is a roll to render — the frame draws two samples, and an
 * empty second tile would be a lie about the library.
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

    ScreenBand(modifier.testTag("home")) {
        Column(Modifier.fillMaxSize()) {
            // The frame is 915 tall (786 of content + a 129 bar). A phone can
            // be shorter, so the content takes what is left and scrolls inside
            // it -- a fixed 786 pushed the bar off the bottom of this device.
            val scroll = rememberScrollState()
            // Without this the screen opened scrolled halfway down on the MI 8:
            // the content is 786 dp against a ~690 dp viewport, and something
            // (the permission dialog taking focus) moved it. The frame's first
            // pixel is its header, so the initial state is the top.
            LaunchedEffect(Unit) { scroll.scrollTo(0) }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll),
            ) {
                Box(Modifier.fillMaxWidth().height(786.dp)) {
                Header(onSearch = onSearch)
                if (current != null) {
                    ContinueCard(
                        roll = current,
                        frameName = recent.firstOrNull()?.name,
                        onClick = { onOpenRoll(current) },
                    )
                }
                val lifted = if (current == null) 187.dp else 0.dp
                SectionHeader(title = "我的胶卷", trailing = "全部", y = 303.dp - lifted)
                if (rolls.isEmpty()) {
                    // The frame assumes a library. An empty one has to say so,
                    // or the screen is a header over a void -- which is what
                    // the first real-device capture showed.
                    Text(
                        text = "还没有胶卷 · 按下面的相机开始第一卷",
                        style = TextStyle(
                            fontFamily = Tokens.Sans,
                            fontSize = 10.sp,
                            color = Tokens.InkTertiary,
                        ),
                        modifier = Modifier.offset(x = 20.dp, y = 330.dp - lifted),
                    )
                } else {
                    RollTiles(rolls = rolls, onOpenRoll = onOpenRoll)
                }
                SectionHeader(
                    title = if (selection.isEmpty()) "最近照片" else "已选 ${selection.size} 张",
                    trailing = if (selection.isEmpty()) "选择" else "退出",
                    trailingColor = Tokens.Accent,
                    y = 477.dp - lifted,
                    onTrailing = if (selection.isEmpty()) null else onExitSelection,
                )
                if (recent.isEmpty()) {
                    Text(
                        text = "还没有照片 · 拍一张，或者从相册导入",
                        style = TextStyle(
                            fontFamily = Tokens.Sans,
                            fontSize = 10.sp,
                            color = Tokens.InkTertiary,
                        ),
                        modifier = Modifier.offset(x = 20.dp, y = 505.dp - lifted),
                    )
                } else {
                    RecentGrid(
                        recent = recent,
                        onOpen = { frame ->
                            val index = recent.indexOf(frame)
                            if (selection.isEmpty()) onOpenRecent(frame) else onToggleSelection(index)
                        },
                        selection = selection,
                    )
                }
                Text(
                    text = "支持 RAW · TIFF · JPEG · HEIF",
                    style = TextStyle(fontFamily = Tokens.Sans, fontSize = 9.sp, color = Tokens.InkTertiary),
                    modifier = Modifier.offset(x = 20.dp, y = 767.dp),
                )
                }
            }
            if (selection.isEmpty()) {
                BottomActions(onPickAlbum = onPickAlbum, onCamera = onCamera)
            } else {
                SelectionActions(count = selection.size, onEdit = onEditSelected, onExit = onExitSelection)
            }
        }
    }
}

@Composable
private fun Header(onSearch: () -> Unit) {
    Text(
        text = "SpektraLab",
        style = TextStyle(fontFamily = Tokens.Sans, fontSize = 22.sp, color = Tokens.Ink, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.offset(x = 20.dp, y = 53.dp),
    )
    Text(
        text = "把数码照片，洗成胶片",
        style = TextStyle(fontFamily = Tokens.Sans, fontSize = 10.sp, color = Tokens.InkTertiary),
        modifier = Modifier.offset(x = 20.dp, y = 86.dp),
    )
    Box(
        Modifier
            .offset(x = 326.dp, y = 54.dp)
            .size(20.dp)
            .clickable(onClick = onSearch)
            .testTag("home-search"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Search, null, tint = Tokens.InkSecondary, modifier = Modifier.size(18.dp))
    }
    // action/import-plus: 34 dp, radius 17, the one solid accent on this screen.
    Box(
        Modifier
            .offset(x = 358.dp, y = 53.dp)
            .size(34.dp)
            .clip(CircleShape)
            .background(Tokens.Accent)
            .clickable(onClick = onSearch)
            .testTag("home-import"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Add, null, tint = Tokens.Ground, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ContinueCard(roll: Roll, frameName: String?, onClick: () -> Unit) {
    Box(
        Modifier
            .offset(x = 20.dp, y = 116.dp)
            .width(372.dp)
            .height(154.dp)
            .clip(RoundedCornerShape(Tokens.Radius.Card))
            .background(Tokens.Card)
            .border(1.dp, Tokens.Hairline, RoundedCornerShape(Tokens.Radius.Card))
            .clickable(onClick = onClick)
            .testTag("home-continue"),
    ) {
        // The frame puts the photograph's corner flush with the card's, so the
        // scrim runs left-to-right from the card's own ground.
        Box(
            Modifier
                .offset(x = 213.dp)
                .size(width = 181.dp, height = 153.dp)
                .clip(RoundedCornerShape(Tokens.Radius.Thumb))
                .background(Color(0xFF2A211A)),
        )
        Box(
            Modifier
                .offset(x = 213.dp)
                .size(width = 158.dp, height = 153.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Tokens.Card, Color(0xAD191512), Color(0x00191512)),
                    ),
                ),
        )
        Text(
            text = "继续冲洗",
            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 10.sp, color = Tokens.Accent, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.offset(x = 15.dp, y = 15.dp),
        )
        Text(
            text = frameName ?: "${stockLabel(roll.filmStockId)} 卷",
            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 16.sp, color = Tokens.Ink, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.offset(x = 15.dp, y = 36.dp),
        )
        Text(
            text = "${stockLabel(roll.filmStockId)} · ${stockLabel(roll.paperStockId)}",
            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 10.sp, color = Tokens.InkSecondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset(x = 15.dp, y = 60.dp)
                .width(194.dp),
        )
        Text(
            text = "${roll.count} / ${roll.capacity} 格",
            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 9.sp, color = Tokens.InkTertiary),
            modifier = Modifier.offset(x = 15.dp, y = 81.dp),
        )
        Row(
            Modifier
                .offset(x = 15.dp, y = 111.dp)
                .width(102.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(Tokens.Radius.Chip))
                .background(Tokens.Pill)
                .border(1.dp, Tokens.PillHairline, RoundedCornerShape(Tokens.Radius.Chip)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "继续拍摄",
                style = TextStyle(fontFamily = Tokens.Sans, fontSize = 9.5.sp, color = Tokens.Ink, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                null,
                tint = Tokens.Ink,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    trailing: String,
    y: Dp,
    trailingColor: Color = Tokens.InkSecondary,
    onTrailing: (() -> Unit)? = null,
) {
    Text(
        text = title,
        style = TextStyle(fontFamily = Tokens.Sans, fontSize = 13.sp, color = Tokens.Ink, fontWeight = FontWeight.Bold),
        modifier = Modifier.offset(x = 20.dp, y = y),
    )
    Box(
        Modifier
            .offset(y = y)
            .fillMaxWidth()
            .padding(end = 20.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Text(
            text = trailing,
            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 10.sp, color = trailingColor),
            modifier = Modifier
                .then(if (onTrailing != null) Modifier.clickable(onClick = onTrailing) else Modifier)
                .testTag("home-trailing"),
        )
    }
}

@Composable
private fun RollTiles(rolls: List<Roll>, onOpenRoll: (Roll) -> Unit) {
    val shown = rolls.take(2)
    shown.forEachIndexed { index, roll ->
        val x = if (index == 0) 20.dp else 214.dp
        Box(
            Modifier
                .offset(x = x, y = 318.dp)
                .size(width = 178.dp, height = 130.dp)
                .clip(RoundedCornerShape(Tokens.Radius.Tile))
                .background(Tokens.Pill)
                .border(1.dp, Tokens.Hairline, RoundedCornerShape(Tokens.Radius.Tile))
                .clickable { onOpenRoll(roll) }
                .testTag("home-roll-${roll.id}"),
        ) {
            Box(
                Modifier
                    .offset(x = 11.dp, y = 11.dp)
                    .size(width = 154.dp, height = 56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF241D18)),
            )
            Text(
                text = stockLabel(roll.filmStockId),
                style = TextStyle(fontFamily = Tokens.Sans, fontSize = 12.sp, color = Tokens.Ink, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .offset(x = 11.dp, y = 79.dp)
                    .width(150.dp),
            )
            Text(
                text = "${roll.count} 张 · ${rollLabel(roll)}",
                style = TextStyle(fontFamily = Tokens.Sans, fontSize = 9.sp, color = Tokens.InkSecondary),
                modifier = Modifier.offset(x = 11.dp, y = 95.dp),
            )
            // progress-track 154 wide; progress-fill is count/capacity of it.
            Box(
                Modifier
                    .offset(x = 11.dp, y = 111.dp)
                    .size(width = 154.dp, height = 3.dp)
                    .clip(RoundedCornerShape(Tokens.Radius.Track))
                    .background(Tokens.Track),
            )
            val fraction = if (roll.capacity == 0) 0f else roll.count.toFloat() / roll.capacity
            Box(
                Modifier
                    .offset(x = 11.dp, y = 111.dp)
                    .size(width = (154 * fraction.coerceIn(0f, 1f)).dp, height = 3.dp)
                    .clip(RoundedCornerShape(Tokens.Radius.Track))
                    .background(Tokens.AccentFill),
            )
        }
    }
}

@Composable
private fun RecentGrid(recent: List<RecentFrame>, onOpen: (RecentFrame) -> Unit, selection: Set<Int> = emptySet()) {
    val columns = listOf(20.dp, 144.dp, 268.dp)
    recent.take(6).forEachIndexed { index, frame ->
        val x = columns[index % 3]
        val y = if (index < 3) 496.dp else 620.dp
        Box(
            Modifier
                .offset(x = x, y = y)
                .size(116.dp)
                .clip(RoundedCornerShape(Tokens.Radius.Thumb))
                .background(Color(0xFF241D18))
                .clickable { onOpen(frame) }
                .testTag("home-recent-${frame.name}"),
        ) {
            Thumbnail(frame.uri)
            if (index in selection) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Tokens.Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✓",
                        style = TextStyle(fontFamily = Tokens.Sans, fontSize = 10.sp, color = Tokens.Ground),
                    )
                }
            }
            if (index == 0 && selection.isEmpty()) {
                Box(
                    Modifier
                        .offset(x = 8.dp, y = 8.dp)
                        .size(width = 36.dp, height = 16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Tokens.Scrim),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = frame.badge,
                        style = TextStyle(
                            fontFamily = Tokens.Sans,
                            fontSize = 7.5.sp,
                            color = Tokens.InkSecondary,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(uri: Uri) {
    val context: Context = LocalContext.current.applicationContext
    val bitmap = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri.toString()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // `loadThumbnail` is the platform's own downscaler: it reads
                // the embedded thumbnail or decodes at the requested size,
                // which hand-rolled BitmapFactory sampling on a content://
                // stream did not reliably do -- those tiles came out black.
                context.contentResolver
                    .loadThumbnail(uri, android.util.Size(320, 320), null)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap.value?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun BottomActions(onPickAlbum: () -> Unit, onCamera: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(129.dp)
            .background(Tokens.Bar)
            .testTag("home-actions"),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Hairline))
        // The frame puts the camera on the left, filled, and the album import
        // on the right as a ghost. That order is the product's: the camera is
        // the primary act, importing is the exception.
        AccentButton(
            label = "相机",
            icon = { Icon(Icons.Outlined.PhotoCamera, null, tint = Tokens.Ground, modifier = Modifier.size(16.dp)) },
            onClick = onCamera,
            x = 20.dp,
            tag = "home-camera",
        )
        GhostButton(
            label = "相册",
            icon = { Icon(Icons.Outlined.PhotoLibrary, null, tint = Tokens.Ink, modifier = Modifier.size(16.dp)) },
            onClick = onPickAlbum,
            x = 218.dp,
            tag = "home-album",
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-19).dp)
                .size(width = 120.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Tokens.Ink.copy(alpha = 0.45f)),
        )
    }
}

@Composable
private fun AccentButton(label: String, icon: @Composable () -> Unit, onClick: () -> Unit, x: Dp, tag: String) {
    Row(
        Modifier
            .offset(x = x, y = 26.dp)
            .size(width = 170.dp, height = 44.dp)
            .clip(RoundedCornerShape(Tokens.Radius.Pill))
            .background(Tokens.Accent)
            .clickable(onClick = onClick)
            .testTag(tag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 12.sp, color = Tokens.Ground, fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun GhostButton(label: String, icon: @Composable () -> Unit, onClick: () -> Unit, x: Dp, tag: String) {
    Row(
        Modifier
            .offset(x = x, y = 26.dp)
            .size(width = 170.dp, height = 44.dp)
            .clip(RoundedCornerShape(Tokens.Radius.Pill))
            .background(Tokens.Pill)
            .border(1.dp, Tokens.PillHairline, RoundedCornerShape(Tokens.Radius.Pill))
            .clickable(onClick = onClick)
            .testTag(tag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 12.sp, color = Tokens.Ink, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** "今天" / "10月28日", as the frame writes it. */
private fun rollLabel(roll: Roll): String {
    val days = ((System.currentTimeMillis() - roll.createdAtMillis) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "今天"
        days == 1 -> "昨天"
        else -> {
            val calendar = java.util.Calendar.getInstance().apply { timeInMillis = roll.createdAtMillis }
            "${calendar.get(java.util.Calendar.MONTH) + 1}月${calendar.get(java.util.Calendar.DAY_OF_MONTH)}日"
        }
    }
}

/** C1's bottom bar once something is selected: one action, and the way out. */
@Composable
private fun SelectionActions(count: Int, onEdit: () -> Unit, onExit: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(129.dp)
            .background(Tokens.Bar)
            .testTag("home-selection-actions"),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Hairline))
        AccentButton(
            label = "编辑 $count 张",
            icon = {},
            onClick = onEdit,
            x = 20.dp,
            tag = "home-edit-selected",
        )
        GhostButton(
            label = "退出",
            icon = {},
            onClick = onExit,
            x = 218.dp,
            tag = "home-exit-selection",
        )
    }
}