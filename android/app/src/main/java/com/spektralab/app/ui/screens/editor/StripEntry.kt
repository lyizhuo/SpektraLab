package com.spektralab.app.ui.screens.editor

import android.content.Context
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One frame on the strip.
 *
 * It lived in the old `ui/FilmStrip.kt`, which the redesign deleted; the model
 * belongs with the strip that draws it. Thumbnails come from the platform's
 * thumbnail service rather than a full decode — a strip of five 62 dp squares
 * must not cost five 24 MP decodes.
 */
data class StripEntry(val uri: Uri, val name: String)

@Composable
fun StripThumbnail(entry: StripEntry, modifier: Modifier = Modifier) {
    val context: Context = LocalContext.current.applicationContext
    val bitmap = produceState<ImageBitmap?>(null, entry.uri.toString()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver
                    .loadThumbnail(entry.uri, Size(192, 192), null)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    val image = bitmap.value
    if (image == null) {
        androidx.compose.foundation.layout.Box(modifier.background(Color(0xFF241D18)))
    } else {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize(),
        )
    }
}
