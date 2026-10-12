package com.spektralab.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.spektralab.app.ui.EditorScreen
import com.spektralab.app.ui.StripEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.boot()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
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

    private fun String.extension(): String = substringAfterLast('.', "").lowercase()

    private companion object {
        val IMAGE_TYPES = arrayOf("image/*", "application/octet-stream")

        /** Plan S5 item 1's traversal list. */
        val RAW_EXTENSIONS = setOf(
            "arw", "nef", "cr2", "cr3", "dng", "raf", "orf", "rw2", "pef",
            "jpg", "jpeg", "png", "tif", "tiff",
        )

        /** Plan S5's LRU ceiling for the thumbnail wall. */
        const val STRIP_LIMIT = 256
    }
}
