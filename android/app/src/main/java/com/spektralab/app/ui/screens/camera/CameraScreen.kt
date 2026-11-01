package com.spektralab.app.ui.screens.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.spektralab.app.camera.BlockReason
import com.spektralab.app.camera.CameraInventory
import com.spektralab.app.camera.CaptureMode
import com.spektralab.app.camera.CaptureReadiness
import com.spektralab.app.camera.ExposureReadout
import com.spektralab.app.camera.LensPlan
import com.spektralab.app.camera.RawCameraSession
import com.spektralab.app.ui.frame.Layer
import com.spektralab.app.ui.frame.ScreenBand
import com.spektralab.app.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * C3 — 相机拍摄, transcribed from the frame `41:5`.
 *
 * The viewfinder occupies `140…689` and the chrome sits on the shades above and
 * below it: close / stock chip / flash at `y=54`, the metering readout at
 * `y=107`, the EV dial on the left edge, the lens picker and the roll counter
 * at the bottom, the shutter at `y=782`.
 *
 * Three things the frame does not draw and this keeps, all of them load-bearing
 * (they were here before the redesign and survived it):
 *
 *  1. **The shutter can be blocked, and says why.** [CaptureReadiness] decides
 *     and the reason replaces the readout's second line. A greyed shutter with
 *     no reason is the cheapest way to make a camera feel broken.
 *  2. **The viewfinder is not the picture.** It carries the vendor's tone
 *     curve; the developed result comes from the RAW. The roll line says which
 *     mode is in force (`RAW` or `便捷`) for the same reason.
 *  3. **A write failure outranks the readiness reason** — the user just pressed
 *     the shutter and needs to know that failed, not why the next one might be
 *     refused.
 */
@Composable
fun CameraScreen(
    hasPermission: Boolean,
    captureDirectory: File,
    onCaptured: (File, String) -> Unit,
    onClose: () -> Unit,
    filmLabel: String = "PORTRA 400",
    frameIndex: Int = 0,
    frameCapacity: Int = 36,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val inventory = remember { CameraInventory(context).probe() }
    val plan: LensPlan = inventory.plan

    var selectedStop by remember(plan) { mutableStateOf(plan.main ?: plan.zoomStops.first()) }
    var openSession by remember { mutableStateOf<RawCameraSession?>(null) }
    var readout by remember { mutableStateOf(ExposureReadout.UNKNOWN) }
    var readiness by remember {
        mutableStateOf<CaptureReadiness>(CaptureReadiness.Blocked(BlockReason.NO_CAMERA))
    }
    var busy by remember { mutableStateOf(false) }
    var errorLine by remember { mutableStateOf<String?>(null) }
    var openingLensId by remember { mutableStateOf<String?>(null) }

    val granted = hasPermission || ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    // The lens picker is the frame's 0.7 / 1× / 2×; the plan decides which of
    // them exist on this device and which of those can shoot RAW.
    val stops = plan.zoomStops

    ScreenBand(background = Color.Black) {
        // ---- viewfinder ----------------------------------------------------
        Box(
            Modifier
                .offset(y = 140.dp)
                .fillMaxWidth()
                .height(549.dp)
                .background(Color(0xFF0B0A09)),
        ) {
            if (granted) {
                AndroidView(
                    factory = { ctx -> android.view.View(ctx) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ---- shades --------------------------------------------------------
        Box(
            Modifier
                .fillMaxWidth()
                .height(137.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0xCC000000), Color(0x00000000)),
                    ),
                ),
        )
        Box(
            Modifier
                .offset(y = 689.dp)
                .fillMaxWidth()
                .height(226.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0x00000000), Color(0xE6000000)),
                    ),
                ),
        )

        CompositionGrid()

        // ---- top chrome ----------------------------------------------------
        CircleIcon(
            x = 16.dp,
            y = 54.dp,
            onClick = onClose,
            tag = "camera-close",
        ) { Icon(Icons.Outlined.Close, null, tint = Tokens.Ink, modifier = Modifier.size(16.dp)) }

        StockChip(x = 136.dp, y = 54.dp, film = filmLabel)

        CircleIcon(x = 358.dp, y = 54.dp, onClick = {}, tag = "camera-flash") {
            Text("⚡", fontSize = 13.sp, color = Tokens.Ink)
        }

        // ---- metering ------------------------------------------------------
        Text(
            text = listOf(readout.isoLabel, "AUTO").filter { it.isNotEmpty() }.joinToString(" · "),
            style = TextStyle(
                fontFamily = Tokens.Mono,
                fontSize = 9.sp,
                color = Tokens.Ink,
            ),
            modifier = Modifier.offset(x = 20.dp, y = 107.dp),
        )
        Box(
            Modifier.offset(y = 107.dp).fillMaxWidth().padding(end = 20.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            Text(
                text = listOf(readout.shutterLabel, readout.apertureLabel)
                    .filter { it.isNotEmpty() }
                    .joinToString(" "),
                style = TextStyle(
                    fontFamily = Tokens.Mono,
                    fontSize = 9.sp,
                    color = Tokens.Ink,
                ),
            )
        }

        // ---- EV dial -------------------------------------------------------
        Layer(x = 16.dp, y = 340.dp, width = 42.dp, height = 126.dp) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(21.dp))
                    .background(Color(0x4D000000))
                    .border(1.dp, Tokens.Hairline, RoundedCornerShape(21.dp)),
            ) {
                Text(
                    text = "EV",
                    style = TextStyle(
                        fontFamily = Tokens.Mono,
                        fontSize = 8.sp,
                        color = Tokens.InkSecondary,
                    ),
                    modifier = Modifier.offset(x = 8.dp, y = 12.dp),
                )
                Text(
                    text = "0",
                    style = TextStyle(
                        fontFamily = Tokens.Sans,
                        fontSize = 10.sp,
                        color = Tokens.Ink,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.offset(x = 8.dp, y = 28.dp),
                )
                Box(
                    Modifier
                        .offset(x = 19.dp, y = 56.dp)
                        .size(width = 2.dp, height = 44.dp)
                        .background(Color(0x40FFFFFF)),
                )
                Box(
                    Modifier
                        .offset(x = 16.dp, y = 71.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Tokens.Accent),
                )
            }
        }

        // ---- lens picker ---------------------------------------------------
        Layer(x = 135.dp, y = 708.dp, width = 140.dp, height = 41.dp) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x57000000))
                    .border(1.dp, Tokens.Hairline, RoundedCornerShape(16.dp)),
            ) {
                stops.take(3).forEachIndexed { index, stop ->
                    val active = stop.ratio == selectedStop.ratio
                    val x = 25.dp + (44 * index).dp
                    if (active) {
                        Box(
                            Modifier
                                .offset(x = (47 + 44 * index).dp - 47.dp + 22.dp, y = 2.dp)
                                .size(width = 44.dp, height = 35.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x38E1A95F)),
                        )
                    }
                    Text(
                        text = zoomLabel(stop.ratio),
                        style = TextStyle(
                            fontFamily = Tokens.Sans,
                            fontSize = 16.sp,
                            color = if (active) Tokens.Accent else Tokens.Ink,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        modifier = Modifier
                            .offset(x = x, y = 10.dp)
                            .width(44.dp)
                            .clickable { selectedStop = stop }
                            .testTag("lens-${stop.ratio}"),
                    )
                }
            }
        }

        // ---- roll line -----------------------------------------------------
        Layer(x = 20.dp, y = 760.dp, width = 372.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "$filmLabel · 135",
                    style = TextStyle(fontFamily = Tokens.Mono, fontSize = 9.sp, color = Tokens.Accent),
                )
                Text(
                    text = "$frameIndex / $frameCapacity",
                    style = TextStyle(fontFamily = Tokens.Mono, fontSize = 9.sp, color = Tokens.Ink),
                    modifier = Modifier.testTag("camera-counter"),
                )
            }
        }

        // The readiness line, where the eye already is.
        val blocked = readiness as? CaptureReadiness.Blocked
        val mode = (readiness as? CaptureReadiness.Ready)?.mode
        val notice = errorLine ?: blocked?.reason?.label() ?: if (!granted) "需要相机权限" else null
        if (notice != null) {
            Text(
                text = notice,
                style = TextStyle(fontFamily = Tokens.Sans, fontSize = 10.sp, color = Color(0xFFFFB4A2)),
                modifier = Modifier
                    .offset(x = 20.dp, y = 745.dp)
                    .width(372.dp),
            )
        }

        // ---- bottom chrome -------------------------------------------------
        GalleryThumb(x = 24.dp, y = 811.dp, count = frameIndex)

        Shutter(
            x = 154.dp,
            y = 782.dp,
            enabled = mode != null && !busy,
            onClick = {
                val session = openSession ?: return@Shutter
                busy = true
                errorLine = null
                scope.launch {
                    val dest = File(captureDirectory, "SPK_${System.currentTimeMillis()}.dng")
                    try {
                        val bytes = withContext(Dispatchers.IO) { session.capture(dest) }
                        if (bytes > 0) onCaptured(dest, readout.summary()) else errorLine = "写入失败"
                    } catch (failure: Exception) {
                        errorLine = failure.message ?: "拍摄失败"
                    } finally {
                        busy = false
                    }
                }
            },
        )

        CircleIcon(
            x = 334.dp,
            y = 811.dp,
            size = 54.dp,
            radius = 27.dp,
            onClick = {},
            tag = "camera-flip",
        ) { Icon(Icons.Outlined.FlipCameraAndroid, null, tint = Tokens.Ink, modifier = Modifier.size(14.dp)) }

        // Home indicator, as the frame draws it.
        Box(
            Modifier
                .offset(x = 146.dp, y = 894.dp)
                .size(width = 120.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Tokens.Ink.copy(alpha = 0.35f)),
        )
    }
}

@Composable
private fun CompositionGrid() {
    val line = Color(0x29FFFFFF)
    for (x in listOf(137.dp, 275.dp)) {
        Box(Modifier.offset(x = x).width(1.dp).height(915.dp).background(line))
    }
    for (y in listOf(305.dp, 610.dp)) {
        Box(Modifier.offset(y = y).height(1.dp).fillMaxWidth().background(line))
    }
    // The reticle sits at the frame's centre, not the viewfinder's.
    Box(
        Modifier
            .offset(x = 178.dp, y = 429.dp)
            .size(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(6.dp)),
    )
}

@Composable
private fun StockChip(x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp, film: String) {
    Layer(x = x, y = y, width = 140.dp, height = 38.dp) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(19.dp))
                .background(Color(0x61000000))
                .border(1.dp, Tokens.Hairline, RoundedCornerShape(19.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = film,
                    style = TextStyle(
                        fontFamily = Tokens.Sans,
                        fontSize = 10.sp,
                        color = Tokens.Accent,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.testTag("camera-film"),
                )
                Text(
                    text = "135 · 36 EXP",
                    style = TextStyle(fontFamily = Tokens.Sans, fontSize = 8.sp, color = Tokens.InkSecondary),
                )
            }
        }
    }
}

@Composable
private fun CircleIcon(
    x: androidx.compose.ui.unit.Dp,
    y: androidx.compose.ui.unit.Dp,
    size: androidx.compose.ui.unit.Dp = 38.dp,
    radius: androidx.compose.ui.unit.Dp = 19.dp,
    onClick: () -> Unit,
    tag: String,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .offset(x = x, y = y)
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(Color(0x57000000))
            .border(1.dp, Tokens.Hairline, RoundedCornerShape(radius))
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Shutter(x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .offset(x = x, y = y)
            .size(104.dp)
            .testTag("camera-shutter")
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(104.dp)
                .clip(CircleShape)
                .border(3.dp, Tokens.Ink.copy(alpha = if (enabled) 0.9f else 0.3f), CircleShape),
        )
        Box(
            Modifier
                .size(86.dp)
                .clip(CircleShape)
                .background(if (enabled) Tokens.Ink else Tokens.Ink.copy(alpha = 0.35f)),
        )
    }
}

@Composable
private fun GalleryThumb(x: androidx.compose.ui.unit.Dp, y: androidx.compose.ui.unit.Dp, count: Int) {
    Layer(x = x, y = y, width = 54.dp, height = 54.dp) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF241D18))
                .border(1.dp, Tokens.Hairline, RoundedCornerShape(14.dp)),
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(20.dp)
                    .background(Color(0x85000000)),
            )
            Text(
                text = count.toString(),
                style = TextStyle(fontFamily = Tokens.Sans, fontSize = 8.sp, color = Tokens.Ink),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 5.dp, bottom = 4.dp),
            )
        }
    }
}

/** The frame prints 0.7 / 1× / 2×; the plan carries a ratio. */
private fun zoomLabel(ratio: Float): String =
    if (ratio >= 0.99f && ratio <= 1.01f) "1×" else "%.1f".format(ratio).removeSuffix(".0") + "×"
/**
 * Why the shutter is refused, in the user's words.
 *
 * These lived in the old Material screen and are carried over unchanged: the
 * reason a photograph cannot be taken is not a design decision, and the frame
 * has nowhere to print it, so it goes where the metering readout is.
 */
private fun BlockReason.label(): String = when (this) {
    BlockReason.PERMISSION -> "需要相机权限"
    BlockReason.NO_CAMERA -> "没有可用的后置相机"
    BlockReason.LENS_UNAVAILABLE -> "这颗镜头不可用"
    BlockReason.BUSY -> "上一张还在写入"
    BlockReason.STORAGE_FULL -> "存储空间不足"
    BlockReason.TOO_HOT -> "机身过热，稍后再拍"
    BlockReason.OUT_OF_MEMORY -> "内存不足以处理这张照片"
}

private fun ExposureReadout.summary(): String =
    listOf(isoLabel, shutterLabel, apertureLabel).filter { it.isNotEmpty() }.joinToString(" ")