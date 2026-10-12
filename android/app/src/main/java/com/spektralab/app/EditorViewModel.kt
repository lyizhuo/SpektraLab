package com.spektralab.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.spektralab.app.edits.EditStore
import com.spektralab.app.edits.UndoStack
import com.spektralab.app.export.ExportColorSpace
import com.spektralab.app.export.ExportFormat
import com.spektralab.app.export.ExportGate
import com.spektralab.app.export.ExportOutcome
import com.spektralab.app.export.ExportSpec
import com.spektralab.app.export.ExportTarget
import com.spektralab.app.export.ExportWorker
import com.spektralab.engine.EngineClient
import com.spektralab.engine.EngineException
import com.spektralab.engine.EngineSession
import com.spektralab.engine.MemoryBudget
import com.spektralab.engine.RenderInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * The one place that talks to the engine.
 *
 * State is a single immutable value the Compose layer renders; every command
 * hops to the engine's worker thread (inside [EngineClient]) and comes back
 * with a new state. Nothing here blocks the main thread: opening, rendering
 * and exporting are all seconds-long native calls.
 *
 * Three of the plan's policies live here rather than in the UI, because they
 * are about what the engine is *allowed* to be asked for:
 *
 *  * the D9 budget gate before an export (and the refusal that names the
 *    largest long edge this device can do instead of silently downscaling);
 *  * the export path's session flow — the canvas decodes at half size, the
 *    export closes that session and re-opens at full size (which is also what
 *    returns the cached negatives afterwards);
 *  * editing state that survives the app: [EditStore] per photograph,
 *    [UndoStack] for the last 64 edits.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    enum class Phase { Booting, Ready, Opening, Rendering, Exporting, Error }

    data class Source(val uri: Uri, val name: String, val width: Int, val height: Int, val metadata: JSONObject)

    data class UiState(
        val phase: Phase = Phase.Booting,
        val capabilities: JSONObject? = null,
        val paramsSchema: JSONObject? = null,
        val nativeBuildInfo: JSONObject? = null,
        val unsupportedFeatures: Set<String> = emptySet(),
        val budget: MemoryBudget? = null,
        val source: Source? = null,
        val tier: String = "live",
        val bitmap: Bitmap? = null,
        val lastRender: RenderInfo? = null,
        val params: JSONObject? = null,
        val memory: JSONObject? = null,
        val message: String? = null,
        val error: String? = null,
        val busy: Boolean = false,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val exportGate: ExportGate? = null,
        val exportProgress: String? = null,
        val exportOutcome: ExportOutcome? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val context: Application get() = getApplication()

    /**
     * The process's engine, not this view model's: an export worker outlives
     * the activity, and a second engine would be a second GPU device holding
     * its own full-frame planes (plan S4).
     */
    private val client: EngineClient get() = (context as SpektraLabApp).engine

    private var session: EngineSession? = null
    private var exportJob: Job? = null

    private val editStore = EditStore(application)
    private val undoStack = UndoStack()

    /** The canvas decode: the live tier's long edge (1600 phone / 2048 tablet). */
    var previewLongEdge: Int = 1600
        private set

    private var currentUri: Uri? = null
    private var pendingExportSpec: ExportSpec? = null
    private var currentName: String = "photo"
    private var geometry: JSONObject = JSONObject()

    val isEngineReady: Boolean get() = _state.value.phase != Phase.Booting

    fun boot() {
        if (_state.value.capabilities != null) return
        viewModelScope.launch {
            try {
                val created = client
                val capabilities = created.start()
                val unsupported = capabilities.optJSONObject("backend")
                    ?.optJSONArray("unsupported_features")
                    ?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
                    ?: emptySet()
                val budget = MemoryBudget(totalRamBytes(context))
                _state.value = _state.value.copy(
                    phase = Phase.Ready,
                    capabilities = capabilities,
                    paramsSchema = created.paramsSchema,
                    nativeBuildInfo = created.nativeBuildInfo,
                    unsupportedFeatures = unsupported,
                    budget = budget,
                    memory = created.memoryReport(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(phase = Phase.Error, error = failure.message ?: failure.toString())
            }
        }
    }

    /**
     * Opens a photograph, restoring anything the user did to it before.
     * `halfSize` is the canvas decode: LibRaw half size for RAW, which is what
     * makes the first frame and every interactive edit cheap.
     */
    fun open(uri: Uri, displayName: String, halfSize: Boolean = true, decodeJson: JSONObject? = null) {
        if (client == null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(phase = Phase.Opening, busy = true, error = null)
            saveEdits()
            try {
                currentUri = uri
                currentName = displayName
                undoStack.clear()
                val opened = openSession(uri, displayName, halfSize, decodeJson, restoreEdits = true)
                _state.value = _state.value.copy(
                    source = Source(uri, displayName, opened.width, opened.height, opened.metadata),
                    params = opened.params,
                    canUndo = undoStack.canUndo,
                    canRedo = undoStack.canRedo,
                    phase = Phase.Ready,
                )
                renderTier("live", reuse = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(phase = Phase.Error, error = failure.message ?: failure.toString())
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    /** Applies a wire delta and re-renders the current tier. */
    fun setParams(delta: JSONObject, resumeTier: String = _state.value.tier) {
        viewModelScope.launch {
            val opened = ensureSession() ?: return@launch
            try {
                val before = opened.params
                val fields = delta.keys().asSequence().toSet()
                val merged = undoStack.record(before, fields, System.currentTimeMillis())
                if (!merged) {
                    // A new step starts here: the state to undo *to* is the one
                    // the engine held before this change.
                    undoStack.record(before, fields, System.currentTimeMillis())
                }
                val params = opened.setParams(delta)
                geometry = params.filterGeometry()
                _state.value = _state.value.copy(
                    params = params,
                    canUndo = undoStack.canUndo,
                    canRedo = undoStack.canRedo,
                    exportGate = exportGate(),
                )
                renderTier(resumeTier, reuse = _state.value.bitmap)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(error = failure.message ?: failure.toString())
            }
        }
    }

    fun undo() = restoreStep(undo = true)

    fun redo() = restoreStep(undo = false)

    private fun restoreStep(undo: Boolean) {
        viewModelScope.launch {
            val opened = ensureSession() ?: return@launch
            try {
                val step = (if (undo) undoStack.undo() else undoStack.redo()) ?: return@launch
                val params = opened.setParams(step)
                geometry = params.filterGeometry()
                _state.value = _state.value.copy(
                    params = params,
                    canUndo = undoStack.canUndo,
                    canRedo = undoStack.canRedo,
                )
                renderTier(_state.value.tier, reuse = _state.value.bitmap)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(error = failure.message ?: failure.toString())
            }
        }
    }

    fun setTier(tier: String) {
        _state.value = _state.value.copy(tier = tier)
        viewModelScope.launch { renderTier(tier, reuse = null) }
    }

    /** Reprints from the cached negative: a print-stock change without re-developing. */
    fun reprint() {
        viewModelScope.launch {
            val opened = ensureSession() ?: return@launch
            _state.value = _state.value.copy(phase = Phase.Rendering, busy = true)
            try {
                val (bitmap, info) = opened.renderInto(_state.value.tier, reprint = true, reuse = _state.value.bitmap)
                _state.value = _state.value.copy(
                    bitmap = bitmap,
                    lastRender = info,
                    memory = info.memory,
                    phase = Phase.Ready,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(phase = Phase.Error, error = failure.message ?: failure.toString())
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun cancelRender() {
        session?.cancelFromAnyThread()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null, message = null, exportOutcome = null)
    }

    // --- export ------------------------------------------------------------

    /** Whether an export is allowed, and why not when it is not (plan D9 item 3). */
    fun exportGate(): ExportGate {
        val source = _state.value.source ?: return ExportGate(false, "no photograph open", 0)
        val budget = _state.value.budget ?: return ExportGate(false, "engine not ready", 0)
        val maxPixels = _state.value.capabilities?.optLong("max_pixels") ?: 0
        val maxTexture = _state.value.capabilities?.optInt("max_texture_dimension_2d") ?: 0
        val verdict = budget.verdict(source.width, source.height, maxPixels, maxTexture)
        return when (verdict) {
            is MemoryBudget.Verdict.Allowed -> ExportGate(true, null, verdictHeadroom(source, budget))
            is MemoryBudget.Verdict.Refused ->
                ExportGate(false, verdict.reason, budget.maxLongEdgeFor(source.width, source.height))
        }
    }

    private fun verdictHeadroom(source: Source, budget: MemoryBudget): Int =
        minOf(budget.maxLongEdgeFor(source.width, source.height), maxOf(source.width, source.height))

    /**
     * Exports at full resolution. The canvas session is half size, so this
     * closes it and re-opens at full size first (S6's flow); afterwards the
     * interactive session is re-opened, which is also what gives the cached
     * negatives back.
     */
    fun export(spec: ExportSpec, target: ExportTarget, directory: Uri? = null) {
        val source = _state.value.source ?: return
        if (exportJob?.isActive == true) return
        val gate = exportGate()
        if (!gate.allowed) {
            _state.value = _state.value.copy(
                exportOutcome = ExportOutcome.Refused(gate.reason ?: "export is not available", gate.maxLongEdge),
            )
            return
        }
        if (target == ExportTarget.Directory && directory == null) {
            _state.value = _state.value.copy(exportOutcome = ExportOutcome.Failed("no directory was picked"))
            return
        }
        val params = _state.value.params ?: JSONObject()
        pendingExportSpec = spec
        saveEdits()
        val request = OneTimeWorkRequestBuilder<ExportWorker>()
            .setInputData(
                workDataOf(
                    ExportWorker.KEY_SOURCE to source.uri.toString(),
                    ExportWorker.KEY_NAME to source.name,
                    ExportWorker.KEY_PARAMS to params.toString(),
                    ExportWorker.KEY_FORMAT to spec.format.wire,
                    ExportWorker.KEY_SPACE to spec.colorSpace.wire,
                    ExportWorker.KEY_QUALITY to spec.jpegQuality,
                    ExportWorker.KEY_LONG_EDGE to spec.longEdge,
                    ExportWorker.KEY_TARGET to target.name,
                    ExportWorker.KEY_DIRECTORY to directory?.toString(),
                ),
            )
            .addTag(ExportWorker.UNIQUE_WORK)
            .build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(ExportWorker.UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
        exportJob = viewModelScope.launch {
            _state.value = _state.value.copy(phase = Phase.Exporting, busy = true, exportProgress = "queued")
            workManager.getWorkInfosForUniqueWorkFlow(ExportWorker.UNIQUE_WORK).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                val stage = info.progress.getString(ExportWorker.KEY_STAGE)
                if (stage != null && _state.value.busy) {
                    _state.value = _state.value.copy(exportProgress = stage)
                }
                if (!info.state.isFinished) return@collect
                finishExport(info)
            }
        }
    }

    /** Turns a finished work request into the state the export page shows. */
    private suspend fun finishExport(info: WorkInfo) {
        val output = info.outputData
        val outcome = when (info.state) {
            WorkInfo.State.SUCCEEDED -> ExportOutcome.Success(
                uri = output.getString(ExportWorker.KEY_URI) ?: "",
                width = output.getInt(ExportWorker.KEY_WIDTH, 0),
                height = output.getInt(ExportWorker.KEY_HEIGHT, 0),
                bytes = output.getLong(ExportWorker.KEY_BYTES, 0),
                format = pendingExportSpec?.format ?: ExportFormat.Tiff16,
                colorSpace = pendingExportSpec?.colorSpace ?: ExportColorSpace.Srgb,
            )
            WorkInfo.State.CANCELLED -> ExportOutcome.Cancelled
            else -> ExportOutcome.Failed(output.getString(ExportWorker.KEY_ERROR) ?: "export ${info.state}")
        }
        _state.value = _state.value.copy(exportOutcome = outcome, busy = false, exportProgress = null, phase = Phase.Ready)
        // The worker closed its full-resolution session; the canvas needs one
        // again, and re-opening it is also what returns the cached negatives.
        runCatching { reopenCanvasSession() }
    }

    private suspend fun reopenCanvasSession() {
        val uri = currentUri ?: return
        val reopened = openSession(uri, currentName, halfSize = true, decodeJson = null, restoreEdits = false)
        _state.value.params?.let { reopened.setParams(it) }
        renderTier(_state.value.tier, reuse = null)
    }

    fun cancelExport() {
        // `spk_cancel` is safe from any thread and unfixes the render at its
        // next node boundary; WorkManager's cancellation stops the worker, and
        // the runner's `finally` then removes the temporary file.
        client.currentSession?.cancelFromAnyThread()
        WorkManager.getInstance(context).cancelUniqueWork(ExportWorker.UNIQUE_WORK)
        _state.value = _state.value.copy(exportProgress = "cancelling")
    }

    // --- memory ------------------------------------------------------------

    /**
     * The system is short of memory. Engine-side there is nothing to ask:
     * Vulkan has no pressure callback and no disk-backed planes, so the app
     * has to shrink itself — drop caches, close what is not being looked at,
     * and lower the canvas size for the next open.
     */
    fun onTrimMemory(level: Int) {
        if (level < Application.TRIM_MEMORY_RUNNING_LOW) return
        previewLongEdge = 1080
        trimCaches(maxBytes = 128L shl 20)
        _state.value = _state.value.copy(message = "memory pressure: canvas resolution lowered to 1080 px")
    }

    /**
     * `cacheDir` is capped (plan S6): export temporaries first, then anything
     * else the app put there.
     */
    fun trimCaches(maxBytes: Long = CACHE_CAP_BYTES) {
        val tempExports = File(context.cacheDir, "export")
        tempExports.listFiles()?.forEach { if (it.lastModified() < System.currentTimeMillis() - 60_000) it.delete() }
        val cache = context.cacheDir
        val files = cache.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = files.sumOf { if (it.isFile) it.length() else 0L }
        for (file in files.reversed()) {
            if (total <= maxBytes) break
            if (!file.isFile) continue
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    fun refreshMemory() {
        val engine = client ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(memory = engine.memoryReport(), exportGate = exportGate())
        }
    }

    // --- edits persistence --------------------------------------------------

    fun saveEdits() {
        val uri = currentUri ?: return
        val params = _state.value.params ?: return
        runCatching {
            editStore.save(
                uri = uri,
                params = params,
                ui = JSONObject().apply {
                    put("tier", _state.value.tier)
                    put("preview_long_edge", previewLongEdge)
                },
                geometry = geometry,
            )
        }
    }

    fun clearEdits() {
        editStore.clear()
        _state.value = _state.value.copy(message = "stored edits cleared")
    }

    // --- internals ----------------------------------------------------------

    /**
     * The canvas session, re-opened when it is gone.
     *
     * Nothing about the engine is shared across components except the process's
     * client: an export closes every session it opened (its own full-size one
     * and the canvas's half-size one), so the canvas asks for a new session
     * before it renders rather than assuming the one it remembers is alive.
     */
    private suspend fun ensureSession(): EngineSession? {
        val existing = session
        if (existing != null && !existing.isClosed) return existing
        val uri = currentUri ?: return null
        return openSession(uri, currentName, halfSize = true, decodeJson = null, restoreEdits = true)
    }

    private suspend fun openSession(
        uri: Uri,
        displayName: String,
        halfSize: Boolean,
        decodeJson: JSONObject?,
        restoreEdits: Boolean,
    ): EngineSession {
        val engine = client ?: throw EngineException(-1, "engine not created")
        engine.closeSession()
        session = null
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw EngineException(-1, "cannot open $displayName")
        val delta = JSONObject().apply {
            put("preview_long_edge", previewLongEdge)
            decodeJson?.let { put("decode", it) }
        }
        val opened = descriptor.use { engine.open(it.detachFd(), displayName, halfSize, delta) }
        session = opened
        if (restoreEdits) {
            editStore.load(uri)?.let { stored ->
                if (stored.params.length() > 0) opened.setParams(stored.params)
                geometry = stored.geometry
                previewLongEdge = stored.ui.optInt("preview_long_edge", previewLongEdge)
            }
        }
        return opened
    }

    private suspend fun renderTier(tier: String, reuse: Bitmap?) {
        val opened = ensureSession() ?: return
        _state.value = _state.value.copy(phase = Phase.Rendering, busy = true)
        try {
            val (bitmap, info) = opened.renderInto(tier, reprint = false, reuse = reuse)
            _state.value = _state.value.copy(
                bitmap = bitmap,
                lastRender = info,
                memory = info.memory,
                tier = tier,
                phase = Phase.Ready,
                error = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _state.value = _state.value.copy(phase = Phase.Error, error = failure.message ?: failure.toString())
        } finally {
            _state.value = _state.value.copy(busy = false)
        }
    }

    private fun totalRamBytes(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.totalMem
    }

    /**
     * The canvas decode size the *next* open will use (plan S5 performance
     * group). `preview_long_edge` is a `live=false` field — `spk_open`'s
     * initial delta is the only place it takes effect (`engine.cpp`'s
     * per-session override) — so the UI stores it here rather than sending it
     * through `setParams`, and the clamped range is the schema's
     * (`engine/src/core/params.cpp`: 800..8192).
     */
    fun setPreviewLongEdge(px: Int) {
        previewLongEdge = px.coerceIn(800, 8192)
        _state.value = _state.value.copy(
            message = "canvas resolution ${previewLongEdge}px takes effect on the next open",
        )
    }

    override fun onCleared() {
        // The engine outlives this view model on purpose (an export worker may
        // still be rendering); only the session this view model opened is the
        // process's to close, and the next open replaces it.
        saveEdits()
        session = null
    }

    companion object {
        const val CACHE_CAP_BYTES = 512L shl 20
    }
}

/** The geometry fields, which the crop page owns and the edits file stores. */
internal fun JSONObject.filterGeometry(): JSONObject {
    val keys = listOf(
        "geometry_crop_x",
        "geometry_crop_y",
        "geometry_crop_w",
        "geometry_crop_h",
        "geometry_rotation_deg",
        "geometry_quarter_turns",
        "geometry_flip_h",
        "geometry_flip_v",
    )
    return JSONObject().apply { keys.forEach { if (has(it)) put(it, get(it)) } }
}
