package com.spektralab.app.ui

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.spektralab.app.camera.BlockReason
import com.spektralab.app.camera.CameraInventory
import com.spektralab.app.camera.CaptureConditions
import com.spektralab.app.camera.CaptureMode
import com.spektralab.app.camera.CaptureReadiness
import com.spektralab.app.camera.ExposureReadout
import com.spektralab.app.camera.LensPlan
import com.spektralab.app.camera.RawCameraSession
import com.spektralab.app.camera.ZoomStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The prototype's warm accent, so the camera and the editor read as one app. */
private val Accent = Color(0xFFE8A33D)
private val Scrim = Color(0xCC000000)

/**
 * C3 · 相机拍摄.
 *
 * The layout follows the Figma frame: film badge and flash on top, exposure
 * readout in the corners, an EV dial on the left, the lens bar and the shutter
 * at the bottom. Two things the prototype does not draw are load-bearing here:
 *
 * 1. **The shutter can be blocked, and says why.** [CaptureReadiness] decides,
 *    and the reason is printed where the readout normally goes. This is the
 *    design notes' §8.3 rule; a greyed button with no reason is the single
 *    cheapest way to make a camera feel broken.
 * 2. **The viewfinder is not the picture.** It is the camera's own preview,
 *    which has the vendor's tone curve in it. The developed result comes from
 *    the RAW. The badge in the corner says `RAW` or `便捷`, because a user
 *    comparing the two deserves to know which one is physics.
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val inventory = remember { CameraInventory(context).probe() }
    val plan: LensPlan = inventory.plan

    var selectedStop by remember(plan) { mutableStateOf(plan.main ?: plan.zoomStops.first()) }
    var openSession by remember { mutableStateOf<RawCameraSession?>(null) }
    var readout by remember { mutableStateOf(ExposureReadout.UNKNOWN) }
    var readiness by remember { mutableStateOf<CaptureReadiness>(CaptureReadiness.Blocked(BlockReason.NO_CAMERA)) }
    var busy by remember { mutableStateOf(false) }
    var errorLine by remember { mutableStateOf<String?>(null) }
    var previewSize by remember { mutableStateOf(0 to 0) }
    /** The lens id an open is in flight for; see the AndroidView update block. */
    var openingLensId by remember { mutableStateOf<String?>(null) }

    // Re-evaluate the shutter whenever the things it depends on move. Cheap
    // enough to do on every recomposition of these keys, and it keeps the rule
    // in one place instead of scattered through the button's enabled =.
    LaunchedEffect(hasPermission, inventory, selectedStop, busy) {
        readiness = if (!hasPermission) {
            CaptureReadiness.Blocked(BlockReason.PERMISSION)
        } else {
            CaptureReadiness.evaluate(
                CaptureConditions(
                    permissionGranted = true,
                    cameraCount = inventory.lenses.size,
                    lensHasRaw = selectedStop.lens?.hasRaw == true,
                    busy = busy,
                    freeBytes = captureDirectory.usableSpace,
                    thermalStatus = thermalStatus(context),
                    thermalHeadroom = thermalHeadroom(context),
                    exceedsMemoryBudget = false,
                ),
            )
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- viewfinder ----------------------------------------------------
        if (hasPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = Unit
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                                previewSize = w to hh
                            }
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                openSession?.close()
                                openSession = null
                            }
                        })
                    }
                },
                update = { view ->
                    val lens = selectedStop.lens
                    // `openSession` is null for the whole of an open, so without
                    // an explicit token every recomposition would start another
                    // one — and each open holds the camera. The token is the lens
                    // id being opened, cleared once the session lands or fails.
                    val target = lens?.cameraId
                    if (target != null && target != openingLensId && target != openSession?.lens?.cameraId) {
                        openSession?.close()
                        openSession = null
                        openingLensId = target
                        val surface = view.holder.surface
                        if (surface.isValid) {
                            scope.launch {
                                try {
                                    val session = withContext(Dispatchers.IO) { RawCameraSession(context, lens) }
                                    session.open(surface)
                                    openSession = session
                                } catch (e: Exception) {
                                    errorLine = e.message ?: "相机打开失败"
                                } finally {
                                    openingLensId = null
                                }
                            }
                        } else {
                            openingLensId = null
                        }
                    }
                },
            )
            CompositionGrid()
        }

        // ---- chrome --------------------------------------------------------
        Column(Modifier.fillMaxSize()) {
            CameraTopBar(
                hasPermission = hasPermission,
                filmLabel = filmLabel,
                onClose = onClose,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = listOf(readout.isoLabel, "AUTO").filter { it.isNotEmpty() }.joinToString(" · "),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                )
                Text(
                    text = listOf(readout.shutterLabel, readout.apertureLabel)
                        .filter { it.isNotEmpty() }.joinToString(" "),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(8.dp))

            Box(Modifier.weight(1f)) {
                EvDial(
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                    value = 0.0,
                    onValue = { /* AE compensation lands with the manual-exposure pass */ },
                )
            }

            // The readiness line sits where the eye already is: above the
            // shutter, in the readout's type.
            val blocked = readiness as? CaptureReadiness.Blocked
            val mode = (readiness as? CaptureReadiness.Ready)?.mode
            // A write error outranks the readiness reason: the user just pressed
            // the shutter and needs to know *that* failed, not why the next one
            // might be refused.
            val notice = errorLine ?: blocked?.reason?.label()
            if (notice != null) {
                Text(
                    text = notice,
                    color = Color(0xFFFFB4A2),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            LensBar(
                stops = plan.zoomStops,
                selected = selectedStop,
                onSelect = { selectedStop = it },
            )

            BottomBar(
                shutterEnabled = mode != null && !busy,
                convenience = mode == CaptureMode.CONVENIENCE,
                frameIndex = frameIndex,
                capacity = frameCapacity,
                filmLabel = filmLabel,
                onShutter = {
                    val session = openSession ?: return@BottomBar
                    busy = true
                    errorLine = null
                    scope.launch {
                        val dest = File(captureDirectory, "SPK_${System.currentTimeMillis()}.dng")
                        try {
                            val bytes = withContext(Dispatchers.IO) { session.capture(dest) }
                            if (bytes > 0) {
                                onCaptured(dest, readout.summary())
                            } else {
                                errorLine = "写入失败"
                            }
                        } catch (e: Exception) {
                            errorLine = e.message ?: "拍摄失败"
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// pieces
// ---------------------------------------------------------------------------

@Composable
private fun CameraTopBar(hasPermission: Boolean, filmLabel: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        CircleButton(onClick = onClose) { Text("✕", color = Color.White, fontSize = 18.sp) }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = Scrim, shape = RoundedCornerShape(14.dp)) {
                Text(
                    text = filmLabel,
                    color = Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (!hasPermission) {
                Text(
                    "需要相机权限",
                    color = Color(0xFFFFB4A2),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        CircleButton(onClick = {}) { Text("⚡", color = Color.White, fontSize = 16.sp) }
    }
}

@Composable
private fun CircleButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        color = Scrim,
        shape = CircleShape,
        modifier = Modifier.size(40.dp),
        onClick = onClick,
    ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() } }
}

/** Rule-of-thirds grid — the prototype's only viewfinder aid. */
@Composable
private fun CompositionGrid() {
    Canvas(Modifier.fillMaxSize()) {
        val line = Color.White.copy(alpha = 0.18f)
        val stroke = 1.dp.toPx()
        for (i in 1..2) {
            drawLine(line, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), stroke)
            drawLine(line, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), stroke)
        }
    }
}

/**
 * The vertical EV dial from the prototype.
 *
 * Presentation only for now: `CONTROL_AE_EXPOSURE_COMPENSATION` is wired in the
 * capture pass that also handles manual ISO/shutter, because a dial that moves
 * without changing the picture is worse than one that is not there yet.
 */
@Composable
private fun EvDial(modifier: Modifier = Modifier, value: Double, onValue: (Double) -> Unit) {
    Surface(color = Scrim, shape = RoundedCornerShape(20.dp), modifier = modifier.width(48.dp)) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("EV", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
            Text(
                text = if (value == 0.0) "0" else String.format("%+.1f", value),
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Box(
                Modifier.padding(top = 10.dp).width(2.dp).height(120.dp)
                    .background(Color.White.copy(alpha = 0.25f)),
            )
        }
    }
}

@Composable
private fun LensBar(stops: List<ZoomStop>, selected: ZoomStop, onSelect: (ZoomStop) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(color = Scrim, shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                stops.forEach { stop ->
                    val enabled = stop.lens != null
                    val isSelected = stop.ratio == selected.ratio
                    Surface(
                        color = if (isSelected) Accent else Color.Transparent,
                        shape = RoundedCornerShape(16.dp),
                        enabled = enabled,
                        onClick = { onSelect(stop) },
                    ) {
                        Text(
                            text = stop.label(),
                            color = when {
                                isSelected -> Color.Black
                                enabled -> Color.White
                                else -> Color.White.copy(alpha = 0.3f)
                            },
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    shutterEnabled: Boolean,
    convenience: Boolean,
    frameIndex: Int,
    capacity: Int,
    filmLabel: String,
    onShutter: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (convenience) "JPEG · 便捷模式" else "$filmLabel · RAW",
            color = if (convenience) Color(0xFFFFC46B) else Color.White.copy(alpha = 0.75f),
            fontSize = 11.sp,
        )

        ShutterButton(enabled = shutterEnabled, onClick = onShutter)

        Text(
            text = "${frameIndex + 1} / $capacity",
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
        modifier = Modifier.size(72.dp),
        enabled = enabled,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(58.dp).background(Accent.copy(alpha = if (enabled) 0.9f else 0.3f), CircleShape))
        }
    }
}

// ---------------------------------------------------------------------------
// labels and platform reads
// ---------------------------------------------------------------------------

/** `0.7` / `1×` / `2×` — the way the prototype prints them. */
private fun ZoomStop.label(): String = when {
    ratio < 0.95f -> String.format("%.1f", ratio)
    ratio < 1.05f -> "1×"
    else -> String.format("%.0f×", ratio).let { if (ratio % 1f == 0f) it else String.format("%.1f×", ratio) }
}

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

private fun thermalStatus(context: Context): Int {
    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    return pm.currentThermalStatus
}

/**
 * `getThermalHeadroom` is API 30+ and returns NaN on devices without the
 * sensor. The readiness rule treats NaN as "unknown", not as "cold" — see
 * [CaptureReadiness.evaluate].
 */
private fun thermalHeadroom(context: Context): Float {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return Float.NaN
    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    return try {
        pm.getThermalHeadroom(10)
    } catch (e: Exception) {
        Float.NaN
    }
}
