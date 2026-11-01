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
import androidx.compose.material.icons.outlined.FlashOn
import kotlinx.coroutines.delay
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
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import com.spektralab.app.R
import com.spektralab.app.ui.theme.Tokens
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.Offset

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
    onOpenLast: () -> Unit = {},
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
    var torchOn by remember { mutableStateOf(false) }

    /** C3's EV reel: the index the camera is set to, and what a step is worth. */
    var evIndex by remember { mutableStateOf(0) }
    val evRange = remember(inventory, selectedStop) {
        RawCameraSession.evIndexRange(context, selectedStop.lens)
    }
    val evStep = remember(inventory, selectedStop) {
        RawCameraSession.evStep(context, selectedStop.lens)
    }

    // The readout lives in the session and changes on every frame; the
    // screen has to ask for it. A state read inside `AndroidView.update`
    // does not subscribe, so this polls the field the callback writes.
    LaunchedEffect(openSession) {
        while (true) {
            openSession?.let { readout = it.readout }
            delay(400)
        }
    }
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
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            TopBar(
                filmLabel = filmLabel,
                onClose = onClose,
                torchOn = torchOn,
                onToggleTorch = {
                    torchOn = !torchOn
                    openSession?.setTorch(torchOn)
                },
            )

            // The viewfinder's furniture lives on the picture, not in the
            // column: the grid, the reticle and the EV reel belong to the frame
            // that is being composed, and holding them in a nested layout kept
            // them inside the letterbox instead of over the sensor's image.
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CompositionGuides(Modifier.fillMaxSize())
                FocusReticle(Modifier.align(Alignment.Center))
                EvDial(
                    ev = evIndex * evStep,
                    range = evRange.first * evStep..evRange.last * evStep,
                    onChange = { ev ->
                        val steps = (ev / evStep).roundToInt().coerceIn(evRange.first, evRange.last)
                        evIndex = steps
                        openSession?.setExposureCompensation(steps)
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp, top = 40.dp),
                )
                if (notice != null) {
                    Text(
                        text = notice,
                        style = Tokens.Type.Caption,
                        color = Tokens.Accent,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp)
                            .testTag("camera-notice"),
                    )
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.35f to Color(0xB3000000),
                            1f to Color(0xE6000000),
                        ),
                    )
                    .padding(horizontal = 16.dp),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LensPicker(
                        stops = stops,
                        selected = selectedStop,
                        onSelect = { selectedStop = it },
                    )
                }
                Spacer(Modifier.height(14.dp))
                ExposureLine(readout = readout)
                Spacer(Modifier.height(10.dp))
                RollLine(filmLabel = filmLabel, frameIndex = frameIndex, frameCapacity = frameCapacity, mode = mode)
                Spacer(Modifier.height(14.dp))
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
                    onOpenLast = onOpenLast,
                    onFlip = { /* the plan's second lens, not a mirror flip */ },
                )
                Spacer(Modifier.height(10.dp))
            }
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
        // A camera buffer is always landscape; a portrait phone shows it
        // turned, so the view takes the inverse. Using the buffer
        // aspect directly drew a landscape letterbox in the middle of a
        // portrait screen — which is not how any camera previews.
        val portrait = LocalConfiguration.current.orientation ==
            Configuration.ORIENTATION_PORTRAIT
        val bufferAspect = if (portrait) {
            buffer.height.toFloat() / buffer.width.toFloat()
        } else {
            buffer.width.toFloat() / buffer.height.toFloat()
        }
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
private fun TopBar(
    filmLabel: String,
    onClose: () -> Unit,
    torchOn: Boolean,
    onToggleTorch: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundChromeButton(onClick = onClose, tag = "camera-close") {
            Icon(
                painter = painterResource(R.drawable.ic_x),
                contentDescription = "关闭",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        // The stock, as a pill rather than a heading: C3's own shape, 140 × 38
        // with the amber border and the two-line label inside it.
        Column(
            Modifier
                .width(140.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(Color(0x4D000000))
                .border(1.dp, Tokens.AccentBorder, RoundedCornerShape(19.dp))
                .testTag("camera-film"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(filmLabel, style = Tokens.Type.PillLabel.copy(fontSize = 10.sp), color = Tokens.Accent)
            Text(
                text = "135 · 36 EXP",
                style = Tokens.Type.Caption.copy(fontSize = 7.5.sp, fontFamily = Tokens.Mono),
                color = Tokens.Accent.copy(alpha = 0.75f),
            )
        }
        Spacer(Modifier.weight(1f))
        RoundChromeButton(onClick = onToggleTorch, tag = "camera-flash") {
            Icon(
                painter = painterResource(R.drawable.ic_zap),
                contentDescription = "闪光灯",
                tint = if (torchOn) Tokens.Accent else Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "│ 01 │",
            style = Tokens.Type.Caption.copy(fontSize = 8.5.sp, fontFamily = Tokens.Mono),
            color = Tokens.Accent,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Tokens.AccentGhost)
                .border(1.dp, Tokens.AccentBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .testTag("camera-frame-chip"),
        )
    }
}

/** 38 dp circle with a hairline: C3's close and flash buttons. */
@Composable
private fun RoundChromeButton(onClick: () -> Unit, tag: String, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color(0x59000000))
            .border(1.dp, Color(0x33FFFFFF), CircleShape)
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The exposure reel on the left: EV over a vertical track with a knob.
 *
 * C3 puts it at the left edge, half way down the viewfinder, because that is
 * where a thumb reaches when the phone is held one-handed. A drag on the track
 * writes `camera.exposure_compensation_ev`; the number under it is the same
 * value the engine will use.
 */
@Composable
private fun EvDial(
    ev: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val span = range.endInclusive - range.start
    val fraction = if (span <= 0f) 0.5f else ((ev - range.start) / span).coerceIn(0f, 1f)
    Column(
        modifier
            .width(42.dp)
            .height(126.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(Color(0x66000000))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(21.dp))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("EV", style = Tokens.Type.Caption.copy(fontSize = 7.5.sp, fontFamily = Tokens.Mono), color = Color.White.copy(alpha = 0.75f))
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(20.dp)
                .weight(1f)
                .pointerInput(range) {
                    detectDragGestures { change, _ ->
                        val usable = size.height.coerceAtLeast(1)
                        val at = (1f - (change.position.y / usable)).coerceIn(0f, 1f)
                        onChange(range.start + at * span)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(2.dp).height(60.dp).background(Color(0x4DFFFFFF)))
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (60.dp * (1f - fraction)) - 4.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(Tokens.Accent),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (ev > 0f) "+${String.format("%.1f", ev)}" else String.format("%.1f", ev),
            style = Tokens.Type.Caption.copy(fontSize = 8.5.sp, fontFamily = Tokens.Mono),
            color = Color.White,
        )
    }
}

/** The focus reticle: four amber brackets around the middle of the frame. */
@Composable
private fun FocusReticle(modifier: Modifier = Modifier) {
    Box(modifier.size(56.dp)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val arm = 12.dp.toPx()
            val stroke = 2.dp.toPx()
            val color = Tokens.Accent
            fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(color, Offset(x, y), Offset(x + dx, y), stroke)
                drawLine(color, Offset(x, y), Offset(x, y + dy), stroke)
            }
            bracket(0f, 0f, arm, arm)
            bracket(size.width, 0f, -arm, arm)
            bracket(0f, size.height, arm, -arm)
            bracket(size.width, size.height, -arm, -arm)
        }
        Box(
            Modifier.align(Alignment.Center).size(5.dp).clip(CircleShape).background(Tokens.Accent),
        )
    }
}

/**
 * The lens row: 0.7 / 1× / 2 inside one 140 × 41 pill.
 *
 * C3 draws the three stops as one control with the chosen one filled, not as
 * three separate buttons: the lens is a single choice, and a segmented control
 * that reads as three buttons invites the belief that all three can be on.
 */
@Composable
private fun LensPicker(
    stops: List<ZoomStop>,
    selected: ZoomStop,
    onSelect: (ZoomStop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = stops.take(3)
    if (shown.isEmpty()) return
    Row(
        modifier
            .width(140.dp)
            .height(41.dp)
            .clip(RoundedCornerShape(20.5.dp))
            .background(Color(0x66000000))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(20.5.dp))
            .padding(2.dp),
    ) {
        shown.forEach { stop ->
            val here = stop.ratio == selected.ratio
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.5.dp))
                    .background(if (here) Tokens.Accent else Color.Transparent)
                    .clickable { onSelect(stop) }
                    .testTag("lens-${stop.ratio}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = zoomLabel(stop.ratio),
                    style = Tokens.Type.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                    color = if (here) Tokens.OnAccent else Color.White,
                )
            }
        }
    }
}

/** `AUTO · ISO 400` and `1/125 F4.0`, the two ends of the exposure line. */
@Composable
private fun ExposureLine(readout: ExposureReadout) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = "AUTO · ${readout.isoLabel.ifEmpty { "ISO —" }}",
            style = Tokens.Type.Value,
            color = Color.White.copy(alpha = 0.92f),
        )
        Text(
            text = listOf(readout.shutterLabel, readout.apertureLabel)
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .ifEmpty { "—" },
            style = Tokens.Type.Value,
            color = Color.White.copy(alpha = 0.92f),
        )
    }
}

/** The roll's own line: stock and format on the left, the counter on the right. */
@Composable
private fun RollLine(filmLabel: String, frameIndex: Int, frameCapacity: Int, mode: CaptureMode?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = "$filmLabel · 135",
            style = Tokens.Type.Caption.copy(fontSize = 8.5.sp, fontFamily = Tokens.Mono),
            color = Tokens.Accent,
        )
        Text(
            text = "$frameIndex / $frameCapacity",
            style = Tokens.Type.Caption.copy(fontSize = 8.5.sp, fontFamily = Tokens.Mono),
            color = Color.White,
            modifier = Modifier.testTag("camera-counter"),
        )
    }
}

/**
 * The shutter: a 79 dp white disc with an amber centre, flanked by the last
 * frame and the lens flip.
 *
 * The amber centre is the frame's own — it is the same safelight the product
 * uses everywhere, and it is what makes the shutter read as this camera's.
 */
@Composable
private fun BottomControls(
    count: Int,
    enabled: Boolean,
    onCapture: () -> Unit,
    onFlip: () -> Unit,
    onOpenLast: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x33FFFFFF))
                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenLast)
                .testTag("camera-thumb"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = count.toString(),
                style = Tokens.Type.Caption.copy(fontSize = 8.sp, fontFamily = Tokens.Mono),
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            )
        }

        Box(
            Modifier
                .size(79.dp)
                .clip(CircleShape)
                .background(Color(0x1AFFFFFF))
                .border(3.dp, Color(0xF2FFFFFF), CircleShape)
                .clickable(enabled = enabled, onClick = onCapture)
                .testTag("camera-shutter"),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(71.dp).clip(CircleShape).background(Color(0xF2F5F1EA)))
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (enabled) Tokens.Accent else Tokens.InkTertiary),
            )
        }

        RoundChromeButton(onClick = onFlip, tag = "camera-flip") {
            Icon(
                painter = painterResource(R.drawable.ic_flip_camera),
                contentDescription = "切换镜头",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Thirds, drawn across the whole viewfinder. */
@Composable
private fun CompositionGuides(modifier: Modifier = Modifier) {
    val line = Color(0x2EFFFFFF)
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
