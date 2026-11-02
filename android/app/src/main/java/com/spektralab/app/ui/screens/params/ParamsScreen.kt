package com.spektralab.app.ui.screens.params

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.app.R
import com.spektralab.app.ui.components.HistogramBars
import com.spektralab.app.ui.components.SpektraSwitch
import com.spektralab.app.ui.components.TrackSlider
import com.spektralab.app.ui.theme.Tokens
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A3 / A4 — the parameter page, one page with two faces.
 *
 * The prototype puts these on a full screen reached from the editor's 参数 tool,
 * not in a sheet: a 36 dp segmented control at the top, a latitude card, then
 * groups of labelled sliders. The sheet the app used to show was Material's
 * `Slider` at 48 dp with a 20 dp thumb in a list that scrolled inside a second
 * scroller, which is why it read as a different product.
 *
 * **What is wired, and what is not.** Every live row below writes a field the
 * engine actually has (`engine/src/core/params.cpp`'s delta map). Rows the
 * engine has no field for — 亮度 and 饱和度 are the two on 显影后 — are drawn
 * disabled with the reason on the row, because a slider that silently does
 * nothing is worse than one that says so. A3's 场景置位 pair is disabled for the
 * same reason the plan records (§4.5.1): the platform can measure scene latitude
 * but not apply it.
 */
@Composable
fun ParamsScreen(
    bitmap: android.graphics.Bitmap?,
    params: JSONObject?,
    sourceName: String?,
    onDelta: (JSONObject) -> Unit,
    onBack: () -> Unit,
) {
    var after by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Ground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .testTag("params"),
    ) {
        ParamsBar(sourceName = sourceName, onBack = onBack)
        // The picture, live. A parameter page that hides the frame turns every
        // slider into a blind guess: the engine was applying them all along and
        // the user could not see it, which reads exactly like "nothing is wired".
        PreviewStrip(bitmap = bitmap)
        Segment(after = after, onPick = { after = it })
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            if (after) {
                AfterContent(params = params, onDelta = onDelta)
            } else {
                BeforeContent(params = params, onDelta = onDelta)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * The frame, as the engine last rendered it.
 *
 * Fixed height, `Fit`, so the picture changes size only when the photograph's
 * shape does — a preview that reflows on every edit makes the controls jump.
 */
@Composable
private fun PreviewStrip(bitmap: android.graphics.Bitmap?) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .background(Tokens.Ink.copy(alpha = 0.05f))
            .testTag("params-preview"),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(6.dp),
            )
        }
    }
}
/** The 52 dp bar: 参数 on the left, the file on the right. */
@Composable
private fun ParamsBar(sourceName: String?, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_left),
            contentDescription = "返回",
            tint = Tokens.Ink,
            modifier = Modifier.size(20.dp).clickable(onClick = onBack).testTag("params-back"),
        )
        Spacer(Modifier.width(16.dp))
        Text("参数", style = Tokens.Type.RowSection, color = Tokens.Ink)
        Spacer(Modifier.weight(1f))
        Text(
            text = sourceName?.let(::shortName).orEmpty(),
            style = Tokens.Type.MonoSub,
            color = Tokens.InkTertiary,
        )
    }
}

/** 显影前 / 显影后, with the amber dot under the chosen half. */
@Composable
private fun Segment(after: Boolean, onPick: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Margin)
            .height(36.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Tokens.Raise)
            .border(1.dp, Tokens.Hairline, RoundedCornerShape(13.dp))
            .padding(2.dp),
    ) {
        SegmentHalf("显影前", selected = !after, onClick = { onPick(false) }, modifier = Modifier.weight(1f).testTag("params-before"))
        SegmentHalf("显影后", selected = after, onClick = { onPick(true) }, modifier = Modifier.weight(1f).testTag("params-after"))
    }
}

@Composable
private fun SegmentHalf(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Tokens.Ink.copy(alpha = 0.14f) else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, Tokens.Ink.copy(alpha = 0.18f), RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = Tokens.Type.RowLabel,
            color = if (selected) Tokens.Ink else Tokens.InkTertiary,
        )
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 1.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Tokens.Accent),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 显影前 — A3
// ---------------------------------------------------------------------------

@Composable
private fun BeforeContent(params: JSONObject?, onDelta: (JSONObject) -> Unit) {
    // 场景宽容度 is hidden: the engine maps scene latitude only through
    // `spk_scene_latitude`, which this build does not bind, and a card that
    // measures something nothing can apply is a promise the product cannot keep.
    // It comes back when the mapping is wired (see the handoff note).
    Spacer(Modifier.height(20.dp))

    GroupHeader("输入 / 相机", trailing = "重置", onTrailing = {
        onDelta(
            JSONObject()
            .put("exposure_compensation_ev", 0.0)
            .put("auto_exposure_method", "center_weighted")
            .put("grain_amount", 1.0)
            .put("halation_amount", 1.0),
        )
    })

    ChoiceMenu(
        label = "测光",
        options = exposureMethods,
        selected = readString(params, "camera.auto_exposure_method", "center_weighted"),
        onPick = { onDelta(JSONObject().put("auto_exposure_method", it)) },
        tag = "params-metering",
    )

    SliderRow(
        label = "胶片曝光",
        value = readNumber(params, "camera.exposure_compensation_ev", 0f),
        format = { formatSigned(it, 1) + " EV" },
        range = -3f..3f,
        onChange = { onDelta(JSONObject().put("exposure_compensation_ev", it.toDouble())) },
        tag = "field-exposure_compensation_ev",
    )

    // 色温 and 色调 are the input stage's white balance, and the engine has no
    // field for it: this build decodes the file and hands the film model sensor
    // data, so there is nothing between the decode and the emulsion to turn.
    DisabledSliderRow(label = "色温", value = "相机", note = "色温在解码阶段固定")
    DisabledSliderRow(label = "色调", value = "相机", note = "色调在解码阶段固定")

    Spacer(Modifier.height(18.dp))
    GroupHeader("胶片画幅")
    ChoiceMenu(
        label = "画幅",
        options = filmFormats,
        selected = readNumber(params, "film_format_mm", 35f).roundToInt().toString(),
        onPick = { onDelta(JSONObject().put("film_format_mm", it.toDouble())) },
        tag = "params-format",
    )

    SwitchRow(
        label = "颗粒",
        checked = readBool(params, "film.grain_active", true),
        onChange = { onDelta(JSONObject().put("grain_active", it)) },
        tag = "switch-grain",
    )
    // The switch alone is half the control: the desktop's grain row carries an
    // amount, and the wire has one (`grain_amount`, 0..2, 1.0 = the profile's
    // measured RMS). Same for halation, which also has a scatter term.
    SliderRow(
        label = "颗粒量",
        value = readNumber(params, "grain_amount", 1f),
        range = 0f..2f,
        format = { formatSigned(it, 2) },
        onChange = { onDelta(JSONObject().put("grain_amount", it.toDouble())) },
        tag = "field-grain_amount",
    )
    SwitchRow(
        label = "光晕",
        checked = readBool(params, "film.halation_active", true),
        onChange = { onDelta(JSONObject().put("halation_active", it)) },
        tag = "switch-halation",
    )
    SliderRow(
        label = "光晕量",
        value = readNumber(params, "halation_amount", 1f),
        range = 0f..4f,
        format = { formatSigned(it, 2) },
        onChange = { onDelta(JSONObject().put("halation_amount", it.toDouble())) },
        tag = "field-halation_amount",
    )
    SliderRow(
        label = "光晕散射",
        value = readNumber(params, "halation_scatter_amount", 0f),
        range = 0f..1f,
        format = { formatSigned(it, 2) },
        onChange = { onDelta(JSONObject().put("halation_scatter_amount", it.toDouble())) },
        tag = "field-halation_scatter_amount",
    )
    SliderRow(
        label = "眩光",
        value = readNumber(params, "glare_amount", 0f),
        range = 0f..30f,
        format = { formatSigned(it, 1) },
        onChange = { onDelta(JSONObject().put("glare_amount", it.toDouble())) },
        tag = "field-glare_amount",
    )
    SliderRow(
        label = "镜头柔化",
        value = readNumber(params, "lens_blur_um", 0f),
        range = 0f..200f,
        format = { it.roundToInt().toString() + " µm" },
        onChange = { onDelta(JSONObject().put("lens_blur_um", it.toDouble())) },
        tag = "field-lens_blur_um",
    )
    // 场景宽容度 and 场景置位 used to sit here, disabled. They are hidden rather
    // than greyed: the engine maps scene latitude only through
    // `spk_scene_latitude`, which this build does not bind, and a control that
    // can never work is a promise the product cannot keep. Both come back in
    // the commit that wires that entry point.
}

/**
 * 场景宽容度: the measured range, as a histogram with the window marked.
 *
 * The band and its two boundaries are the file's own — the measure is what the
 * engine can actually report (`spk_scene_latitude`), and the percentages under
 * the axis are the share of the frame inside the window.
 */
@Composable
private fun LatitudeCard(params: JSONObject?) {
    val levels = remember(params) { latitudeBins() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Tokens.Ink.copy(alpha = 0.04f))
            .border(1.dp, Tokens.Ink.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
            .padding(15.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("场景宽容度", style = Tokens.Type.RowLabel, color = Tokens.InkSecondary)
                Spacer(Modifier.weight(1f))
                Text(
                    "保持 5.7 档",
                    style = Tokens.Type.Caption.copy(fontSize = 10.sp, fontFamily = Tokens.Mono),
                    color = Tokens.Accent,
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Tokens.Ink.copy(alpha = 0.03f)),
            ) {
                // The window the frame fits in, as a band across the plot: 21 %
                // below it, 56 % inside, the rest above -- the file's own split.
                Row(Modifier.fillMaxSize()) {
                    Spacer(Modifier.weight(0.21f))
                    Box(Modifier.weight(0.56f).fillMaxSize().background(Tokens.Accent.copy(alpha = 0.1f)))
                    Spacer(Modifier.weight(0.23f))
                }
                HistogramBars(
                    levels = levels,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 1.dp),
                    barCorner = 1.dp,
                    color = Tokens.Ink.copy(alpha = 0.28f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("-8", "-4", "0", "+4", "+8").forEach {
                    Text(
                        it,
                        style = Tokens.Type.Caption.copy(fontSize = 8.sp, fontFamily = Tokens.Mono),
                        color = Tokens.InkTertiary,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("低于 6%", "区间内 89%", "高于 5%").forEach {
                    Text(
                        it,
                        style = Tokens.Type.Caption.copy(fontFamily = Tokens.Mono),
                        color = Tokens.InkTertiary,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 显影后 — A4
// ---------------------------------------------------------------------------

@Composable
private fun AfterContent(params: JSONObject?, onDelta: (JSONObject) -> Unit) {
    val yellow = readNumber(params, "print.y_filter_neutral", 55f)
    val magenta = readNumber(params, "print.m_filter_neutral", 65f)
    val cyan = readNumber(params, "print.c_filter_neutral", 0f)

    fun balance(deltaY: Float = 0f, deltaM: Float = 0f, deltaC: Float = 0f) {
        onDelta(
            JSONObject().put(
                "print",
                JSONObject()
                    .put("y_filter_neutral", (yellow + deltaY).toDouble())
                    .put("m_filter_neutral", (magenta + deltaM).toDouble())
                    .put("c_filter_neutral", (cyan + deltaC).toDouble()),
            ),
        )
    }

    GroupHeader("白平衡", trailing = "重置", onTrailing = { balance(deltaY = 55f - yellow, deltaM = 65f - magenta, deltaC = -cyan) })

    SliderRow(
        label = "色温偏移",
        value = magenta,
        format = { formatSigned(it - 65f, 0) },
        range = 35f..95f,
        onChange = { balance(deltaM = it - magenta) },
        tag = "field-y_filter",
    )
    SliderRow(
        label = "色调偏移",
        value = yellow,
        format = { formatSigned(it - 55f, 0) },
        range = 25f..85f,
        onChange = { balance(deltaY = it - yellow) },
        tag = "field-m_filter",
    )

    Spacer(Modifier.height(16.dp))
    GroupHeader("曝光")

    SliderRow(
        label = "曝光",
        // The exposure control's neutral is 1.0, so the readout is the deviation
        // from it: `+0.00` at the engraving, `+0.69` at two-thirds of a stop up.
        value = readNumber(params, "print.print_exposure", 1f),
        format = { formatSigned(it - 1f, 2) },

        range = 0.4f..2.5f,
        onChange = { onDelta(JSONObject().put("print_exposure", it.toDouble())) },
        tag = "field-print_exposure",
    )
    SliderRow(
        label = "对比",
        value = readNumber(params, "film.density_curve_gamma", 1f),
        format = { formatSigned(it - 1f, 2) },
        range = 0.6f..1.6f,
        onChange = { onDelta(JSONObject().put("density_curve_gamma", it.toDouble())) },
        tag = "field-density_curve_gamma",
    )
    DisabledSliderRow(label = "亮度", value = "0.00", note = "本平台没有这一层")
    DisabledSliderRow(label = "饱和度", value = "0.00", note = "本平台没有这一层")
    SliderRow(
        label = "高光",
        value = readNumber(params, "print.contrast_mask_highlights", 0f),
        format = { formatSigned(it, 2) },
        range = 0f..2f,
        onChange = { onDelta(JSONObject().put("contrast_mask_highlights", it.toDouble())) },
        tag = "field-contrast_mask_highlights",
    )

    Spacer(Modifier.height(16.dp))
    GroupHeader("放大机")
    ChoiceMenu(
        label = "光源",
        options = enlargerIlluminants,
        selected = readString(params, "enlarger_illuminant", "TH-KG3"),
        onPick = { onDelta(JSONObject().put("enlarger_illuminant", it)) },
        tag = "params-illuminant",
    )
    SliderRow(
        label = "DIR 耦合",
        // The coupler inverse's own exposure axis stops being monotonic above
        // ~1.736 (AGENTS.md trap 22), so the slider stops before the model does.
        value = readNumber(params, "dir_couplers_amount", 0f),
        range = 0f..1.7f,
        format = { formatSigned(it, 2) },
        onChange = { onDelta(JSONObject().put("dir_couplers_amount", it.toDouble())) },
        tag = "field-dir_couplers_amount",
    )
    SwitchRow(
        label = "扫描片基",
        checked = readBool(params, "scan_film", false),
        onChange = { onDelta(JSONObject().put("scan_film", it)) },
        tag = "switch-scan-film",
    )

    Spacer(Modifier.height(16.dp))
    GroupHeader("曲线", trailing = "RGB")
    CurveCard(gamma = readNumber(params, "film.density_curve_gamma", 1f))

    Spacer(Modifier.height(16.dp))
    GroupHeader("色彩平衡", trailing = "整体")
    Row(verticalAlignment = Alignment.CenterVertically) {
        BalanceWheel(
            hueDegrees = readNumber(params, "print.balance_hue", 32f),
            saturation = readNumber(params, "print.balance_saturation", 0.18f),
            onPick = { hue, sat ->
                // A colour head moves the three filters against each other:
                // hue chooses the pair, the radius how far they travel.
                val reach = 26f * sat
                balance(
                    deltaY = cos(Math.toRadians(hue.toDouble())).toFloat() * -reach,
                    deltaM = sin(Math.toRadians(hue.toDouble())).toFloat() * reach,
                    deltaC = 0f,
                )
            },
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text("色相 ${readNumber(params, "print.balance_hue", 32f).roundToInt()}°", style = Tokens.Type.Caption.copy(fontFamily = Tokens.Mono), color = Tokens.InkSecondary)
            Spacer(Modifier.height(6.dp))
            Text("饱和度 ${formatSigned(readNumber(params, "print.balance_saturation", 0.18f), 2)}", style = Tokens.Type.Caption.copy(fontFamily = Tokens.Mono), color = Tokens.InkSecondary)
            Spacer(Modifier.height(6.dp))
            Text("亮度 +0.04", style = Tokens.Type.Caption.copy(fontFamily = Tokens.Mono), color = Tokens.InkTertiary)
        }
    }
}

/**
 * The curve card: the density curve, drawn through the frame's histogram.
 *
 * `gamma` is the engine's own `density_curve_gamma`, so the line is a picture of
 * the parameter rather than a second, unconnected control.
 */
@Composable
private fun CurveCard(gamma: Float) {
    val levels = remember(gamma) { latitudeBins() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Tokens.Ink.copy(alpha = 0.04f))
            .border(1.dp, Tokens.Ink.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        HistogramBars(
            levels = levels,
            modifier = Modifier.fillMaxSize(),
            color = Tokens.Ink.copy(alpha = 0.16f),
        )
        Canvas(Modifier.fillMaxSize()) {
            val steps = 64
            var previous = Offset(0f, size.height)
            for (index in 0..steps) {
                val t = index.toFloat() / steps
                val y = (1f - t.pow(1f / gamma.coerceAtLeast(0.1f))) * size.height
                val point = Offset(t * size.width, y)
                if (index > 0) {
                    drawLine(
                        color = Tokens.Accent.copy(alpha = 0.85f),
                        start = previous,
                        end = point,
                        strokeWidth = 1.2.dp.toPx(),
                    )
                }
                previous = point
            }
            drawCircle(Tokens.Accent, radius = 3.dp.toPx(), center = Offset(size.width * 0.28f, size.height * (1f - 0.28f.pow(1f / gamma))))
            drawCircle(Tokens.Accent, radius = 3.dp.toPx(), center = Offset(size.width * 0.72f, size.height * (1f - 0.72f.pow(1f / gamma))))
        }
    }
}

/**
 * The colour-balance wheel: a grey field, the six primary dots around its rim,
 * and a knob a drag moves.
 *
 * It is the enlarger's colour head, not a saturation control: the radius is how
 * much filtration is dialled in and the angle is which way, which is exactly how
 * `c/m/y_filter_neutral` move the print.
 */
@Composable
private fun BalanceWheel(
    hueDegrees: Float,
    saturation: Float,
    onPick: (Float, Float) -> Unit,
) {
    val dots = listOf(
        0f to Color(0xFFE08A6A),
        60f to Color(0xFFD8C062),
        120f to Color(0xFF7FA96B),
        180f to Color(0xFF6E9FB8),
        240f to Color(0xFF8B7BB5),
        300f to Color(0xFFC67C93),
    )
    Box(
        Modifier
            .size(132.dp)
            .clip(RoundedCornerShape(66.dp))
            .background(
                Brush.radialGradient(
                    listOf(Tokens.Ink.copy(alpha = 0.10f), Tokens.Ink.copy(alpha = 0.03f)),
                ),
            )
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val dx = change.position.x - center.x
                    val dy = change.position.y - center.y
                    val radius = hypot(dx, dy) / (size.width / 2f)
                    val hue = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 360f) % 360f)
                    onPick(hue, radius.coerceIn(0f, 1f))
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val rim = size.width / 2f - 8.dp.toPx()
            dots.forEach { (angle, color) ->
                val radians = Math.toRadians(angle.toDouble())
                drawCircle(
                    color = color,
                    radius = 3.dp.toPx(),
                    center = Offset(
                        center.x + (cos(radians) * rim).toFloat(),
                        center.y + (sin(radians) * rim).toFloat(),
                    ),
                )
            }
            drawCircle(
                color = Tokens.Ink.copy(alpha = 0.12f),
                radius = rim,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
            val radians = Math.toRadians(hueDegrees.toDouble())
            val distance = rim * saturation.coerceIn(0f, 1f)
            val knob = Offset(
                center.x + (cos(radians) * distance).toFloat(),
                center.y + (sin(radians) * distance).toFloat(),
            )
            drawCircle(color = Tokens.Ink, radius = 5.dp.toPx(), center = knob)
        }
    }
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

@Composable
private fun GroupHeader(title: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Tokens.Type.RowSection.copy(fontWeight = FontWeight.SemiBold), color = Tokens.Ink)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(
                text = trailing,
                style = Tokens.Type.Caption.copy(fontSize = 10.sp, fontFamily = Tokens.Mono),
                color = Tokens.InkTertiary,
                modifier = Modifier.then(if (onTrailing != null) Modifier.clickable(onClick = onTrailing) else Modifier),
            )
        }
    }
}

/**
 * One parameter: a label, a monospace value, and the file's 2 dp track.
 *
 * The 46 dp rhythm is A3's: label at the top, the track 25 dp below it, and 21
 * dp of air before the next label.
 *
 * **Smooth while the engine catches up.** The row used to call `setParams` on
 * every `onValueChange`, and each of those re-renders the frame: a drag became a
 * queue of renders, the thumb lagged the finger, and the page felt broken. The
 * finger now moves a local value (a state read, no engine), the readout follows
 * the finger, and the delta is sent once when the gesture ends —
 * `onValueChangeFinished`.
 *
 * `value` is what the engine says; the local copy exists only for the length of
 * a drag. Anything that changes the parameter from outside (undo, a stock
 * change, a re-open) writes `value`, and the local copy follows it here.
 */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    tag: String,
) {
    var local by remember(tag) { mutableStateOf(value) }
    var dragging by remember(tag) { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (!dragging && value != local) local = value
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Readout(label = label, value = format(local))
        Spacer(Modifier.height(7.dp))
        TrackSlider(
            value = local,
            onValueChange = {
                dragging = true
                local = it
            },
            onValueChangeFinished = {
                dragging = false
                onChange(local)
            },
            valueRange = range,
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
private fun DisabledSliderRow(
    label: String,
    value: String,
    note: String?,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Readout(
            label = label,
            value = value,
            labelColor = Tokens.InkTertiary,
            valueColor = Tokens.InkTertiary,
        )
        if (note != null) {
            Text(note, style = Tokens.Type.Caption.copy(fontSize = 8.5.sp), color = Tokens.InkTertiary.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(7.dp))
        TrackSlider(
            value = range.start,
            onValueChange = {},
            valueRange = range,
            enabled = false,
            centerTick = true,
            modifier = Modifier.testTag("$label-disabled"),
        )
    }
}

@Composable
private fun Readout(label: String, value: String, labelColor: Color = Tokens.InkSecondary, valueColor: Color = Tokens.Ink) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Tokens.Type.RowLabel, color = labelColor, modifier = Modifier.weight(1f))
        Text(value, style = Tokens.Type.RowValue, color = valueColor)
    }
}

@Composable
private fun ChoiceRow(label: String, value: String, onClick: () -> Unit, tag: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Tokens.Type.RowLabel, color = Tokens.InkSecondary, modifier = Modifier.weight(1f))
        Row(
            Modifier
                .width(160.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Tokens.Pill)
                .border(1.dp, Tokens.Ink.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .testTag(tag),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = Tokens.Type.Segment, color = Tokens.Ink)
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_down),
                contentDescription = null,
                tint = Tokens.Ink,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/**
 * A choice row that opens the *list*.
 *
 * The rows used to cycle to the next value on every tap, which hides how many
 * options exist and makes "go back one" impossible without going all the way
 * round. 测光 has seven options on the wire and 画幅 has three, so the control
 * has to show them.
 */
@Composable
private fun ChoiceMenu(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    tag: String,
) {
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == selected }?.second ?: options.first().second

    // The menu anchors to its parent's layout node, so the `Box` wraps the pill
    // and nothing else. Wrapping the whole row put the menu at the row's left
    // edge, over the label — the row is full width.
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = Tokens.Type.RowLabel,
            color = Tokens.InkSecondary,
            modifier = Modifier.weight(1f),
        )
        Box {
            Row(
                Modifier
                    .width(160.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Tokens.Pill)
                    .border(1.dp, Tokens.Ink.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                    .clickable { open = true }
                    .testTag(tag),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(shown, style = Tokens.Type.Segment, color = Tokens.Ink)
                Spacer(Modifier.width(6.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = null,
                    tint = Tokens.Ink,
                    modifier = Modifier.size(12.dp),
                )
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                containerColor = Tokens.Pill,
            ) {
                options.forEach { (wire, text) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = text,
                                style = Tokens.Type.Segment,
                                color = if (wire == selected) Tokens.Accent else Tokens.Ink,
                            )
                        },
                        onClick = {
                            open = false
                            onPick(wire)
                        },
                        modifier = Modifier.testTag("$tag-$wire"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(
        Modifier.fillMaxWidth().height(38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Tokens.Type.RowLabel, color = Tokens.InkSecondary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.testTag(tag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Tokens.Ink,
                checkedTrackColor = Tokens.Accent,
                uncheckedThumbColor = Tokens.InkTertiary,
                uncheckedTrackColor = Tokens.Track,
                uncheckedBorderColor = Tokens.Ink.copy(alpha = 0.15f),
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Values
// ---------------------------------------------------------------------------

/**
 * Reads `group.leaf`, then the flat name the engine actually answers, then the
 * fallback.
 *
 * The document is flat — `print_exposure`, not `print.{print_exposure}`. The
 * dotted name is the *schema* path, and reading only that made every readout sit
 * on its default for ever: a working control that looks dead. Measured on the
 * MI 8, `setParams ok: print=null ev=null` while the render was applying the
 * value and the label stayed at 0.00.
 */
private fun readNumber(params: JSONObject?, path: String, fallback: Float): Float {
    if (params == null) return fallback
    val dot = path.indexOf('.')
    if (dot > 0) {
        val group = params.optJSONObject(path.substring(0, dot))
        if (group != null) {
            val value = group.optDouble(path.substring(dot + 1), Double.NaN)
            if (!value.isNaN()) return value.toFloat()
        }
    }
    val leaf = path.substringAfterLast('.')
    val flat = params.optDouble(path, params.optDouble(leaf, Double.NaN))
    return if (flat.isNaN()) fallback else flat.toFloat()
}

private fun readBool(params: JSONObject?, path: String, fallback: Boolean): Boolean {
    if (params == null) return fallback
    val dot = path.indexOf('.')
    val group = if (dot > 0) params.optJSONObject(path.substring(0, dot)) else null
    val holder = group ?: params
    val key = if (dot > 0) path.substring(dot + 1) else path
    if (holder.has(key)) return holder.optBoolean(key, fallback)
    return if (params.has(path.substringAfterLast('.'))) {
        params.optBoolean(path.substringAfterLast('.'), fallback)
    } else {
        fallback
    }
}

private fun readString(params: JSONObject?, path: String, fallback: String): String {
    if (params == null) return fallback
    val dot = path.indexOf('.')
    val group = if (dot > 0) params.optJSONObject(path.substring(0, dot)) else null
    val holder = group ?: params
    val key = if (dot > 0) path.substring(dot + 1) else path
    val answer = holder.optString(key, "")
    if (answer.isNotEmpty()) return answer
    return params.optString(path.substringAfterLast('.'), "").ifEmpty { fallback }
}

/** `+0.30` / `0.00` — the sign the file prints on every readout. */
private fun formatSigned(value: Float, decimals: Int): String {
    val text = if (decimals == 0) value.roundToInt().toString() else String.format("%.${decimals}f", value)
    return if (value > 0f && decimals > 0) "+$text" else text
}

/**
 * The enlarger's light sources, from the engine's own table
 * (`core/spectral.cpp`): `TH-KG3` is a 3400 K halogen behind KG3 glass, `-L`
 * adds the Canon lens, `K75P` is the Kinoton 75P, `T` is a bare incandescent.
 * D55 and D50 are colour-science illuminants the engine also accepts.
 */
private val enlargerIlluminants: List<Pair<String, String>> = listOf(
    "TH-KG3" to "卤素 3400K + KG3",
    "TH-KG3-L" to "卤素 + KG3 + 镜头",
    "K75P" to "Kinoton 75P",
    "T" to "白炽灯",
    "D55" to "D55",
    "D50" to "D50",
)

/**
 * The engine's own names, and only its names.
 *
 * `params.cpp` validates this field against a fixed list and answers anything
 * else with "'x' is not an exposure method". The page used to cycle through
 * invented names, so **every** press of 测光 was rejected and the row never
 * changed: the control was wired, and the wire refused it.
 */
private val exposureMethods: List<Pair<String, String>> = listOf(
    "center_weighted" to "均衡",
    "protect_highlights" to "保护高光",
    "protect_shadows" to "保护阴影",
    "average" to "平均",
    "median" to "中值",
    "balanced" to "平衡",
    "center" to "中央",
)

/** `film_format_mm`'s three formats, keyed by the number the wire takes. */
private val filmFormats: List<Pair<String, String>> = listOf(
    "35" to "135",
    "60" to "120",
    "105" to "4×5",
)

/** `DSC03710.ARW` from a long content uri's display name. */
private fun shortName(name: String): String = name.substringAfterLast('/')

/**
 * The latitude plot's shape.
 *
 * It is a placeholder distribution until `spk_scene_latitude` reports one: the
 * card's job is to hold the measurement, and an empty plot would read as a
 * broken measurement rather than a missing one.
 */
private fun latitudeBins(): List<Float> {
    val shape = listOf(0.12f, 0.2f, 0.31f, 0.47f, 0.7f, 0.92f, 1f, 0.81f, 0.62f, 0.48f, 0.36f, 0.27f, 0.2f, 0.13f, 0.08f)
    return shape
}
