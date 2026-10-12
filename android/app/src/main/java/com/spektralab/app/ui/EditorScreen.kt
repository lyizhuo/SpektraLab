package com.spektralab.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spektralab.app.EditorViewModel
import org.json.JSONObject

/**
 * The S5 editor (plan S5).
 *
 * One screen, three breakpoints, derived from the width this composable is
 * given (`BoxWithConstraints`, so a constrained parent — a preview, a test —
 * gets the layout it asked for):
 *
 *  * **≤600 dp** — full-screen canvas, film strip on top, the parameter groups
 *    in a `ModalBottomSheet`;
 *  * **600–840 dp** — canvas plus a collapsible tool panel;
 *  * **≥840 dp** — three panes: the strip, the canvas, the tool panel.
 *
 * The tool panel hosts the parameter groups (generated from
 * `spk_params_schema`, [ParamGroups]), crop & rotate ([CropPanel]), the export
 * page ([ExportPanel]) and settings/about ([AboutPanel]).
 *
 * Two rules from the plan are enforced as layout, not as a warning:
 * `capabilities.backend.gpu_available == false` (or limits the engine
 * reported as zero) replaces the whole editor with a read-only page — there is
 * no render entry point to press — and the five features this backend refuses
 * are absent from the catalogue rather than shown disabled (D6).
 */
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    strip: List<StripEntry>,
    onAddPhotos: () -> Unit,
    onAddFolder: () -> Unit,
    onPickExportDirectory: ((Uri) -> Unit) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val schema = remember(state.paramsSchema) { SchemaView(state.paramsSchema) }
    val stocks by produceState(Stocks(), state.capabilities) { value = StockCatalog.load(context) }

    // targetSdk 36 means Android 15+ draws this app edge to edge, so the
    // strips and toolbars have to keep clear of the system bars themselves.
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().background(MaterialTheme.colorScheme.background)) {
        val capabilities = state.capabilities
        when {
            capabilities == null -> BootScreen(state, viewModel)
            !engineUsable(capabilities) -> ReadOnlyErrorPage(state, capabilities, viewModel)
            maxWidth < 600.dp -> PhoneLayout(state, viewModel, schema, stocks, strip, onAddPhotos, onAddFolder, onPickExportDirectory)
            maxWidth < 840.dp -> MediumLayout(state, viewModel, schema, stocks, strip, onAddPhotos, onAddFolder, onPickExportDirectory)
            else -> WideLayout(state, viewModel, schema, stocks, strip, onAddPhotos, onAddFolder, onPickExportDirectory)
        }
    }
}

/** The tools the panel and the sheet can show. */
enum class Tool(val title: String) {
    Params("Develop"),
    Crop("Crop"),
    Export("Export"),
    About("About"),
}

/** D6 / plan S5: no render entry point when the backend cannot render. */
private fun engineUsable(capabilities: JSONObject): Boolean {
    val backend = capabilities.optJSONObject("backend")
    if (backend?.optBoolean("gpu_available", false) != true) return false
    if (capabilities.optLong("max_pixels") <= 0L) return false
    return capabilities.optInt("max_texture_dimension_2d") > 0
}

@Composable
private fun BootScreen(state: EditorViewModel.UiState, viewModel: EditorViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.phase == EditorViewModel.Phase.Error) {
            Text("the engine did not start", style = MaterialTheme.typography.titleMedium)
            Text(state.error.orEmpty(), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = viewModel::boot, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
        } else {
            CircularProgressIndicator()
            Text("creating the engine and warming the stock pair…", modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/**
 * The read-only page (plan S5's "unusable is read-only"): the device's GPU
 * story, the reason, and nothing to press that would render.
 */
@Composable
private fun ReadOnlyErrorPage(
    state: EditorViewModel.UiState,
    capabilities: JSONObject,
    viewModel: EditorViewModel,
) {
    val backend = capabilities.optJSONObject("backend")
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("SpektraLab cannot render on this device", style = MaterialTheme.typography.titleLarge)
        KeyValue("gpu", backend?.optString("gpu") ?: "none")
        KeyValue("gpu available", (backend?.optBoolean("gpu_available") ?: false).toString())
        KeyValue("render core", backend?.optString("render_core") ?: "-")
        KeyValue("math mode", backend?.optString("math_mode") ?: "-")
        KeyValue("working precision", backend?.optString("working_precision") ?: "-")
        KeyValue("max pixels", capabilities.optLong("max_pixels").toString())
        KeyValue("max texture dimension", capabilities.optInt("max_texture_dimension_2d").toString())
        KeyValue("engine", capabilities.optString("version"))
        Text(
            text = "reason: " + (state.error
                ?: "the engine reports no usable compute core; the Vulkan compute path is the only one it has, " +
                "and there is no CPU fallback (plan D9 / AGENTS.md)."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = "unsupported here: " + state.unsupportedFeatures.ifEmpty { setOf("none") }.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = viewModel::boot, modifier = Modifier.testTag("error-retry")) {
            Text("Probe the device again")
        }
    }
}

// --- breakpoints -----------------------------------------------------------

@Composable
private fun PhoneLayout(
    state: EditorViewModel.UiState,
    viewModel: EditorViewModel,
    schema: SchemaView,
    stocks: Stocks,
    strip: List<StripEntry>,
    onAddPhotos: () -> Unit,
    onAddFolder: () -> Unit,
    onPickExportDirectory: ((Uri) -> Unit) -> Unit,
) {
    var tool by remember { mutableStateOf<Tool?>(null) }
    Column(Modifier.fillMaxSize()) {
        FilmStrip(
            entries = strip,
            activeUri = state.source?.uri,
            onSelect = { viewModel.open(it.uri, it.name) },
            onAddPhotos = onAddPhotos,
            onAddFolder = onAddFolder,
            modifier = Modifier.testTag("film-strip"),
        )
        CanvasView(
            state = state,
            onTierChange = viewModel::setTier,
            onPickImage = onAddPhotos,
            onCancel = viewModel::cancelRender,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("canvas-phone"),
        )
        StatusLine(state, viewModel)
        ToolBar(selected = tool, onSelect = { tool = if (tool == it) null else it }, viewModel = viewModel, state = state)
    }
    tool?.let { selected ->
        ToolSheet(
            tool = selected,
            onDismiss = { tool = null },
            state = state,
            viewModel = viewModel,
            schema = schema,
            stocks = stocks,
            onPickExportDirectory = onPickExportDirectory,
        )
    }
}

@Composable
private fun MediumLayout(
    state: EditorViewModel.UiState,
    viewModel: EditorViewModel,
    schema: SchemaView,
    stocks: Stocks,
    strip: List<StripEntry>,
    onAddPhotos: () -> Unit,
    onAddFolder: () -> Unit,
    onPickExportDirectory: ((Uri) -> Unit) -> Unit,
) {
    var tool by remember { mutableStateOf(Tool.Params) }
    var panelOpen by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize()) {
        FilmStrip(
            entries = strip,
            activeUri = state.source?.uri,
            onSelect = { viewModel.open(it.uri, it.name) },
            onAddPhotos = onAddPhotos,
            onAddFolder = onAddFolder,
            modifier = Modifier.testTag("film-strip"),
        )
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.weight(1f)) {
                CanvasView(
                    state = state,
                    onTierChange = viewModel::setTier,
                    onPickImage = onAddPhotos,
                    onCancel = viewModel::cancelRender,
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("canvas-medium"),
                )
                StatusLine(state, viewModel)
            }
            if (panelOpen) {
                Column(Modifier.width(320.dp).fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    ToolTabs(selected = tool, onSelect = { tool = it }, collapse = { panelOpen = false })
                    ToolPane(tool, state, viewModel, schema, stocks, onPickExportDirectory, Modifier.weight(1f))
                }
            } else {
                IconButton(onClick = { panelOpen = true }, modifier = Modifier.testTag("panel-expand")) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "show the tool panel")
                }
            }
        }
    }
}

@Composable
private fun WideLayout(
    state: EditorViewModel.UiState,
    viewModel: EditorViewModel,
    schema: SchemaView,
    stocks: Stocks,
    strip: List<StripEntry>,
    onAddPhotos: () -> Unit,
    onAddFolder: () -> Unit,
    onPickExportDirectory: ((Uri) -> Unit) -> Unit,
) {
    var tool by remember { mutableStateOf(Tool.Params) }
    Row(Modifier.fillMaxSize()) {
        FilmStrip(
            entries = strip,
            activeUri = state.source?.uri,
            onSelect = { viewModel.open(it.uri, it.name) },
            onAddPhotos = onAddPhotos,
            onAddFolder = onAddFolder,
            vertical = true,
            modifier = Modifier.width(132.dp).fillMaxSize().testTag("film-strip"),
        )
        Column(Modifier.weight(1f).fillMaxSize()) {
            CanvasView(
                state = state,
                onTierChange = viewModel::setTier,
                onPickImage = onAddPhotos,
                onCancel = viewModel::cancelRender,
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("canvas-wide"),
            )
            StatusLine(state, viewModel)
        }
        Column(Modifier.width(380.dp).fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            ToolTabs(selected = tool, onSelect = { tool = it })
            ToolPane(tool, state, viewModel, schema, stocks, onPickExportDirectory, Modifier.weight(1f))
        }
    }
}

// --- shared pieces ---------------------------------------------------------

@Composable
private fun ToolTabs(selected: Tool, onSelect: (Tool) -> Unit, collapse: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tool in Tool.entries) {
            FilterChip(
                selected = selected == tool,
                onClick = { onSelect(tool) },
                label = { Text(tool.title) },
                modifier = Modifier.testTag("tool-${tool.name.lowercase()}"),
            )
        }
        if (collapse != null) {
            IconButton(onClick = collapse, modifier = Modifier.testTag("panel-collapse")) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "hide the tool panel")
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun ToolBar(
    selected: Tool?,
    onSelect: (Tool) -> Unit,
    viewModel: EditorViewModel,
    state: EditorViewModel.UiState,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(onClick = viewModel::undo, enabled = state.canUndo, modifier = Modifier.testTag("undo")) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "undo")
        }
        IconButton(onClick = viewModel::redo, enabled = state.canRedo, modifier = Modifier.testTag("redo")) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "redo")
        }
        for (tool in Tool.entries) {
            FilterChip(
                selected = selected == tool,
                onClick = { onSelect(tool) },
                label = { Text(tool.title) },
                modifier = Modifier.testTag("tool-${tool.name.lowercase()}"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolSheet(
    tool: Tool,
    onDismiss: () -> Unit,
    state: EditorViewModel.UiState,
    viewModel: EditorViewModel,
    schema: SchemaView,
    stocks: Stocks,
    onPickExportDirectory: ((Uri) -> Unit) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("tool-sheet")) {
            Text(
                text = tool.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            ToolPane(tool, state, viewModel, schema, stocks, onPickExportDirectory, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ToolPane(
    tool: Tool,
    state: EditorViewModel.UiState,
    viewModel: EditorViewModel,
    schema: SchemaView,
    stocks: Stocks,
    onPickExportDirectory: ((Uri) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        when (tool) {
            Tool.Params -> {
                EditHistoryRow(viewModel, state, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                if (state.paramsSchema == null) {
                    Text("waiting for the engine's parameter schema…", modifier = Modifier.padding(8.dp))
                } else {
                    ParamGroups(
                        viewModel = viewModel,
                        state = state,
                        schema = schema,
                        stocks = stocks,
                        groups = ParamsCatalog.sheetGroups,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Tool.Crop -> CropPanel(viewModel, state, schema, Modifier.weight(1f))
            Tool.Export -> ExportPanel(viewModel, state, onPickExportDirectory, Modifier.weight(1f))
            Tool.About -> AboutPanel(viewModel, state, Modifier.weight(1f))
        }
    }
}

/** The frame, the render and the engine's own messages, one line tall. */
@Composable
private fun StatusLine(state: EditorViewModel.UiState, viewModel: EditorViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val source = state.source
        Text(
            text = source?.let { "${it.name}  ${it.width}×${it.height}" } ?: "no photograph open",
            style = MaterialTheme.typography.labelSmall,
        )
        Text("   tier ${state.tier}   ", style = MaterialTheme.typography.labelSmall)
        Text(
            text = "peak ${(state.memory?.optJSONObject("pool")?.optLong("frame_high_water_bytes") ?: 0L) / (1 shl 20)} MB",
            style = MaterialTheme.typography.labelSmall,
        )
        state.message?.let {
            Text("   $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        state.error?.let {
            TextButton(onClick = viewModel::clearError) {
                Text("error: $it (dismiss)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
