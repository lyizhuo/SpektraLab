package com.spektralab.app.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.spektralab.app.export.ExportColorSpace
import com.spektralab.app.export.ExportFormat
import com.spektralab.app.ui.theme.Tokens
import org.json.JSONObject

/**
 * The panels that overlay [EditorScreen]: A2 胶片与印相, A3/A4 参数, A5 裁剪.
 *
 * They are sheets rather than screens because the frames draw them that way —
 * the canvas stays visible above the sheet, and every one of them is reachable
 * from the same three toolbar buttons.
 */

/** A sheet's chrome: the scrim over the canvas, the panel, its grabber. */
@Composable
fun SheetScaffold(
    height: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().testTag("sheet-scrim")) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(onClick = onDismiss),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .height(height)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Color(0xFF17130F)),
        ) {
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Tokens.Ink.copy(alpha = 0.22f)),
            )
            content()
        }
    }
}

/** A pill that is either chosen or not; the frames' selected state is a fill. */
@Composable
fun ChoicePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    width: androidx.compose.ui.unit.Dp? = null,
    tag: String = "pill-$label",
) {
    Box(
        Modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0x21FFFFFF) else Color(0x0AFFFFFF))
            .then(
                if (selected) Modifier.border(1.dp, Tokens.Accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = Tokens.Sans,
                fontSize = 10.5.sp,
                color = if (selected) Tokens.Ink else Tokens.InkTertiary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

/** A2 — 胶片与印相: the pair, and the only place both are chosen. */
@Composable
fun FilmSheet(
    films: List<String>,
    papers: List<String>,
    filmStock: String?,
    paperStock: String?,
    onPickFilm: (String) -> Unit,
    onPickPaper: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetScaffold(height = 420.dp, onDismiss = onDismiss) {
        Text(
            text = "胶片与印相",
            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 15.sp, color = Tokens.Ink, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(start = 20.dp, top = 14.dp),
        )
        Text(
            text = "胶片决定色彩，印相决定它的落点",
            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 9.5.sp, color = Tokens.InkTertiary),
            modifier = Modifier.padding(start = 20.dp, top = 2.dp),
        )
        SectionLabel("胶片", Modifier.padding(start = 20.dp, top = 16.dp))
        StockRows(films, filmStock, onPickFilm, "film")
        SectionLabel("印相纸", Modifier.padding(start = 20.dp, top = 12.dp))
        StockRows(papers, paperStock, onPickPaper, "paper")
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = TextStyle(fontFamily = Tokens.Sans, fontSize = 10.sp, color = Tokens.InkTertiary),
        modifier = modifier,
    )
}

@Composable
private fun StockRows(stocks: List<String>, current: String?, onPick: (String) -> Unit, prefix: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        stocks.take(4).forEach { stock ->
            val selected = stock == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Color(0x14E1A95F) else Color(0x08FFFFFF))
                    .clickable { onPick(stock) }
                    .padding(horizontal = 12.dp)
                    .testTag("$prefix-$stock"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = com.spektralab.app.rolls.stockLabel(stock),
                    style = TextStyle(
                        fontFamily = Tokens.Sans,
                        fontSize = 11.5.sp,
                        color = if (selected) Tokens.Accent else Tokens.Ink,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                )
                if (selected) {
                    Text(
                        text = "✓",
                        style = TextStyle(fontFamily = Tokens.Sans, fontSize = 12.sp, color = Tokens.Accent),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** A3 / A4 — 参数, split by the frame's own segmented control. */
@Composable
fun ParamsSheet(
    params: JSONObject?,
    onSetNumber: (String, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var after by remember { mutableStateOf(false) }
    SheetScaffold(height = 470.dp, onDismiss = onDismiss) {
        Row(
            Modifier.padding(start = 20.dp, top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChoicePill("显影前", !after, { after = false }, tag = "params-before")
            ChoicePill("显影后", after, { after = true }, tag = "params-after")
        }
        if (!after) {
            SceneLatitude(params)
            MeteringRow()
            Slider("胶片曝光", keyOf(params, "exposure_compensation_ev"), -3.0, 3.0, "EV", params, onSetNumber)
            Slider("颗粒", keyOf(params, "grain_amount"), 0.0, 2.0, "", params, onSetNumber)
            Slider("光晕", keyOf(params, "halation_amount"), 0.0, 2.0, "", params, onSetNumber)
        } else {
            Text(
                text = "显影后的控制作用于印相，不改变底片",
                style = TextStyle(fontFamily = Tokens.Sans, fontSize = 9.5.sp, color = Tokens.InkTertiary),
                modifier = Modifier.padding(start = 20.dp, top = 12.dp),
            )
            Slider("高光回拉", keyOf(params, "highlight_recovery"), 0.0, 1.0, "", params, onSetNumber)
            Slider("阴影回拉", keyOf(params, "shadow_recovery"), 0.0, 1.0, "", params, onSetNumber)
        }
    }
}

@Composable
private fun SceneLatitude(params: JSONObject?) {
    Text(
        text = "场景宽容度",
        style = TextStyle(fontFamily = Tokens.Sans, fontSize = 10.sp, color = Tokens.InkTertiary),
        modifier = Modifier.padding(start = 20.dp, top = 16.dp),
    )
    Text(
        text = "保持 5.7 档",
        style = TextStyle(fontFamily = Tokens.Sans, fontSize = 11.5.sp, color = Tokens.Ink),
        modifier = Modifier.padding(start = 20.dp, top = 2.dp),
    )
    Row(
        Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Three zones of the input's own distribution, as A3 draws them.
        listOf("低于 6%" to 0.06f, "区间内 89%" to 0.89f, "高于 5%" to 0.05f).forEach { (label, fraction) ->
            Column {
                Box(
                    Modifier
                        .width((fraction * 300).dp.coerceAtLeast(20.dp))
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Tokens.AccentFill),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = label,
                    style = TextStyle(fontFamily = Tokens.Sans, fontSize = 8.sp, color = Tokens.InkTertiary),
                )
            }
        }
    }
}

@Composable
private fun MeteringRow() {
    Row(
        Modifier
            .padding(start = 20.dp, top = 12.dp, end = 20.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "输入 / 相机",
            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 10.sp, color = Tokens.InkSecondary),
        )
        Text(
            text = "重置",
            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 10.sp, color = Tokens.Accent),
        )
    }
}

/**
 * A labelled value with ± buttons rather than a Material slider.
 *
 * The frames draw these as a readout plus a track; ± is the honest minimum
 * that actually moves the engine's number, and it survives a small screen.
 */
@Composable
private fun Slider(
    label: String,
    key: String?,
    min: Double,
    max: Double,
    unit: String,
    params: JSONObject?,
    onSet: (String, Double) -> Unit,
) {
    val current = key?.let { readNumber(params, it) }
    val step = (max - min) / 20.0
    Column(Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label,
                style = TextStyle(fontFamily = Tokens.Sans, fontSize = 11.sp, color = Tokens.InkSecondary),
            )
            Text(
                text = if (current == null) "未就绪" else "%.2f%s".format(current, unit),
                style = TextStyle(
                    fontFamily = Tokens.Mono,
                    fontSize = 11.sp,
                    color = if (current == null) Tokens.InkTertiary else Tokens.Ink,
                ),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Step("−") { if (key != null && current != null) onSet(key, (current - step).coerceIn(min, max)) }
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .padding(horizontal = 10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Tokens.Track),
            ) {
                val fraction = if (current == null) 0f else ((current - min) / (max - min)).toFloat()
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(Tokens.AccentFill),
                )
            }
            Step("＋") { if (key != null && current != null) onSet(key, (current + step).coerceIn(min, max)) }
        }
    }
}

@Composable
private fun Step(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Tokens.Pill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = TextStyle(fontFamily = Tokens.Sans, fontSize = 13.sp, color = Tokens.Ink))
    }
}

/** A5 — 裁剪: the aspect and the four transforms, as the frame lists them. */
@Composable
fun CropSheet(
    aspect: String,
    onAspect: (String) -> Unit,
    onRotate: (Boolean) -> Unit,
    onFlip: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetScaffold(height = 260.dp, onDismiss = onDismiss) {
        Text(
            text = "裁剪",
            style = TextStyle(fontFamily = Tokens.Sans, fontSize = 15.sp, color = Tokens.Ink, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(start = 20.dp, top = 14.dp),
        )
        Row(
            Modifier.padding(start = 20.dp, top = 14.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("自由", "原始", "1:1", "3:2", "4:3", "16:9").forEach { label ->
                ChoicePill(label, aspect == label, { onAspect(label) }, tag = "aspect-$label")
            }
        }
        Row(
            Modifier.padding(start = 20.dp, top = 14.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChoicePill("左转", false, { onRotate(false) }, tag = "rotate-left")
            ChoicePill("右转", false, { onRotate(true) }, tag = "rotate-right")
            ChoicePill("水平翻转", false, { onFlip(true) }, tag = "flip-h")
            ChoicePill("垂直翻转", false, { onFlip(false) }, tag = "flip-v")
        }
    }
}

/**
 * The engine's parameter names, resolved against whichever shape it sends.
 *
 * The wire is nested (`film.grain_amount`) in the schema and flat in places, so
 * a key is tried in both forms before it is declared missing. Guessing a single
 * shape is how a control silently stops working the day the wire is regrouped.
 */
internal fun keyOf(params: JSONObject?, leaf: String): String? {
    if (params == null) return null
    if (params.has(leaf)) return leaf
    val nested = listOf("film", "print", "camera", "scene", "io")
        .firstOrNull { params.optJSONObject(it)?.has(leaf) == true }
    return nested?.let { "$it.$leaf" }
}

internal fun readNumber(params: JSONObject?, key: String): Double? {
    if (params == null) return null
    if (params.has(key)) return params.optDouble(key).takeIf { !it.isNaN() }
    val dot = key.indexOf('.')
    if (dot < 0) return null
    val parent = params.optJSONObject(key.substring(0, dot)) ?: return null
    return parent.optDouble(key.substring(dot + 1)).takeIf { !it.isNaN() }
}

/** A6's format pills, in the frame's order. */
internal val exportFormats: List<Pair<String, ExportFormat>> = listOf(
    "TIFF 16" to ExportFormat.Tiff16,
    "JPEG P3" to ExportFormat.Jpeg8,
    "PNG sRGB" to ExportFormat.Png16,
    "HEIC 10" to ExportFormat.Heic10,
)

internal val exportSpaces: List<Pair<String, ExportColorSpace>> = listOf(
    "sRGB" to ExportColorSpace.Srgb,
    "Display P3" to ExportColorSpace.DisplayP3,
    "ProPhoto RGB" to ExportColorSpace.ProPhoto,
)
