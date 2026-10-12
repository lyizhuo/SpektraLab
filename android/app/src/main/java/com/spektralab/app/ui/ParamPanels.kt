package com.spektralab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.spektralab.app.EditorViewModel
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The parameter controls (plan S5 item 3), generated from `params_schema`.
 *
 * Three rules, all of them from the plan:
 *
 *  * the range of every slider is the schema's own `range` — a field the
 *    schema does not bound renders as text rather than as an invented slider;
 *  * a slider sends `setParams` **on release** (`onValueChangeFinished`), so
 *    one drag is one engine call and one undo step (`UndoStack`'s coalescing
 *    window assumes exactly this);
 *  * `preview_long_edge` is not a `setParams` field at all: it is the `live`
 *    tier's size, so the UI hands it to the next `spk_open` instead.
 */
@Composable
fun ParamGroups(
    viewModel: EditorViewModel,
    state: EditorViewModel.UiState,
    schema: SchemaView,
    stocks: Stocks,
    groups: List<ParamGroup>,
    modifier: Modifier = Modifier,
) {
    val fields = ParamsCatalog.visibleFields(schema, state.unsupportedFeatures)
    val expanded = remember { mutableStateMapOf<ParamGroup, Boolean>() }
    val enabled = state.source != null && !state.busy

    Column(modifier.verticalScroll(rememberScrollState())) {
        if (state.source == null) {
            Text(
                text = "Open a photograph to edit its development.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
            )
        }
        for (group in groups) {
            val groupFields = fields.filter { it.group == group }
            if (groupFields.isEmpty()) continue
            val open = groups.size == 1 || (expanded[group] ?: (group == groups.first()))
            GroupHeader(
                title = group.title,
                tag = "group-${group.name}",
                open = open,
                collapsible = groups.size > 1,
                onToggle = { expanded[group] = !open },
            )
            if (open) {
                for (field in groupFields) {
                    FieldControl(
                        field = field,
                        schema = schema,
                        stocks = stocks,
                        viewModel = viewModel,
                        state = state,
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String, tag: String, open: Boolean, collapsible: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (collapsible) it.clickable(onClick = onToggle) else it }
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (collapsible) {
            Icon(
                imageVector = if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (open) "collapse $title" else "expand $title",
            )
        }
    }
}

@Composable
private fun FieldControl(
    field: ParamField,
    schema: SchemaView,
    stocks: Stocks,
    viewModel: EditorViewModel,
    state: EditorViewModel.UiState,
    enabled: Boolean,
) {
    val params = state.params
    when (field.kind) {
        ParamKind.Toggle -> ToggleField(field, schema, params, viewModel, enabled)
        ParamKind.Choice -> ChoiceField(field, stocks, params, viewModel, enabled)
        ParamKind.Slider ->
            if (schema.isNumeric(field.wire) && schema.range(field.wire) != null) {
                SliderField(field, schema, params, viewModel, enabled)
            } else {
                ReadOnlyField(field, schema, params)
            }
    }
}

@Composable
private fun ToggleField(
    field: ParamField,
    schema: SchemaView,
    params: JSONObject?,
    viewModel: EditorViewModel,
    enabled: Boolean,
) {
    val value = schema.currentOf(params, field.wire) as? Boolean ?: false
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(field.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (!schema.isLive(field.wire)) RebuildTag()
        Switch(
            checked = value,
            enabled = enabled,
            onCheckedChange = { viewModel.setParams(JSONObject().put(field.wire, it)) },
            modifier = Modifier.testTag("field-${field.wire}"),
        )
    }
}

@Composable
private fun ChoiceField(
    field: ParamField,
    stocks: Stocks,
    params: JSONObject?,
    viewModel: EditorViewModel,
    enabled: Boolean,
) {
    val current = params?.optString(field.wire).orEmpty()
    val choices = when (field.choicesFrom) {
        ChoiceSource.Static -> field.choices
        else -> stocks.choicesFor(field.choicesFrom)
    }
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(field.label, style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled && choices.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("field-${field.wire}"),
            ) {
                Text(if (current.isEmpty()) "(none)" else current, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (choice in choices) {
                    DropdownMenuItem(
                        text = { Text(if (choice.isEmpty()) "(none)" else choice) },
                        onClick = {
                            open = false
                            viewModel.setParams(JSONObject().put(field.wire, choice))
                        },
                    )
                }
            }
        }
    }
}

/**
 * A slider whose bounds are the schema's, and whose engine call happens once,
 * when the finger comes off. The local value is what the finger is on; the
 * frame the engine sends back arrives through [EditorViewModel.state] and
 * replaces the canvas, not the slider.
 */
@Composable
private fun SliderField(
    field: ParamField,
    schema: SchemaView,
    params: JSONObject?,
    viewModel: EditorViewModel,
    enabled: Boolean,
) {
    val range = schema.range(field.wire) ?: return
    val integer = schema.isInteger(field.wire)
    // The stored value for a next-open field is the pending one, not the
    // session's (which was fixed when the frame was decoded).
    val stored = if (field.nextOpenOnly) viewModel.previewLongEdge.toDouble() else params?.optDouble(field.wire, range.start.toDouble())
    val value = (stored ?: range.start.toDouble()).toFloat().coerceIn(range.start, range.endInclusive)
    var local by remember(value) { mutableStateOf(value) }
    val span = range.endInclusive - range.start
    val steps = if (integer && span <= 8f) max(0, span.roundToInt() - 1) else 0
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (field.nextOpenOnly) {
                Text(
                    text = "next open",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else if (!schema.isLive(field.wire)) {
                RebuildTag()
            }
            Text(
                text = if (integer) "${local.roundToInt()}" else "%.3f".format(local),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Slider(
            value = local,
            onValueChange = { local = if (integer) it.roundToInt().toFloat() else it },
            valueRange = range,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = {
                if (field.nextOpenOnly) {
                    viewModel.setPreviewLongEdge(local.roundToInt())
                } else if (integer) {
                    viewModel.setParams(JSONObject().put(field.wire, local.roundToInt()))
                } else {
                    viewModel.setParams(JSONObject().put(field.wire, local.toDouble()))
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("field-${field.wire}"),
        )
    }
}

@Composable
private fun ReadOnlyField(field: ParamField, schema: SchemaView, params: JSONObject?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(field.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            text = "${schema.currentOf(params, field.wire) ?: "-"} (no schema range)",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * A non-live field rebuilds the pipeline (or re-develops the negative), which
 * is a visible cost on a phone; the schema's own `live` flag is what decides
 * the tag, not a guess here.
 */
@Composable
private fun RebuildTag() {
    Text(
        text = "rebuild",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(end = 6.dp),
    )
}

/** The undo/redo row, shared by the parameter pane and the sheet. */
@Composable
fun EditHistoryRow(viewModel: EditorViewModel, state: EditorViewModel.UiState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = viewModel::undo, enabled = state.canUndo) { Text("Undo") }
            OutlinedButton(onClick = viewModel::redo, enabled = state.canRedo) { Text("Redo") }
            Text(
                text = "one drag = one step",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        HorizontalDivider()
    }
}
