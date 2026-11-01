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
import com.spektralab.app.ui.EditorScreen
import com.spektralab.app.ui.StripEntry
import kotlinx.coroutines.Dispatchers
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

    /** Where the export page's `ACTION_OPEN_DOCUMENT_TREE` result goes. */
    private var exportDirectoryCallback: ((Uri) -> Unit)? = null

    private val pickImages = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (uri in uris) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            addToStrip(uri)
        }
        uris.lastOrNull()?.let { viewModel.open(it, nameOf(it)) }
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
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        EditorScreen(
                            viewModel = viewModel,
                            strip = strip,
                            onAddPhotos = { pickImages.launch(IMAGE_TYPES) },
                            onAddFolder = {
                                exportDirectoryCallback = null
                                pickFolder.launch(null)
                            },
                            onPickExportDirectory = { onPicked ->
                                exportDirectoryCallback = onPicked
                                pickFolder.launch(null)
                            },
                        )

                        // C1 owns the camera now (its 相机 button), which is
                        // where the prototype puts it. The floating button was
                        // a placeholder for exactly this and is gone with the
                        // screen that replaced it.
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

        /** Plan S5 item 1's traversal list. */
        val RAW_EXTENSIONS = setOf(
            "arw", "nef", "cr2", "cr3", "dng", "raf", "orf", "rw2", "pef",
            "jpg", "jpeg", "png", "tif", "tiff",
        )

        /** Plan S5's LRU ceiling for the thumbnail wall. */
        const val STRIP_LIMIT = 256
    }
}
