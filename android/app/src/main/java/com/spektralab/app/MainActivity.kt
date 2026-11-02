package com.spektralab.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import com.spektralab.app.ui.screens.crop.CropScreen
import com.spektralab.app.ui.screens.params.ParamsScreen
import com.spektralab.app.ui.screens.importing.ImportRow
import com.spektralab.app.ui.screens.importing.ImportScreen
import com.spektralab.app.ui.screens.editor.EditorScreen
import com.spektralab.app.ui.screens.editor.EditorTool
import com.spektralab.app.ui.screens.editor.FilmSheet
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

    /** The roll a capture lands in; its frames are what A1's strip shows. */
    private val rolls by lazy { com.spektralab.app.rolls.RollStore(this) }

    /** The frame the editor was asked to open, so the strip can point at it. */
    private var lastOpened: Uri? = null

    /** The export page's chosen folder, remembered for the run. */
    private var exportDirectory: Uri? = null

    /** A5's aspect pill, kept on the screen rather than the engine. */
    private var aspect by mutableStateOf("原始")

    /** A5's straighten angle, in degrees; the engine takes it as geometry. */
    private var rotation by mutableStateOf(0f)

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
        importAll(uris.distinct())
    }

    /**
     * Take a set of frames through C2.
     *
     * B2 hands its selection here and so does the system file picker, so the two
     * doors cannot drift apart: one row per file with its own state, each row
     * settled by that file's own open reaching Ready.
     */
    private fun importAll(picked: List<Uri>) {
        if (picked.isEmpty()) return
        for (uri in picked) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            addToStrip(uri)
        lastOpened = uri
        }
        importRows = picked.map { ImportRow(nameOf(it), ImportRow.State.Waiting) }
        importOpen = true
        lifecycleScope.launch {
            picked.forEachIndexed { index, uri ->
                importRows = importRows.toMutableList().also {
                    it[index] = it[index].copy(state = ImportRow.State.Working)
                }
                viewModel.open(uri, nameOf(uri))
                // Waiting on `phase == Ready` alone is a trap: the previous file
                // already left the state Ready, so `first` returns at once and the
                // sequence races ahead -- on the MI 8 that showed as an import
                // screen stuck at 0% over a black preview. The wait has to name
                // *this* file.
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
        lastOpened = uri
        viewModel.open(uri, name)
    }

    /**
     * A1's film strip is the roll, not the one frame that was just opened.
     *
     * The strip used to be filled only by the caller: one entry from the camera,
     * one from the picker. That made A1 look like a single-photograph editor and
     * hid every other frame of the roll, even though `RollStore` had been
     * recording them all along — the camera appends each capture's content URI.
     */
    private fun seedStripFromRoll() {
        runCatching {
            val roll = rolls.current() ?: return@runCatching
            for (id in roll.frameIds) {
                val uri = runCatching { Uri.parse(id) }.getOrNull() ?: continue
                if (strip.none { it.uri == uri }) {
                    strip.add(StripEntry(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "frame"))
                }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.boot()
        seedStripFromRoll()
        openFromIntent(intent)

        @Suppress("DEPRECATION")
        val batch = intent?.getStringArrayListExtra(EXTRA_BATCH)?.map(Uri::parse).orEmpty()
        val toImport = intent?.getStringArrayListExtra(EXTRA_IMPORT)?.map(Uri::parse).orEmpty()
        if (toImport.isNotEmpty()) {
            // B2 promised to import these, so they go through C2 rather than
            // straight into the editor: the screen that says which file is being
            // decoded is the one the prototype puts between the two.
            importAll(toImport)
        } else if (batch.isNotEmpty()) {
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

                    // Point the strip at the frame that was asked for. The strip
                    // is seeded from the roll, so the opened frame is one of many.
                    LaunchedEffect(strip.size, lastOpened) {
                        val uri = lastOpened ?: return@LaunchedEffect
                        val index = strip.indexOfFirst { it.uri == uri }
                        if (index >= 0) selected = index
                    }

                    LaunchedEffect(Unit) {
                        stocks = runCatching { StockCatalog.load(this@MainActivity) }.getOrDefault(Stocks())
                    }

                    LaunchedEffect(errorLine) {
                        errorLine?.let {
                            android.widget.Toast.makeText(this@MainActivity, it, android.widget.Toast.LENGTH_LONG).show()
                            errorLine = null
                        }
                    }

                    // A page that is not the editor is a page you can come back
                    // from. Without this, back from 参数 or 裁剪 finished the
                    // activity and the photograph being edited went with it.
                    BackHandler(enabled = exportOpen || tool != null || importOpen) {
                        when {
                            importOpen -> Unit
                            exportOpen -> exportOpen = false
                            else -> tool = null
                        }
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
                            note = batchNote,
                        )
                    } else if (importOpen) {
                        ImportScreen(
                            rows = importRows,
                            preview = state.bitmap,
                            onCancel = { importOpen = false },
                        )
                    } else if (exportOpen) {
                        ExportScreen(
                            state = state,
                            exportCount = batchUris.ifEmpty { listOfNotNull(state.source?.uri) }.size,
                            directoryLabel = directoryLabel,
                            onClose = { exportOpen = false },
                            onPickDirectory = {
                                exportDirectoryCallback = { uri ->
                                    directoryLabel = uri.lastPathSegment
                                }
                                pickFolder.launch(null)
                            },
                            onExport = { spec ->
                                val target = exportDirectory?.let { ExportTarget.Directory } ?: ExportTarget.Album
                                viewModel.export(spec, target, exportDirectory)
                            },
                        )
                    } else if (tool == EditorTool.Params) {
                        ParamsScreen(
                            bitmap = state.bitmap,
                            params = state.params,
                            sourceName = state.source?.name,
                            onDelta = { viewModel.setParams(it) },
                            onBack = { tool = null },
                        )
                    } else if (tool == EditorTool.Crop) {
                        CropScreen(
                            bitmap = state.bitmap,
                            aspect = aspect,
                            rotation = rotation,
                            outputLabel = state.source?.let {
                                "输出 ${it.width} × ${it.height}"
                            } ?: "输出 —",
                            onAspect = { label -> aspect = label; viewModel.setParams(aspectDelta(label)) },
                            onRotate = { clockwise ->
                                viewModel.setParams(JSONObject().put("geometry_quarter_turns", if (clockwise) 3 else 1))
                            },
                            onFlip = { horizontal ->
                                viewModel.setParams(
                                    JSONObject().put(if (horizontal) "geometry_flip_h" else "geometry_flip_v", true),
                                )
                            },
                            onStraighten = { degrees ->
                                rotation = degrees
                                viewModel.setParams(JSONObject().put("geometry_rotation_deg", degrees.toDouble()))
                            },
                            onCancel = { tool = null },
                            onDone = { tool = null },
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
                                if (tool == EditorTool.Film) {
                                    FilmSheet(
                                        films = stocks.films,
                                        papers = stocks.prints,
                                        illuminants = stocks.illuminants,
                                        filmStock = state.params?.optString("film_stock"),
                                        paperStock = state.params?.optString("print_stock"),
                                        illuminant = state.params?.optString("enlarger_illuminant"),
                                onPickFilm = { film ->
                                    // A stock is measured against a particular
                                    // paper: C200 against Crystal Archive, Portra
                                    // against Portra Endura. Sending the film alone
                                    // left it on whatever paper was selected, which is
                                    // the other half of "the other films look wrong".
                                    val delta = JSONObject().put("film_stock", film)
                                    stocks.targetPrintFor(film)?.let { paper ->
                                        delta.put("print_stock", paper)
                                    }
                                    viewModel.setParams(delta)
                                },
                                        onPickPaper = { viewModel.setParams(JSONObject().put("print_stock", it)) },
                                        onPickIlluminant = { viewModel.setParams(JSONObject().put("enlarger_illuminant", it)) },
                                        onDismiss = { tool = null },
                                    )
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
        lastOpened = uri
    }

    /**
     * What to call a frame.
     *
     * A `content://` uri's last segment is a database id, not a name: the editor's
     * title read `1000000096` until this asked the provider for the display name.
     * It falls back to the segment when the provider has none (a raw file picked
     * through SAF sometimes does not), and to `photo` when there is nothing at all.
     */
    private fun nameOf(uri: Uri): String {
        if (uri.scheme == "content") {
            val name = runCatching {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()
            if (!name.isNullOrBlank()) return name
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "photo"
    }

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
        lastOpened = uri
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

        /** B2's selection: frames to take through C2 before the editor opens. */
        const val EXTRA_IMPORT = "com.spektralab.app.IMPORT"

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
    if ("胶片与相纸" in groups) copy("film_stock", "print_stock")
    if ("曝光" in groups) copy("exposure_compensation_ev")
    if ("白平衡" in groups) copy("c_filter_neutral", "m_filter_neutral", "y_filter_neutral")
    if ("胶片效果" in groups) copy("grain_amount", "halation_amount")
    return delta
}
