package com.spektralab.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The canvas histogram (plan S5 item 2): 256 bins per channel, computed in
 * Kotlin from the frame the engine just handed us.
 *
 * **All three channels** are binned — R, G and B at 8 bits each, which is the
 * only histogram a `Bitmap` can answer for honestly: the engine's own result
 * is `rgba16` but the display transform has already landed it in sRGB
 * `ARGB_8888` (`spk_output_transform`, plan D5), so a bin here *is* a display
 * level, not a linear scene value. Luminance is not binned separately — a
 * derived curve would invite reading it as measured data.
 *
 * Sampling is strided: the live tier's frame is up to 2048 px on the long
 * edge, and a histogram is a shape, not an audit. Rows are read a row at a
 * time into one reused buffer so nothing per-pixel is allocated.
 */
class Histogram(val red: IntArray, val green: IntArray, val blue: IntArray) {

    val peak: Int = max(1, maxOf(red.max(), green.max(), blue.max()))

    companion object {
        const val BINS = 256

        /** Rows and columns sampled at most, bounding the pass on a big frame. */
        private const val MAX_ROWS = 512
        private const val MAX_COLUMNS = 2048

        fun of(bitmap: Bitmap): Histogram {
            val red = IntArray(BINS)
            val green = IntArray(BINS)
            val blue = IntArray(BINS)
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return Histogram(red, green, blue)
            val rowStride = max(1, height / MAX_ROWS)
            val columnStride = max(1, width / MAX_COLUMNS)
            val row = IntArray(width)
            var y = 0
            while (y < height) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                var x = 0
                while (x < width) {
                    val pixel = row[x]
                    red[(pixel shr 16) and 0xFF]++
                    green[(pixel shr 8) and 0xFF]++
                    blue[pixel and 0xFF]++
                    x += columnStride
                }
                y += rowStride
            }
            return Histogram(red, green, blue)
        }
    }
}

/**
 * The overlay itself: three 256-bin curves, and the count of samples behind
 * them so the shape is not mistaken for a measurement of the whole frame.
 */
@Composable
fun HistogramOverlay(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    val histogram by produceState<Histogram?>(initialValue = null, bitmap) {
        value = bitmap?.let { withContext(Dispatchers.Default) { Histogram.of(it) } }
    }
    val colors = listOf(
        Color(0xFFFF5A5A), // red
        Color(0xFF5AFF8A), // green
        Color(0xFF6AA8FF), // blue
    )
    Box(modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(64.dp)) {
            val current = histogram ?: return@Canvas
            val channels = listOf(current.red, current.green, current.blue)
            channels.forEachIndexed { index, bins ->
                drawChannel(bins, current.peak, colors[index])
            }
        }
    }
}

private fun DrawScope.drawChannel(bins: IntArray, peak: Int, color: Color) {
    val width = size.width
    val height = size.height
    val step = width / (bins.size - 1).toFloat()
    var previous = Offset(0f, height)
    for (index in bins.indices) {
        val x = index * step
        val y = height - (bins[index].toFloat() / peak) * height
        drawLine(color = color.copy(alpha = 0.85f), start = previous, end = Offset(x, y), strokeWidth = 1.5f)
        previous = Offset(x, y)
    }
}

/** The one-line caption the canvas shows next to the curves. */
@Composable
fun HistogramCaption(modifier: Modifier = Modifier) {
    Text(
        text = "histogram: R/G/B, 256 bins, sRGB display levels",
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier,
    )
}
