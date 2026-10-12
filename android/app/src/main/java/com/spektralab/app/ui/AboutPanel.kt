package com.spektralab.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.spektralab.app.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings / about (plan S5 item 6).
 *
 * Everything here is *reported*, not summarised: the engine's build string,
 * the native library's own compile-time state (the plan's S1 acceptance turns
 * on `optimized` — an `-O0` engine makes every measurement taken on it
 * meaningless), the device's limits as the engine advertised them, the
 * features this backend refuses, the licence texts the APK ships, and the
 * engine's current memory report.
 */
@Composable
fun AboutPanel(viewModel: EditorViewModel, state: EditorViewModel.UiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val capabilities = state.capabilities
    val backend = capabilities?.optJSONObject("backend")
    val tiers = capabilities?.optJSONObject("tiers")
    val licenses by produceState(initialValue = emptyList<License>(), capabilities != null) {
        value = withContext(Dispatchers.IO) { readLicenses(context) }
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Section("Engine")
        KeyValue("build", capabilities?.optString("version") ?: "not started")
        KeyValue("engine", capabilities?.optString("engine") ?: "-")
        KeyValue("transport/schema", "${capabilities?.optInt("transport_version")} / ${capabilities?.optInt("schema_version")}")

        Section("Native library")
        val build = state.nativeBuildInfo
        KeyValue("optimized", build?.optBoolean("optimized")?.toString() ?: "-")
        KeyValue("NDEBUG", build?.optBoolean("ndebug")?.toString() ?: "-")
        KeyValue("cmake build type", build?.optString("cmake_build_type") ?: "-")
        KeyValue("abi / api", "${build?.optString("abi")} / ${build?.optInt("android_api")}")
        KeyValue("c++ std", build?.optInt("cxx_std")?.toString() ?: "-")
        KeyValue("compiler", build?.optString("compiler") ?: "-")
        if (build?.optBoolean("optimized") == false) {
            Text(
                text = "This library is not optimized: performance numbers taken on it are not meaningful (plan S1 item 5).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Section("Device limits")
        KeyValue("gpu", backend?.optString("gpu") ?: "-")
        KeyValue("gpu available", backend?.optBoolean("gpu_available")?.toString() ?: "-")
        KeyValue("render core", backend?.optString("render_core") ?: "-")
        KeyValue("working precision", backend?.optString("working_precision") ?: "-")
        KeyValue("max pixels", "${capabilities?.optLong("max_pixels")} (${capabilities?.optDouble("max_mp")} MP)")
        KeyValue("max texture dimension", capabilities?.optInt("max_texture_dimension_2d")?.toString() ?: "-")
        KeyValue("tiers", "live ${tiers?.opt("live")} / preview ${tiers?.opt("preview")} / full ${tiers?.opt("full")}")
        KeyValue("canvas decode", "${viewModel.previewLongEdge} px (next open)")
        KeyValue("budget", state.budget?.let {
            "${it.nativeBudgetBytes / (1 shl 20)} MB, ${it.allowedPixels / 1_000_000} MP, max edge ${it.maxLongEdge} px"
        } ?: "-")

        Section("Unsupported on this backend")
        Text(
            text = state.unsupportedFeatures.ifEmpty { setOf("none") }.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "These controls are hidden rather than shown disabled (plan D6): the engine refuses the fields by name.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        Section("Memory report")
        Text(
            text = state.memory?.toString(2) ?: "not read yet",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
        )

        Section("Edits")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::refreshMemory,
                modifier = Modifier.testTag("about-refresh-memory"),
            ) { Text("Refresh memory") }
            OutlinedButton(onClick = { viewModel.trimCaches() }) { Text("Trim caches") }
            OutlinedButton(
                onClick = viewModel::clearEdits,
                modifier = Modifier.testTag("about-clear-edits"),
            ) { Text("Clear stored edits") }
        }
        KeyValue("stored edits", "filesDir/edits (per photograph, keyed by uri + size + stamp)")

        Section("Licences")
        if (licenses.isEmpty()) {
            Text("No licence texts staged under assets/engine/license.", style = MaterialTheme.typography.bodySmall)
        }
        for (license in licenses) {
            var open by remember(license.name) { mutableStateOf(false) }
            OutlinedButton(onClick = { open = !open }) {
                Text((if (open) "▾ " else "▸ ") + license.name)
            }
            if (open) {
                Text(
                    text = license.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleSmall)
}

@Composable
internal fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

internal data class License(val name: String, val text: String)

/**
 * The licence texts the APK ships (`android/engine/build.gradle.kts` stages
 * `license/` as `assets/engine/license/`). Read from the assets rather
 * than from the unpacked copy, so they are available before the engine has
 * ever been created.
 */
internal fun readLicenses(context: Context, limit: Int = 64 * 1024): List<License> = runCatching {
    val assets = context.assets
    val names = assets.list("engine/license")?.sorted() ?: emptyList()
    names.mapNotNull { name ->
        if (name.endsWith("/")) return@mapNotNull null
        runCatching {
            val text = assets.open("engine/license/$name").bufferedReader().use { reader ->
                val buffer = CharArray(limit)
                val read = reader.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read)
            }
            License(name, text)
        }.getOrNull()
    }
}.getOrDefault(emptyList())
