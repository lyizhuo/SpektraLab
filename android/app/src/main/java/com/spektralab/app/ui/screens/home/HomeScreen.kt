package com.spektralab.app.ui.screens.home

import android.content.Context
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spektralab.app.rolls.Roll
import com.spektralab.app.rolls.stockLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One frame on the recent grid: the gallery's own entry, plus its format badge. */
data class RecentFrame(val uri: Uri, val name: String, val badge: String)

/**
 * C1 — the library.
 *
 * **Layout comes from Material 3, not from the frame's coordinates.** The
 * prototype is 412×915 with every element absolutely placed; reproducing that
 * literally is what broke on a 393 dp phone. This screen keeps the frame's
 * *structure* and visual language — ground, hairline cards, the amber safelight,
 * the section order, the numbers — and expresses it with `Scaffold`,
 * `LazyColumn`/`LazyRow` and weights, so it composes itself at any width and any
 * height. Content that scrolls is lazy; the bar is fixed; the insets are the
 * window's, not a guessed 129 dp.
 *
 * The frame's own hierarchy, top to bottom: identity, the roll in progress, the
 * roll library, the recent frames, what the app can open, and the two doors
 * (camera, album). Nothing has been added to it and nothing removed.
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

    Scaffold(
        modifier = modifier.testTag("home"),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (selection.isEmpty()) {
                HomeBar(onCamera = onCamera, onPickAlbum = onPickAlbum)
            } else {
                SelectionBar(
                    count = selection.size,
                    onEdit = onEditSelected,
                    onExit = onExitSelection,
                )
            }
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = insets.calculateTopPadding() + 12.dp,
                bottom = insets.calculateBottomPadding() + 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item { Identity(onSearch = onSearch) }

            if (current != null) {
                item {
                    Spacer(Modifier.height(20.dp))
                    ContinueCard(
                        roll = current,
                        frameName = recent.firstOrNull()?.name,
                        onClick = { onOpenRoll(current) },
                    )
                }
            }

            item {
                SectionHeader(
                    title = "我的胶卷",
                    trailing = "全部",
                    modifier = Modifier.padding(top = 28.dp, bottom = 12.dp),
                )
            }
            if (rolls.isEmpty()) {
                item { EmptyLine("还没有胶卷 · 按下面的相机开始第一卷") }
            } else {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(rolls) { roll ->
                            RollTile(roll = roll, onClick = { onOpenRoll(roll) })
                        }
                    }
                }
            }

            item {
                SectionHeader(
                    title = if (selection.isEmpty()) "最近照片" else "已选 ${selection.size} 张",
                    trailing = if (selection.isEmpty()) "选择" else "退出",
                    accent = selection.isEmpty(),
                    onTrailing = if (selection.isEmpty()) null else onExitSelection,
                    modifier = Modifier.padding(top = 28.dp, bottom = 12.dp),
                )
            }
            if (recent.isEmpty()) {
                item { EmptyLine("还没有照片 · 拍一张，或者从相册导入") }
            } else {
                // Rows of three inside the one scroll container. A nested
                // vertical grid would fight this list for the same axis.
                recent.chunked(3).forEachIndexed { rowIndex, chunk ->
                    item(key = "recent-$rowIndex") {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            chunk.forEachIndexed { column, frame ->
                                val index = rowIndex * 3 + column
                                RecentTile(
                                    frame = frame,
                                    selected = index in selection,
                                    selectable = selection.isNotEmpty(),
                                    onClick = {
                                        if (selection.isEmpty()) {
                                            onOpenRecent(frame)
                                        } else {
                                            onToggleSelection(index)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "支持 RAW · TIFF · JPEG · HEIF",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Identity(onSearch: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Latent",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "把数码照片，洗成胶片",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        IconButton(onClick = onSearch, modifier = Modifier.testTag("home-search")) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = "搜索",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ContinueCard(roll: Roll, frameName: String?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().testTag("home-continue"),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = "继续冲洗",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = frameName ?: "新的胶卷",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(stockLabel(roll.filmStockId), stockLabel(roll.paperStockId))
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${roll.count} / ${roll.capacity} 格",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(
                onClick = onClick,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.testTag("home-continue-button"),
            ) {
                Text("继续拍摄 →", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun RollTile(roll: Roll, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.width(178.dp).testTag("home-roll-${roll.id}"),
    ) {
        Column {
            // The frame prints the stock's box art here. The product does not
            // license Kodak's or Fuji's packaging, so the band is the film's own
            // colour rather than a copied box.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = stockLabel(roll.filmStockId) ?: "胶卷",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${roll.count} 张 · " + android.text.format.DateUtils.getRelativeTimeSpanString(roll.createdAtMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun RecentTile(
    frame: RecentFrame,
    selected: Boolean,
    selectable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .aspectRatio(0.78f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    ) {
        Thumbnail(frame.uri, Modifier.fillMaxSize())
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (frame.badge.isNotEmpty()) {
                AssistChip(
                    onClick = onClick,
                    label = {
                        Text(frame.badge, style = MaterialTheme.typography.labelSmall)
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                        labelColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
            if (selected) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        if (selectable && !selected) {
            // A dimmed frame reads as "not picked" without a second control.
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.35f)))
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    trailing: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onTrailing: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = trailing,
            style = MaterialTheme.typography.labelMedium,
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier
                .then(if (onTrailing != null) Modifier.clickable(onClick = onTrailing) else Modifier)
                .testTag("home-trailing"),
        )
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun HomeBar(onCamera: () -> Unit, onPickAlbum: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onCamera,
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.weight(1f).height(52.dp).testTag("home-camera"),
            ) {
                Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("相机", style = MaterialTheme.typography.labelLarge)
            }
            FilledTonalButton(
                onClick = onPickAlbum,
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.weight(1f).height(52.dp).testTag("home-album"),
            ) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("相册", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SelectionBar(count: Int, onEdit: () -> Unit, onExit: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .testTag("home-selection-actions"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onEdit,
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.weight(1f).height(52.dp).testTag("home-edit-selected"),
            ) {
                Text("编辑 $count 张", style = MaterialTheme.typography.labelLarge)
            }
            FilledTonalButton(
                onClick = onExit,
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.weight(1f).height(52.dp).testTag("home-exit-selection"),
            ) {
                Text("退出", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** The platform's own thumbnail service, off the main thread. */
@Composable
private fun Thumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val context: Context = LocalContext.current.applicationContext
    val bitmap by produceState<ImageBitmap?>(null, uri.toString()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(320, 320), null).asImageBitmap()
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) {
        androidx.compose.foundation.Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}
