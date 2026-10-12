package com.spektralab.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.spektralab.app.EditorViewModel
import com.spektralab.app.export.ExportColorSpace
import com.spektralab.app.export.ExportFormat
import com.spektralab.app.export.ExportOutcome
import com.spektralab.app.export.ExportSpec
import com.spektralab.app.export.ExportTarget
import kotlin.math.roundToInt

/**
 * The export page (plan S5 item 5, S6's flow, D9 item 3).
 *
 * The gate is the *view model's*: `exportGate()` decides from the frame's own
 * size and the D9 budget whether this export may start at all, and when it may
 * not the page shows its reason and the largest long edge this device can do —
 * a refusal with a number, not a silent downscale. `longEdge = 0` means "the
 * frame's own size"; the slider's ceiling is the gate's largest available
 * value.
 *
 * The engine's writer lands a hidden partial file (plan S6) and
 * `export()` publishes it — to MediaStore's album, or to a directory the user
 * picked with `ACTION_OPEN_DOCUMENT_TREE`.
 */
@Composable
fun ExportPanel(
    viewModel: EditorViewModel,
    state: EditorViewModel.UiState,
    onPickExportDirectory: ((Uri) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gate = state.exportGate ?: viewModel.exportGate()
    val source = state.source
    var format by remember { mutableStateOf(ExportFormat.Tiff16) }
    var colorSpace by remember { mutableStateOf(ExportColorSpace.Srgb) }
    var quality by remember { mutableIntStateOf(92) }
    var longEdge by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableStateOf(ExportTarget.Album) }
    var directory by remember { mutableStateOf<Uri?>(null) }

    val spec = ExportSpec(
        format = format,
        colorSpace = colorSpace,
        jpegQuality = quality,
        longEdge = longEdge.roundToInt(),
    )

    Column(modifier.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (source == null) {
            Text("Open a photograph to export it.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        if (!gate.allowed) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                Column(Modifier.padding(8.dp)) {
                    Text("Export is not available for this frame", style = MaterialTheme.typography.titleSmall)
                    Text(gate.reason.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = "largest long edge this device can do: ${gate.maxLongEdge} px",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Text("Format", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (candidate in ExportFormat.entries) {
                FilterChip(
                    selected = format == candidate,
                    onClick = { format = candidate },
                    label = { Text(candidate.wire) },
                    modifier = Modifier.testTag("export-format-${candidate.wire}"),
                )
            }
        }

        Text("Colour space", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (candidate in ExportColorSpace.entries) {
                FilterChip(
                    selected = colorSpace == candidate,
                    onClick = { colorSpace = candidate },
                    label = { Text(candidate.wire) },
                    modifier = Modifier.testTag("export-space-${candidate.wire}"),
                )
            }
        }

        if (format == ExportFormat.Jpeg8) {
            Text("JPEG quality $quality", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = quality.toFloat(),
                onValueChange = { quality = it.roundToInt() },
                valueRange = 1f..100f,
                modifier = Modifier.fillMaxWidth().testTag("export-quality"),
            )
        }

        Text(
            text = "Long edge: " + if (longEdge <= 0f) "the frame's own size" else "${longEdge.roundToInt()} px",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = longEdge,
            onValueChange = { longEdge = it },
            valueRange = 0f..(gate.maxLongEdge.coerceAtLeast(1)).toFloat(),
            enabled = gate.allowed,
            modifier = Modifier.fillMaxWidth().testTag("export-long-edge"),
        )
        Text(
            text = "budget: ${state.budget?.let { "${it.allowedPixels / 1_000_000} MP" } ?: "-"}" +
                "   largest available: ${gate.maxLongEdge} px",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Text("Target", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = target == ExportTarget.Album,
                onClick = { target = ExportTarget.Album },
                label = { Text("Album (Pictures/SpektraLab)") },
                modifier = Modifier.testTag("export-target-album"),
            )
            FilterChip(
                selected = target == ExportTarget.Directory,
                onClick = { target = ExportTarget.Directory },
                label = { Text("Directory…") },
                modifier = Modifier.testTag("export-target-directory"),
            )
        }
        if (target == ExportTarget.Directory) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onPickExportDirectory { directory = it } },
                    modifier = Modifier.testTag("export-pick-directory"),
                ) { Text(if (directory == null) "Pick a directory" else "Change directory") }
                Text(
                    text = directory?.lastPathSegment ?: "no directory picked",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.export(spec, target, directory) },
                enabled = gate.allowed && !state.busy && (target == ExportTarget.Album || directory != null),
                modifier = Modifier.testTag("export-run"),
            ) { Text("Export ${format.wire}") }
            if (state.busy && state.phase == EditorViewModel.Phase.Exporting) {
                OutlinedButton(onClick = viewModel::cancelExport, modifier = Modifier.testTag("export-cancel")) {
                    Text("Cancel")
                }
            }
        }

        if (state.phase == EditorViewModel.Phase.Exporting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(text = state.exportProgress ?: "rendering the full frame", style = MaterialTheme.typography.labelMedium)
        }

        when (val outcome = state.exportOutcome) {
            is ExportOutcome.Success -> Text(
                text = "wrote ${outcome.width}×${outcome.height} ${outcome.format.wire} (${outcome.colorSpace.wire}), " +
                    "${outcome.bytes / 1024} KiB\n${outcome.uri}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            is ExportOutcome.Refused -> Text(
                text = "refused: ${outcome.reason} (largest available ${outcome.maxLongEdge} px)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            is ExportOutcome.Failed -> Text(
                text = "failed: ${outcome.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            ExportOutcome.Cancelled -> Text("cancelled", style = MaterialTheme.typography.bodySmall)
            null -> Unit
        }

        Text(
            text = "An export closes the half-size canvas session, re-opens this photograph at full size, " +
                "renders and then re-opens the canvas (plan S6/D9): the negative cache is given back afterwards.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
