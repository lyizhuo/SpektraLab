package com.spektralab.app.ui.screens.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.spektralab.app.camera.BlockReason
import com.spektralab.app.camera.CameraInventory
import com.spektralab.app.camera.CaptureConditions
import com.spektralab.app.camera.CaptureMode
import com.spektralab.app.camera.CaptureReadiness
import com.spektralab.app.camera.ExposureReadout
import com.spektralab.app.camera.LensPlan
import com.spektralab.app.camera.RawCameraSession
import com.spektralab.app.camera.ZoomStop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * C3 — the camera.
 *
 * **Layout is Material 3 and Compose primitives, not coordinates.** The preview
 * fills the window; the chrome is a `Column` with a `weight(1f)` spacer between
 * the readouts and the controls, and the controls sit inside
 * `WindowInsets.safeDrawing`. Nothing is placed at an absolute y, so the screen
 * composes itself for any aspect ratio: a 4:3 sensor on a 21:9 phone crops the
 * preview and still leaves the shutter above the gesture bar, and a 16:9 tablet
 * gets a taller preview rather than a gap.
 *
 * **The viewfinder is the camera.** This file lost that once — the layout was
 * rewritten and the `SurfaceView` plus the session-open block went with it, so
 * the screen showed black on the MI 8 while every unit stayed green. The
 * session harness below is restored and its token comment is load-bearing.
 *
 * Three behaviours the design does not draw, kept because their absence feels
 * like a broken camera:
 *  1. the shutter can be blocked **and says why** in words;
 *  2. the roll line names the film and the frame, so the viewfinder's vendor
 *     tone curve is never mistaken for the picture;
 *  3. a write failure outranks the readiness reason.
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
    val stops = plan.zoomStops

    var selectedStop by remember(plan) { mutableStateOf(plan.main ?: stops.first()) }
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

    LaunchedEffect(granted, inventory, selectedStop, busy) {
        readiness = if (!granted) {
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

    val blocked = readiness as? CaptureReadiness.Blocked
    val mode = (readiness as? CaptureReadiness.Ready)?.mode
    val notice = errorLine ?: blocked?.reason?.label() ?: if (!granted) "需要相机权限" else null

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            Viewfinder(
                openSession = openSession,
                selectedStop = selectedStop,
                openingLensId = openingLensId,
                onOpening = { openingLensId = it },
                onOpened = { openSession = it },
                onClosed = { openSession = null },
                onError = { errorLine = it },
                scope = scope,
                context = context,
            )
        }

        // Scrims, so white type survives a sunlit scene without a solid bar.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xB3000000),
                        0.22f to Color.Transparent,
                        0.70f to Color.Transparent,
                        1f to Color(0xD9000000),
                    ),
                ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp),
        ) {
            TopControls(filmLabel = filmLabel, onClose = onClose)
            ReadoutRow(readout = readout)
            CompositionGuides(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
            )
            if (notice != null) {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .testTag("camera-notice"),
                )
            }
            LensPicker(
                stops = stops,
                selected = selectedStop,
                onSelect = { selectedStop = it },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(10.dp))
            RollLine(filmLabel = filmLabel, frameIndex = frameIndex, frameCapacity = frameCapacity, mode = mode)
            Spacer(Modifier.height(12.dp))
            BottomControls(
                count = frameIndex,
                enabled = mode != null && !busy,
                onCapture = {
                    val session = openSession ?: return@BottomControls
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
                onFlip = { /* the plan's second lens, not a mirror flip */ },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * The `SurfaceView` and the session that fills it, centre-cropped.
 *
 * The token is not decoration: `openSession` is null for the whole of an open,
 * so without it every recomposition starts another open — and each one holds
 * the camera.
 */
@Composable
private fun Viewfinder(
    openSession: RawCameraSession?,
    selectedStop: ZoomStop,
    openingLensId: String?,
    onOpening: (String?) -> Unit,
    onOpened: (RawCameraSession) -> Unit,
    onClosed: () -> Unit,
    onError: (String) -> Unit,
    scope: CoroutineScope,
    context: Context,
) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        // The surface is not valid on the first composition, and `update` is the
        // only place the open is attempted: without a signal from the holder the
        // screen stayed black with no error at all. This token is that signal.
        var surfaceToken by remember { mutableIntStateOf(0) }
        // Read in the composition, not inside `update`: a state read inside the
        // AndroidView callback does not subscribe, so the open was never retried
        // after the surface appeared and the preview stayed black.
        val surfaceGeneration = surfaceToken
        // The viewfinder's buffer is pinned to a size the camera actually
        // produces, and the view takes that same aspect. Two independent facts
        // follow, and both matter for a product whose whole claim is colour and
        // optics:
        //
        //  * **No stretch, ever.** The camera scales its output into whatever
        //    buffer the surface has. Leaving that buffer to the layout means a
        //    4:3 sensor is stretched over a 19.5:9 screen — the picture is
        //    geometrically wrong before any colour happens. `setFixedSize` to a
        //    real preview size, and a view with that same aspect, makes the map
        //    1:1 and the overflow a crop.
        //  * **Centre-crop, not letterbox.** A viewfinder fills the window; the
        //    part the sensor did not see at the edges is simply not shown, which
        //    is what every camera app does and what the frame's full-bleed
        //    picture implies.
        // The frame the user composes must be the frame the DNG holds. The
        // viewfinder therefore takes the *capture* aspect and is letterboxed
        // inside the space the chrome leaves — no crop (which would hide a sixth
        // of a 4:3 sensor on a 19.5:9 screen) and no stretch (which is not a
        // camera, it is a bug).
        val raw = remember(selectedStop) { RawCameraSession.rawSizeFor(context, selectedStop.lens) }
        val buffer = remember(selectedStop) {
            RawCameraSession.previewSizeFor(context, selectedStop.lens)
        }
        val bufferAspect = raw.width.toFloat() / raw.height.toFloat()
        // Fit inside, never fill: the sensor's whole field is visible.
        val preview = Modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.Center)
            .aspectRatio(bufferAspect, matchHeightConstraintsFirst = true)
        AndroidView(
            modifier = preview
                .align(Alignment.Center)
                .clipToBounds()
                .testTag("camera-preview"),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.setFixedSize(buffer.width, buffer.height)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                            surfaceToken++
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) = onClosed()
                    })
                }
            },
            update = { view ->
                if (surfaceGeneration < 0) return@AndroidView   // re-run once the holder has a surface
                val lens = selectedStop.lens
                val target = lens?.cameraId
                if (target != null && target != openingLensId && target != openSession?.lens?.cameraId) {
                    onClosed()
                    onOpening(target)
                    val surface = view.holder.surface
                    if (surface.isValid) {
                        scope.launch {
                            try {
                                val session = withContext(Dispatchers.IO) { RawCameraSession(context, lens) }
                                session.open(surface)
                                onOpened(session)
                            } catch (failure: Exception) {
                                onError(failure.message ?: "相机打开失败")
                            } finally {
                                onOpening(null)
                            }
                        }
                    } else {
                        onOpening(null)
                    }
                }
            },
        )
    }
}

@Composable
private fun TopControls(filmLabel: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScrimIconButton(onClick = onClose, tag = "camera-close") {
            Icon(Icons.Outlined.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = Color(0x61000000),
            modifier = Modifier.weight(1f).testTag("camera-film"),
        ) {
            Column(
                Modifier.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = filmLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "135 · 36 EXP",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        ScrimIconButton(onClick = {}, tag = "camera-flash") {
            Icon(Icons.Outlined.FlashOff, contentDescription = "闪光灯", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ReadoutRow(readout: ExposureReadout) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = listOf(readout.isoLabel, "AUTO").filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = Color.White.copy(alpha = 0.9f),
        )
        Text(
            text = listOf(readout.shutterLabel, readout.apertureLabel)
                .filter { it.isNotEmpty() }
                .joinToString(" "),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = Color.White.copy(alpha = 0.9f),
        )
    }
}

@Composable
private fun RollLine(filmLabel: String, frameIndex: Int, frameCapacity: Int, mode: CaptureMode?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = "$filmLabel · 135",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "$frameIndex / $frameCapacity",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = Color.White,
            modifier = Modifier.testTag("camera-counter"),
        )
    }
}

@Composable
private fun LensPicker(
    stops: List<ZoomStop>,
    selected: ZoomStop,
    onSelect: (ZoomStop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = stops.take(3)
    if (shown.isEmpty()) return
    val index = shown.indexOfFirst { it.ratio == selected.ratio }.coerceAtLeast(0)
    SingleChoiceSegmentedButtonRow(modifier.width(220.dp)) {
        shown.forEachIndexed { i, stop ->
            SegmentedButton(
                selected = i == index,
                onClick = { onSelect(stop) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = shown.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    inactiveContainerColor = Color(0x57000000),
                    inactiveContentColor = Color.White,
                ),
                modifier = Modifier.testTag("lens-${stop.ratio}"),
                label = {
                    Text(
                        text = zoomLabel(stop.ratio),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
            )
        }
    }
}

@Composable
private fun BottomControls(
    count: Int,
    enabled: Boolean,
    onCapture: () -> Unit,
    onFlip: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF241D18),
            modifier = Modifier.size(54.dp).testTag("camera-thumb"),
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(6.dp),
                )
            }
        }

        FilledIconButton(
            onClick = onCapture,
            enabled = enabled,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color(0xFFF5F1EA),
                contentColor = Color(0xFF100E0C),
                disabledContainerColor = Color(0x59F5F1EA),
                disabledContentColor = Color(0x8A100E0C),
            ),
            modifier = Modifier.size(76.dp).testTag("camera-shutter"),
        ) {
            Box(
                Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(if (enabled) Color(0xFFF5F1EA) else Color(0x8AF5F1EA)),
            )
        }

        ScrimIconButton(onClick = onFlip, tag = "camera-flip") {
            Icon(
                Icons.Outlined.FlipCameraAndroid,
                contentDescription = "切换镜头",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ScrimIconButton(onClick: () -> Unit, tag: String, content: @Composable () -> Unit) {
    Surface(shape = CircleShape, color = Color(0x57000000), modifier = Modifier.size(44.dp)) {
        IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) { content() }
    }
}

/** Thirds, drawn in the band between the readouts and the controls. */
@Composable
private fun CompositionGuides(modifier: Modifier = Modifier) {
    val line = Color(0x29FFFFFF)
    Box(modifier) {
        Row(Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(line))
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(line))
            Spacer(Modifier.weight(1f))
        }
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.height(1.dp).fillMaxWidth().background(line))
            Spacer(Modifier.weight(1f))
            Box(Modifier.height(1.dp).fillMaxWidth().background(line))
            Spacer(Modifier.weight(1f))
        }
    }
}

private fun zoomLabel(ratio: Float): String = when {
    ratio < 0.95f -> String.format("%.1f×", ratio)
    ratio < 1.05f -> "1×"
    ratio % 1f == 0f -> String.format("%.0f×", ratio)
    else -> String.format("%.1f×", ratio)
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
    val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    return power.currentThermalStatus
}

/** `getThermalHeadroom` is API 30+, and NaN means "unknown", not "cold". */
private fun thermalHeadroom(context: Context): Float {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return Float.NaN
    val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    return try {
        power.getThermalHeadroom(10)
    } catch (failure: Exception) {
        Float.NaN
    }
}
