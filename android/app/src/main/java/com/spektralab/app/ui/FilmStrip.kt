package com.spektralab.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One photograph in the strip. The uri is what `open()` takes. */
data class StripEntry(val uri: Uri, val name: String)

/**
 * The film strip (plan S5 item 1, and the "top film strip" of the phone
 * breakpoint).
 *
 * Thumbnails come from the platform's own thumbnail service
 * (`ContentResolver.loadThumbnail`), not from the engine: the engine's
 * `sf_thumbnail` needs a `spk_engine` and a decode, and a strip of 40 frames
 * must not cost 40 decodes. A provider that cannot answer gives no thumbnail
 * and the tile falls back to an icon — the strip is navigation, so a missing
 * pixel is not an error.
 *
 * The cache is an LRU in *bytes* (plan S5's LRU cap, sized for thumbnails
 * rather than the plan's 256 full-size entries) so a folder of RAW files
 * cannot grow the heap tile by tile.
 */
@Composable
fun FilmStrip(
    entries: List<StripEntry>,
    activeUri: Uri?,
    onSelect: (StripEntry) -> Unit,
    onAddPhotos: () -> Unit,
    onAddFolder: () -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val context = LocalContext.current
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))) {
        if (vertical) {
            Column(Modifier.fillMaxWidth()) {
                StripActions(onAddPhotos = onAddPhotos, onAddFolder = onAddFolder, vertical = true)
                if (entries.isEmpty()) {
                    Text(
                        text = "no frames",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                ) {
                    items(entries, key = { it.uri.toString() }) { entry ->
                        Tile(context, entry, entry.uri == activeUri, onSelect, Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StripActions(onAddPhotos = onAddPhotos, onAddFolder = onAddFolder, vertical = false)
                    Text(
                        text = if (entries.isEmpty()) "no frames yet" else "${entries.size} frame(s)",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    items(entries, key = { it.uri.toString() }) { entry ->
                        Tile(context, entry, entry.uri == activeUri, onSelect, Modifier.width(72.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StripActions(onAddPhotos: () -> Unit, onAddFolder: () -> Unit, vertical: Boolean) {
    Row(
        modifier = Modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onAddPhotos, modifier = Modifier.testTag("strip-add-photos")) {
            Icon(Icons.Filled.Add, contentDescription = "add photographs")
        }
        IconButton(onClick = onAddFolder, modifier = Modifier.testTag("strip-add-folder")) {
            Icon(Icons.Filled.FolderOpen, contentDescription = "add a folder of raw files")
        }
        if (vertical) {
            Text("strip", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Tile(
    context: Context,
    entry: StripEntry,
    active: Boolean,
    onSelect: (StripEntry) -> Unit,
    modifier: Modifier,
) {
    val thumbnail by produceState<Bitmap?>(initialValue = null, entry.uri) {
        value = Thumbnails.load(context, entry.uri)
    }
    Column(
        modifier
            .clickable { onSelect(entry) }
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF1A1A1A)),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = thumbnail
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = entry.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = entry.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private object Thumbnails {

    private const val SIZE = 160
    private const val CACHE_BYTES = 8 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(uri.toString())?.let { return@withContext it }
        val loaded = runCatching {
            context.contentResolver.loadThumbnail(uri, android.util.Size(SIZE, SIZE), null)
        }.getOrNull()
        loaded?.also { cache.put(uri.toString(), it) }
    }
}
