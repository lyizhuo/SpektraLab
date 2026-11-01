package com.spektralab.app.ui.screens.picker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.spektralab.app.R
import com.spektralab.app.ui.components.ActionButton
import com.spektralab.app.ui.components.UriThumbnail
import com.spektralab.app.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One photograph the picker can offer. */
data class GalleryFrame(val uri: Uri, val name: String)

/**
 * B2 — 导入照片, the picker the prototype draws.
 *
 * The app used to hand this to the system's document picker, which is why there
 * was no B2 screen at all: a picker owned by the OS is a picker this product
 * cannot style, and the prototype's version is not a photo grid with round
 * corners — it is 全选, a 照片/相册 pair, 最近项目 with a count, a three-column
 * grid whose chosen tiles carry an amber ring and a numbered check, and the
 * promise under it (最多 24 张, 保持拍摄顺序) before the amber 导入 N 张.
 *
 * So this reads the gallery through MediaStore itself. The system picker is still
 * the door for a *file* the gallery does not index (a DNG in a folder, a card
 * reader): the 相册 pill and the app's own import path keep it reachable.
 */
@Composable
fun PhotoPickerScreen(
    onCancel: () -> Unit,
    onImport: (List<GalleryFrame>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var frames by remember { mutableStateOf<List<GalleryFrame>>(emptyList()) }
    var selected by remember { mutableStateOf<List<Int>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_IMAGES,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        denied = !granted
        if (granted) {
            frames = withContext(Dispatchers.IO) { readGallery(context) }
        }
        loaded = true
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Tokens.Ground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .testTag("picker"),
    ) {
        PickerBar(
            onCancel = onCancel,
            onSelectAll = { selected = if (selected.size == frames.size) emptyList() else frames.indices.toList() },
            allSelected = frames.isNotEmpty() && selected.size == frames.size,
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = Tokens.Margin),
            horizontalArrangement = Arrangement.Center,
        ) {
            PickerTab("照片", selected = true, onClick = {})
            PickerTab("相册", selected = false, onClick = {})
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Margin), verticalAlignment = Alignment.CenterVertically) {
            Text("最近项目", style = Tokens.Type.Body, color = Tokens.Ink, modifier = Modifier.weight(1f))
            Text(
                text = if (loaded) "${frames.size} 张" else "读取中…",
                style = Tokens.Type.Caption.copy(fontFamily = Tokens.Mono),
                color = Tokens.InkTertiary,
            )
        }
        Spacer(Modifier.height(10.dp))

        if (denied) {
            Column(Modifier.padding(horizontal = Tokens.Margin)) {
                Text(
                    text = "没有相册权限，读不到这台机器上的照片。",
                    style = Tokens.Type.Caption,
                    color = Tokens.InkSecondary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "可以从系统文件选择器打开一张照片，编辑器仍然可用。",
                    style = Tokens.Type.Caption,
                    color = Tokens.InkTertiary,
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = Tokens.Margin),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(frames) { index, frame ->
                val order = selected.indexOf(index)
                PickerTile(
                    frame = frame,
                    order = if (order >= 0) order + 1 else null,
                    onClick = {
                        selected = when {
                            order >= 0 -> selected - index
                            selected.size >= MAX_PICK -> selected
                            else -> selected + index
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Margin)) {
            Text(
                text = "已选 ${selected.size} 张",
                style = Tokens.Type.RowLabel,
                color = Tokens.Ink,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "最多 $MAX_PICK 张",
                style = Tokens.Type.Caption.copy(fontFamily = Tokens.Mono),
                color = Tokens.InkTertiary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "导入后仍可在胶卷中拖动排序",
            style = Tokens.Type.Caption.copy(fontSize = 8.5.sp),
            color = Tokens.InkTertiary,
            modifier = Modifier.padding(horizontal = Tokens.Margin),
        )

        ActionButton(
            label = "导入 ${selected.size} 张",
            onClick = { onImport(selected.sorted().mapNotNull { frames.getOrNull(it) }) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Tokens.Margin, end = Tokens.Margin, top = 12.dp, bottom = 24.dp)
                .testTag("picker-import"),
        )
    }
}

/** 52 dp: close, 导入照片, 全选. */
@Composable
private fun PickerBar(onCancel: () -> Unit, onSelectAll: () -> Unit, allSelected: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = Tokens.Margin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Tokens.Raise)
                .border(1.dp, Tokens.PillHairline, CircleShape)
                .clickable(onClick = onCancel)
                .testTag("picker-cancel"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_x),
                contentDescription = "关闭",
                tint = Tokens.Ink,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = "导入照片",
            style = Tokens.Type.BarTitle,
            color = Tokens.Ink,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Box(
            Modifier
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(if (allSelected) Tokens.AccentGhost else Tokens.Pill)
                .border(1.dp, if (allSelected) Tokens.AccentBorder else Tokens.PillHairline, RoundedCornerShape(13.dp))
                .clickable(onClick = onSelectAll)
                .padding(horizontal = 12.dp)
                .testTag("picker-select-all"),
            contentAlignment = Alignment.Center,
        ) {
            Text("全选", style = Tokens.Type.PillLabel, color = if (allSelected) Tokens.Accent else Tokens.Ink)
        }
    }
}

@Composable
private fun PickerTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Tokens.AccentGhost else Tokens.Raise)
            .border(1.dp, if (selected) Tokens.AccentBorder else Tokens.Ink.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = Tokens.Type.Caption.copy(fontSize = 9.5.sp),
            color = if (selected) Tokens.Accent else Tokens.InkSecondary,
        )
    }
}

/** A tile with the amber ring and the numbered check the frame draws. */
@Composable
private fun PickerTile(frame: GalleryFrame, order: Int?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(Tokens.Raise)
            .clickable(onClick = onClick),
    ) {
        UriThumbnail(frame.uri, Modifier.fillMaxSize())
        if (order != null) {
            Box(Modifier.fillMaxSize().border(2.dp, Tokens.Accent, shape))
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (order != null) Tokens.Accent else Color(0x66000000))
                .border(1.dp, if (order != null) Tokens.Accent else Color(0x73FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (order != null) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = Tokens.OnAccent,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}

/** The gallery, newest first, as the picker offers it. */
private fun readGallery(context: Context): List<GalleryFrame> {
    val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
    val out = mutableListOf<GalleryFrame>()
    runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext() && out.size < GALLERY_LIMIT) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "photo"
                out += GalleryFrame(
                    uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id,
                    ),
                    name = name,
                )
            }
        }
    }
    return out
}

/** B2's own limit: the frame says 最多 24 张. */
const val MAX_PICK = 24

/** How many the grid offers before it stops paging. */
private const val GALLERY_LIMIT = 240
