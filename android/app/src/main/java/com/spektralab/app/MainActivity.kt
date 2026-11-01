package com.spektralab.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.spektralab.app.EditorViewModel.Phase
import com.spektralab.app.export.ExportTarget
import com.spektralab.app.ui.StockCatalog
import com.spektralab.app.ui.Stocks
import com.spektralab.app.ui.screens.batch.BatchScreen
import com.spektralab.app.ui.screens.importing.ImportRow
import com.spektralab.app.ui.screens.importing.ImportScreen
import com.spektralab.app.ui.screens.editor.CropSheet
import com.spektralab.app.ui.screens.editor.EditorScreen
import com.spektralab.app.ui.screens.editor.EditorTool
import com.spektralab.app.ui.screens.editor.FilmSheet
import com.spektralab.app.ui.screens.editor.ParamsSheet
import com.spektralab.app.ui.screens.editor.StripEntry
import com.spektralab.app.ui.screens.editor.frameNumber
import com.spektralab.app.ui.screens.editor.stockOf
import com.spektralab.app.ui.screens.export.ExportScreen
import com.spektralab.app.ui.theme.SpektraLabTheme
import com.spektralab.app.ui.theme.SpektraLabTheme
import com.spektralab.app.ui.theme.SpektraLabTheme
import com.spektralab.app.ui.theme.Tokens
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The prototype's warm accent, shared with C3's shutter. */
private val CameraAccent = Color(0xFFE8A33D)

class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels()

    /**
     * What the film strip shows. It is a session list of picked photographs
     * rather than a folder scan: the grants are persisted, so an entry stays
     * openable after a restart, and nothing is enumerated the user did not
     * point at.
     */
    private val strip = mutableStateListOf<StripEntry>()

    /** The export page's chosen folder, remembered for the run. */
    private var exportDirectory: Uri? = null

    /** A5's aspect pill, kept on the screen rather than the engine. */
    private var aspect by mutableStateOf("原始")

    /** C2's rows while an import is running, and whether that screen is up. */
    private var importRows by mutableStateOf<List<ImportRow>>(emptyList())
    private var importOpen by mutableStateOf(false)

    /** The last import failure, shown on C2 rather than swallowed. */
    private var errorLine by mutableStateOf<String?>(null)

    /** B1 is up when a batch arrived; the set is the frames it carries. */
    private var batchUris by mutableStateOf<List<Uri>>(emptyList())
    private var batchOpen by mutableStateOf(false)
    private var batchSelected by mutableStateOf<Set<Int>>(emptySet())
    /** 拷贝's clipboard, in the process only — it is not a file. */
    private var copiedParams by mutableStateOf<JSONObject?>(null)
    private var batchNote by mutableStateOf<String?>(null)

    /** Where the export page's `ACTION_OPEN_DOCUMENT_TREE` result goes. */
    private var exportDirectoryCallback: ((Uri) -> Unit)? = null

    private val pickImages = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val picked = uris.distinct()
        if (picked.isEmpty()) return@registerForActivityResult
        for (uri in picked) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            addToStrip(uri)
        }
        // C2 draws one row per file with its own state, so the import has to be
        // a sequence the user can watch rather than a loop that ends on the
        // last file. Each row settles when its own `open` reaches Ready.
        importRows = picked.map { ImportRow(nameOf(it), ImportRow.State.Waiting) }
        importOpen = true
        lifecycleScope.launch {
            picked.forEachIndexed { index, uri ->
                importRows = importRows.toMutableList().also {
                    it[index] = it[index].copy(state = ImportRow.State.Working)
                }
                viewModel.open(uri, nameOf(uri))
                // Waiting on `phase == Ready` alone is a trap: the previous
                // file already left the state Ready, so `first` returns at once
                // and the sequence races ahead — on the MI 8 that showed as an
                // import screen stuck at 0% over a black preview. The wait has
                // to name *this* file.
                val settled = withTimeoutOrNull(120_000) {
                    viewModel.state.first {
                        it.source?.uri == uri && (it.phase == Phase.Ready || it.phase == Phase.Error)
                    }
                }
                if (settled == null) {
                    errorLine = "导入超时：${nameOf(uri)}"
                    return@launch
                }
                importRows = importRows.toMutableList().also {
                    it[index] = it[index].copy(
                        state = if (settled.phase == Phase.Ready) ImportRow.State.Done else ImportRow.State.Waiting,
                    )
                }
            }
            importOpen = false
        }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val exportTarget = exportDirectoryCallback
        exportDirectoryCallback = null
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // One grant covers both roles: the same tree can be the export target,
        // and (when it was picked as a folder) the strip's source.
        if (exportTarget != null) {
            exportTarget(uri)
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { scanFolder(uri) }
            strip.addAll(found.filterNot { entry -> strip.any { it.uri == entry.uri } })
            found.firstOrNull()?.let { viewModel.open(it.uri, it.name) }
        }
    }

    /**
     * C3's result: one DNG, already developed enough to be openable.
     *
     * The capture is a real RAW file, so the engine's own LibRaw path reads it
     * exactly as it reads an imported `.dng` — the film model gets sensor data
     * rather than a JPEG's tone curve. That is the whole reason the camera
     * exists in this product (`AGENTS.md` trap 12).
     */
    private val launchCamera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != CameraActivity.RESULT_CAPTURED) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "capture.dng"
        if (strip.none { it.uri == uri }) strip.add(StripEntry(uri, name))
        viewModel.open(uri, name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.boot()
        openFromIntent(intent)

        @Suppress("DEPRECATION")
        val batch = intent?.getStringArrayListExtra(EXTRA_BATCH)?.map(Uri::parse).orEmpty()
        if (batch.isNotEmpty()) {
            batchUris = batch
            batchSelected = batch.indices.toSet()
            batchOpen = true
        }
        setContent {
            SpektraLabTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Tokens.Ground) {
                    val state by viewModel.state.collectAsState()
                    var tool by remember { mutableStateOf<EditorTool?>(null) }
                    var exportOpen by remember { mutableStateOf(false) }
                    var selected by remember { mutableStateOf(0) }
                    var stocks by remember { mutableStateOf(Stocks()) }
                    var directoryLabel by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(Unit) {
                        stocks = runCatching { StockCatalog.load(this@MainActivity) }.getOrDefault(Stocks())
                    }

                    if (batchOpen) {
                        BatchScreen(
                            strip = batchUris.mapIndexed { index, uri -> StripEntry(uri, nameOf(uri)) },
                            selected = batchSelected,
                            bitmap = state.bitmap,
                            stockLabel = stockOf(state),
                            frameNumber = frameNumber(state.source?.name ?: ""),
                            onToggle = { index ->
                                batchSelected = if (index in batchSelected) batchSelected - index else batchSelected + index
                            },
                            onExit = {
                                batchOpen = false
                                batchSelected = emptySet()
                            },
                            onCopy = {
                                copiedParams = state.params
                                batchNote = "已拷贝当前设置"
                            },
                            onPaste = {
                                copiedParams?.let { viewModel.setParams(it) }
                                batchNote = if (copiedParams == null) "还没有拷贝任何设置" else "已粘贴到当前照片"
                            },
                            onSync = { groups ->
                                val source = copiedParams ?: state.params
                                batchNote = if (source == null) "还没有可复制的设置" else null
                                if (source != null) {
                                    val targets = batchSelected.sorted()
                                        .mapNotNull { batchUris.getOrNull(it) }
                                    lifecycleScope.launch {
                                        var done = 0
                                        for (uri in targets) {
                                            viewModel.open(uri, nameOf(uri))
                                            withTimeoutOrNull(120_000) {
                                                viewModel.state.first {
                                                    it.source?.uri == uri && it.phase == Phase.Ready
                                                }
                                            }
                                            viewModel.setParams(syncDelta(source, groups))
                                            done++
                                        }
                                        batchNote = "已同步到 $done 张"
                                    }
                                }
                            },
                            onExport = { exportOpen = true },
                        )
                    } else if (importOpen) {
                        ImportScreen(
                            rows = importRows,
                            preview = state.bitmap,
                            onCancel = {
                                importOpen = false
                                importRows = emptyList()
                            },
                        )
                    } else if (exportOpen) {
                        ExportScreen(
                            state = state,
                            exportCount = strip.size.coerceAtLeast(1),
                            directoryLabel = directoryLabel,
                            onClose = { exportOpen = false },
                            onPickDirectory = {
                                exportDirectoryCallback = { picked ->
                                    directoryLabel = picked.lastPathSegment?.substringAfterLast(':')
                                }
                                pickFolder.launch(null)
                            },
                            onExport = { spec ->
                                viewModel.export(spec, ExportTarget.Directory, exportDirectory)
                            },
                        )
                    } else {
                        EditorScreen(
                            state = state,
                            strip = strip,
                            selectedIndex = selected,
                            onSelectFrame = { index ->
                                selected = index
                                strip.getOrNull(index)?.let { entry ->
                                    viewModel.open(entry.uri, entry.name)
                                }
                            },
                            onOpenPhotos = { pickImages.launch(IMAGE_TYPES) },
                            onClose = { finish() },
                            onTool = { tool = it },
                            tool = tool,
                            onExport = { exportOpen = true },
                            sheet = {
                                when (tool) {
                                    EditorTool.Film -> FilmSheet(
                                        films = stocks.films,
                                        papers = stocks.prints,
                                        filmStock = state.params?.optString("film_stock"),
                                        paperStock = state.params?.optString("paper_stock"),
                                        onPickFilm = { viewModel.setParams(JSONObject().put("film_stock", it)) },
                                        onPickPaper = { viewModel.setParams(JSONObject().put("paper_stock", it)) },
                                        onDismiss = { tool = null },
                                    )
                                    EditorTool.Params -> ParamsSheet(
                                        params = state.params,
                                        onSetNumber = { key, value ->
                                            viewModel.setParams(nestedDelta(key, value))
                                        },
                                        onDismiss = { tool = null },
                                    )
                                    EditorTool.Crop -> CropSheet(
                                        aspect = aspect,
                                        onAspect = { label -> aspect = label; viewModel.setParams(aspectDelta(label)) },
                                        onRotate = { clockwise ->
                                            viewModel.setParams(JSONObject().put("geometry_quarter_turns", if (clockwise) 3 else 1))
                                        },
                                        onFlip = { horizontal ->
                                            viewModel.setParams(
                                                JSONObject().put(
                                                    if (horizontal) "geometry_flip_h" else "geometry_flip_v",
                                                    true,
                                                ),
                                            )
                                        },
                                        onDismiss = { tool = null },
                                    )
                                    null -> Unit
                                }
                            },
                        )
                    }
                }
            }
        }
        }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        viewModel.onTrimMemory(level)
    }

    private fun addToStrip(uri: Uri) {
        val name = nameOf(uri)
        if (strip.none { it.uri == uri }) strip.add(StripEntry(uri, name))
    }

    private fun nameOf(uri: Uri): String = uri.lastPathSegment?.substringAfterLast('/') ?: "photo"

    /**
     * The plan's extension list (S5 item 1): the raw formats the engine's
     * LibRaw build carries plus the rasters the platform can decode. The mime
     * type a provider reports for a raw file is not reliable, so the extension
     * is the filter.
     */
    private fun scanFolder(tree: Uri): List<StripEntry> {
        val root = DocumentFile.fromTreeUri(this, tree) ?: return emptyList()
        val found = mutableListOf<StripEntry>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty() && found.size < STRIP_LIMIT) {
            val directory = queue.removeFirst()
            for (child in directory.listFiles()) {
                if (child.isDirectory) {
                    queue.addLast(child)
                } else if (child.name?.extension() in RAW_EXTENSIONS) {
                    found.add(StripEntry(child.uri, child.name ?: "photo"))
                    if (found.size >= STRIP_LIMIT) break
                }
            }
        }
        return found
    }

    /**
     * Opens the photograph an Intent named, if it named one.
     *
     * Reached from C1 — its import pill and its 最近照片 tiles — and from
     * nothing else yet. A URI that cannot be read is ignored rather than
     * thrown: the caller is another screen of this app, and refusing to open
     * the editor because one tile went stale would be worse than opening it
     * empty.
     */
    private fun openFromIntent(intent: Intent?) {
        if (intent == null) return
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(EXTRA_OPEN_URI) ?: intent.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            addToStrip(uri)
            viewModel.open(uri, nameOf(uri))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)

        @Suppress("DEPRECATION")
        val batch = intent?.getStringArrayListExtra(EXTRA_BATCH)?.map(Uri::parse).orEmpty()
        if (batch.isNotEmpty()) {
            batchUris = batch
            batchSelected = batch.indices.toSet()
            batchOpen = true
        }
    }

    private fun String.extension(): String = substringAfterLast('.', "").lowercase()

    companion object {
        val IMAGE_TYPES = arrayOf("image/*", "application/octet-stream")

        /**
         * Home's door into the editor: the content URI to open on arrival.
         *
         * C1's "从相册导入" and its 最近照片 tiles both mean "open *this*
         * photograph", so the editor has to accept one. Until now it could only
         * be told through its own picker.
         */
        const val EXTRA_OPEN_URI = "com.spektralab.app.OPEN_URI"

        /** B1's door: the frames that arrived already selected. */
        const val EXTRA_BATCH = "com.spektralab.app.BATCH"

        /** Plan S5 item 1's traversal list. */
        val RAW_EXTENSIONS = setOf(
            "arw", "nef", "cr2", "cr3", "dng", "raf", "orf", "rw2", "pef",
            "jpg", "jpeg", "png", "tif", "tiff",
        )

        /** Plan S5's LRU ceiling for the thumbnail wall. */
        const val STRIP_LIMIT = 256
    }
}

/**
 * Turns `film.grain_amount` into `{"film": {"grain_amount": …}}`.
 *
 * The sheet asks for a leaf by whichever name the engine exposes; the wire is
 * nested, and a flat `put("film.grain_amount", …)` is a key the engine has
 * never heard of — it would be accepted and silently do nothing.
 */
private fun nestedDelta(key: String, value: Double): JSONObject {
    val dot = key.indexOf('.')
    return if (dot < 0) {
        JSONObject().put(key, value)
    } else {
        JSONObject().put(key.substring(0, dot), JSONObject().put(key.substring(dot + 1), value))
    }
}

/** A5's aspect pills, as the normalised crop the engine takes. */
private fun aspectDelta(label: String): JSONObject {
    val ratio = when (label) {
        "1:1" -> 1.0
        "3:2" -> 3.0 / 2.0
        "4:3" -> 4.0 / 3.0
        "16:9" -> 16.0 / 9.0
        else -> 0.0
    }
    val delta = JSONObject()
    if (ratio <= 0.0) {
        delta.put("geometry_crop_x", 0.0).put("geometry_crop_y", 0.0)
            .put("geometry_crop_w", 1.0).put("geometry_crop_h", 1.0)
        return delta
    }
    // Centre the crop: the longest span the ratio allows, symmetric about the
    // middle, which is what the frame's crop box draws.
    val width = if (ratio >= 1.0) 1.0 else ratio
    val height = if (ratio >= 1.0) 1.0 / ratio else 1.0
    delta.put("geometry_crop_x", (1.0 - width) / 2.0)
        .put("geometry_crop_y", (1.0 - height) / 2.0)
        .put("geometry_crop_w", width)
        .put("geometry_crop_h", height)
    return delta
}

/**
 * The groups B1's checkboxes carry, as wire keys.
 *
 * A group with no entry here is one the wire has no name for yet. That is not
 * a reason to silently pretend: [BatchScreen] lists six groups and this maps
 * four of them, so a sync reports how many frames it wrote rather than
 * claiming all six travelled.
 */
private fun syncDelta(source: JSONObject, groups: Set<String>): JSONObject {
    val delta = JSONObject()
    fun copy(vararg keys: String) {
        for (key in keys) {
            if (source.has(key)) delta.put(key, source.get(key))
        }
    }
    if ("胶片与相纸" in groups) copy("film_stock", "paper_stock")
    if ("曝光" in groups) copy("exposure_compensation_ev")
    if ("白平衡" in groups) copy("white_balance_k", "tint")
    if ("胶片效果" in groups) copy("grain_amount", "halation_amount")
    return delta
}