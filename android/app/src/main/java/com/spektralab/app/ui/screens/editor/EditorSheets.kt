@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spektralab.app.ui.screens.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * A2 / A3 / A4 / A5 — the sheets.
 *
 * `ModalBottomSheet` rather than a hand-rolled scrim: it brings the drag handle,
 * the back gesture and the scrim's accessibility, which a `Box` with a black
 * overlay does not. The content and its order are the frames'.
 */
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("胶片与印相", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                "胶片",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            LazyColumn(Modifier.height(170.dp).padding(top = 4.dp)) {
                items(films) { stock ->
                    ListItem(
                        headlineContent = { Text(stock, style = MaterialTheme.typography.bodyMedium) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (stock == filmStock) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            } else {
                                Color.Transparent
                            },
                        ),
                        modifier = Modifier
                            .testTag("film-$stock")
                            .clickable { onPickFilm(stock) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "相纸",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            LazyColumn(Modifier.height(140.dp).padding(top = 4.dp)) {
                items(papers) { stock ->
                    ListItem(
                        headlineContent = { Text(stock, style = MaterialTheme.typography.bodyMedium) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (stock == paperStock) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            } else {
                                Color.Transparent
                            },
                        ),
                        modifier = Modifier
                            .testTag("paper-$stock")
                            .clickable { onPickPaper(stock) },
                    )
                }
            }
        }
    }
}

/** A3 显影前 / A4 显影后 on one sheet, with the frame's own segmented control. */
@Composable
fun ParamsSheet(
    params: JSONObject?,
    onSetNumber: (String, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var after by remember { mutableStateOf(false) }
    val rows = if (after) afterRows else beforeRows

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false, true).forEachIndexed { index, isAfter ->
                    SegmentedButton(
                        selected = after == isAfter,
                        onClick = { after = isAfter },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        label = { Text(if (isAfter) "显影后" else "显影前") },
                        modifier = Modifier.testTag(if (isAfter) "params-after" else "params-before"),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.height(340.dp)) {
                items(rows) { spec ->
                    ParamRow(
                        spec = spec,
                        value = readNumber(params, spec.key, spec.initial),
                        onChange = { onSetNumber(spec.key, it) },
                    )
                }
            }
        }
    }
}

private data class ParamSpec(
    val label: String,
    val key: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val initial: Float,
)

/** A3's list; the keys are the engine's wire names. */
private val beforeRows = listOf(
    ParamSpec("曝光补偿", "exposure_compensation_ev", -3f, 3f, 0.1f, 0f),
    ParamSpec("色温", "white_balance_k", 3000f, 9000f, 100f, 5500f),
    ParamSpec("色调", "tint", -60f, 60f, 1f, 0f),
    ParamSpec("颗粒", "grain_amount", 0f, 2f, 0.05f, 1f),
    ParamSpec("光晕", "halation_amount", 0f, 2f, 0.05f, 0.5f),
    ParamSpec("场景置位", "scene_placement_ev", -2f, 2f, 0.1f, 0f),
)

/** A4's list: what the print stage owns. */
private val afterRows = listOf(
    ParamSpec("放大曝光", "print_exposure", 0.4f, 2.5f, 0.02f, 1f),
    ParamSpec("对比", "print_contrast", 0.5f, 2f, 0.02f, 1f),
    ParamSpec("偏色 M", "print_compensation_m", -0.5f, 0.5f, 0.01f, 0f),
    ParamSpec("偏色 Y", "print_compensation_y", -0.5f, 0.5f, 0.01f, 0f),
    ParamSpec("前闪", "preflash_exposure", 0f, 1f, 0.02f, 0f),
)

@Composable
private fun ParamRow(spec: ParamSpec, value: Float, onChange: (Double) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(spec.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = formatValue(value, spec.step),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−") { onChange((value - spec.step).coerceAtLeast(spec.min).toDouble()) }
            Slider(
                value = value,
                onValueChange = { onChange(quantise(it, spec.step).toDouble()) },
                valueRange = spec.min..spec.max,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
                    .testTag("field-${spec.key}"),
            )
            StepButton("+") { onChange((value + spec.step).coerceAtMost(spec.max).toDouble()) }
        }
    }
}

@Composable
private fun StepButton(symbol: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.width(36.dp)) {
        Text(symbol, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun CropSheet(
    aspect: String,
    onAspect: (String) -> Unit,
    onRotate: (Boolean) -> Unit,
    onFlip: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("裁剪", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.height(120.dp)) {
                items(listOf("自由", "原始", "1:1", "3:2", "4:3", "16:9").chunked(3)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            FilterChip(
                                selected = option == aspect,
                                onClick = { onAspect(option) },
                                label = { Text(option, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary
                                        .copy(alpha = 0.2f),
                                ),
                                modifier = Modifier.testTag("aspect-$option"),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onRotate(false) }) {
                    Icon(Icons.Outlined.RotateLeft, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("左转")
                }
                TextButton(onClick = { onRotate(true) }) {
                    Icon(Icons.Outlined.RotateRight, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("右转")
                }
                TextButton(onClick = { onFlip(true) }) {
                    Icon(Icons.Outlined.Flip, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("水平")
                }
                TextButton(onClick = { onFlip(false) }) { Text("垂直") }
            }
        }
    }
}

internal fun quantise(value: Float, step: Float): Float =
    if (step <= 0f) value else (value / step).roundToInt() * step

internal fun formatValue(value: Float, step: Float): String =
    if (step >= 1f) value.roundToInt().toString() else String.format("%.2f", value)

/**
 * Reads a parameter by wire name, trying the flat key first and then the nested
 * object the engine uses (`film`, `print`, `camera`, `scene`, `io`).
 */
internal fun readNumber(params: JSONObject?, leaf: String, fallback: Float): Float {
    if (params == null) return fallback
    params.optDouble(leaf, Double.NaN).takeIf { !it.isNaN() }?.let { return it.toFloat() }
    for (group in listOf("film", "print", "camera", "scene", "io", "geometry")) {
        val nested = params.optJSONObject(group) ?: continue
        val value = nested.optDouble(leaf, Double.NaN)
        if (!value.isNaN()) return value.toFloat()
    }
    return fallback
}
