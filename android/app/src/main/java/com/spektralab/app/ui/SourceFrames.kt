package com.spektralab.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The *source* frame, for the two places the UI has to show it rather than
 * what the engine just rendered:
 *
 *  * the canvas's long press ("see the original frame", plan S5 item 2), and
 *  * the crop page's map, because the geometry is normalized on the source
 *    frame the engine decoded — `node_geometry` reads the crop in `in.w`/
 *    `in.h` of its own input (`engine/src/pipeline/pipeline.cpp`) — while the
 *    rendered frame the canvas shows has that crop already applied. Cropping a
 *    crop is arithmetic, but it is not what the wire field means.
 *
 * Two decoders, and the difference matters:
 *
 *  * [thumbnail] asks the platform's thumbnail service
 *    (`ContentResolver.loadThumbnail`): the cheap answer for a long press on a
 *    45 MP original, and the only answer for a RAW no platform decoder knows;
 *  * [bounded] uses `BitmapFactory` with a sample size: exact aspect ratio and
 *    **no EXIF rotation applied**, which is what the map needs, because
 *    `orientation` is a field the engine reports rather than one it acts on.
 *
 * Neither touches the engine: a preview must not cost a session.
 */
internal object SourceFrames {

    /** Long edge of the long-press preview. */
    const val PREVIEW_EDGE = 2048

    /** Long edge of the crop map: enough for a corner drag, small enough to cache. */
    const val MAP_EDGE = 1024

    private const val CACHE_BYTES = 16 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun thumbnail(context: Context, uri: Uri, maxEdge: Int): Bitmap? =
        load("thumb:$maxEdge:$uri") {
            runCatching {
                context.contentResolver.loadThumbnail(uri, android.util.Size(maxEdge, maxEdge), null)
            }.getOrNull()
        }

    suspend fun bounded(context: Context, uri: Uri, maxEdge: Int): Bitmap? =
        load("file:$maxEdge:$uri") {
            val path = uri.path ?: return@load null
            runCatching { BitmapFactory.decodeFile(path, options(path, maxEdge)) }.getOrNull()
        }

    /** Whichever decoder this uri answers to, cheapest first. */
    suspend fun preview(context: Context, uri: Uri): Bitmap? =
        thumbnail(context, uri, PREVIEW_EDGE) ?: bounded(context, uri, PREVIEW_EDGE)

    /** The map wants the exact frame, so the exact decoder goes first. */
    suspend fun map(context: Context, uri: Uri): Bitmap? =
        bounded(context, uri, MAP_EDGE) ?: thumbnail(context, uri, MAP_EDGE)

    /** The long-press overlay: the source while the finger is down, else nothing. */
    @Composable
    fun previewWhilePressed(context: Context, uri: Uri?, pressed: Boolean): Bitmap? =
        produceState<Bitmap?>(initialValue = null, pressed, uri) {
            value = if (pressed && uri != null) preview(context, uri) else null
        }.value

    /**
     * The crop map's frame: the source, whatever the current crop is. A format
     * with no platform decoder (RAW, on a device whose provider has no
     * thumbnail for it) falls back to [fallback] — the rendered frame — which
     * is exact while the crop is identity and approximate after that.
     */
    @Composable
    fun map(context: Context, uri: Uri?, fallback: Bitmap?): Bitmap? =
        produceState<Bitmap?>(initialValue = null, uri) {
            value = uri?.let { map(context, it) } ?: null
        }.value ?: fallback

    private suspend fun load(key: String, decode: suspend () -> Bitmap?): Bitmap? {
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { decode() } ?: return null
        // A frame too big for a quarter of the cache is returned but not kept:
        // caching it would evict everything else for one picture.
        if (bitmap.byteCount <= CACHE_BYTES / 4) cache.put(key, bitmap)
        return bitmap
    }

    private fun options(path: String, maxEdge: Int): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(path, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        return BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    }
}
