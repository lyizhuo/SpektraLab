package com.spektralab.app.export

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektralab.app.EditorViewModel
import com.spektralab.engine.RenderInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * S6's acceptance, on a real device: an export reaches the album with the byte
 * count the engine reported, the edits come back after the app is gone, twenty
 * parameter changes undo back to where they started, and a cancelled export
 * leaves neither a pending MediaStore row nor a temporary file.
 *
 * The tests drive [EditorViewModel] rather than the UI (the S5 flows own the
 * screens); what they are about is the state machine underneath it — a
 * background worker, a per-photograph edit file, and a session the export
 * closes and the canvas re-opens.
 */
@RunWith(AndroidJUnit4::class)
class ExportPersistenceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A fresh view model per test: the edit store is keyed by the file. */
    private fun viewModel(): EditorViewModel =
        EditorViewModel(context.applicationContext as android.app.Application)

    private fun pushFixture(name: String): Uri {
        val target = File(context.filesDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.png").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(target)
    }

    private fun awaitCondition(what: String, timeoutMillis: Long = 180_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(200)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun awaitFrame(model: EditorViewModel, what: String = "a frame") {
        awaitCondition("$what (bitmap + idle)") {
            val state = model.state.value
            state.bitmap != null && !state.busy && state.phase == EditorViewModel.Phase.Ready
        }
    }

    private fun albumRows(displayNamePrefix: String = "SpektraLab_"): List<Pair<Uri, Boolean>> {
        val rows = mutableListOf<Pair<Uri, Boolean>>()
        context.contentResolver.query(
            android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.IS_PENDING),
            "${android.provider.MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("$displayNamePrefix%"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val pending = !cursor.isNull(1) && cursor.getInt(1) == 1
                rows += Uri.withAppendedPath(
                    android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    id.toString(),
                ) to pending
            }
        }
        return rows
    }

    @Test
    fun exportReachesTheAlbumWithTheEnginesByteCount() {
        val model = viewModel()
        model.boot()
        awaitCondition("the engine") { model.state.value.capabilities != null }
        val uri = pushFixture("export-album.png")
        model.open(uri, "export-album.png")
        awaitFrame(model, "the canvas frame")

        model.export(ExportSpec(format = ExportFormat.Tiff16), ExportTarget.Album)
        awaitCondition("the export to finish", timeoutMillis = 240_000) {
            model.state.value.exportOutcome != null
        }
        val outcome = model.state.value.exportOutcome
        assertTrue("export failed: $outcome", outcome is ExportOutcome.Success)
        val success = outcome as ExportOutcome.Success
        assertTrue("the export held no bytes: $success", success.bytes > 1024)
        assertTrue("no size was reported: $success", success.width > 0 && success.height > 0)

        // The published file must be the size the engine wrote, and visible:
        // a pending row is a file the user cannot see yet.
        val published = Uri.parse(success.uri)
        val length = context.contentResolver.openAssetFileDescriptor(published, "r")?.use { it.length } ?: -1
        assertEquals("published bytes differ from the engine's report", success.bytes, length)
        assertTrue("the published row is still pending", albumRows().none { it.first == published && it.second })

        context.contentResolver.delete(published, null, null)
    }

    @Test
    fun editsComeBackAfterTheViewModelIsGone() {
        val uri = pushFixture("edit-restore.png")
        val first = viewModel()
        first.boot()
        awaitCondition("the engine") { first.state.value.capabilities != null }
        first.open(uri, "edit-restore.png")
        awaitFrame(first, "the first frame")

        first.setParams(JSONObject().put("print_exposure", 4.5))
        awaitCondition("the parameter to reach the engine") {
            first.state.value.params?.optDouble("print_exposure", 0.0)?.let { kotlin.math.abs(it - 4.5) < 1e-6 } == true
        }

        // The app "restarts": a new view model over the same file.
        val second = viewModel()
        second.boot()
        awaitCondition("the second engine") { second.state.value.capabilities != null }
        second.open(uri, "edit-restore.png")
        awaitFrame(second, "the restored frame")
        val restored = second.state.value.params?.optDouble("print_exposure", 0.0) ?: 0.0
        assertEquals("the stored edit did not come back", 4.5, restored, 1e-6)
    }

    @Test
    fun twentyChangesUndoToTheStart() {
        val model = viewModel()
        model.boot()
        awaitCondition("the engine") { model.state.value.capabilities != null }
        val uri = pushFixture("undo-stack.png")
        model.open(uri, "undo-stack.png")
        awaitFrame(model, "the frame before the edits")

        // Four live fields cycled five times: each change differs from the one
        // before it in *which* fields it names, so the 400 ms coalescing window
        // (UndoStack) cannot merge two steps into one.
        val changes = listOf(
            "print_exposure" to 1.4,
            "m_filter_shift" to 0.10,
            "y_filter_shift" to -0.10,
            "preflash_exposure" to 0.05,
        )
        val expected = model.state.value.params?.optDouble("print_exposure", 0.0) ?: 0.0
        repeat(5) { round ->
            for ((field, base) in changes) {
                val value = base + round * 0.01
                model.setParams(JSONObject().put(field, value))
                awaitCondition("$field=$value to reach the engine") {
                    kotlin.math.abs((model.state.value.params?.optDouble(field, Double.NaN) ?: Double.NaN) - value) < 1e-6
                }
            }
        }
        assertTrue("the undo stack did not fill: ${model.state.value.canUndo}", model.state.value.canUndo)

        repeat(20) { index ->
            val previous = model.state.value.lastRender
            model.undo()
            if (index < 19) {
                awaitCondition("undo ${index + 1} to render", timeoutMillis = 240_000) {
                    model.state.value.lastRender !== previous
                }
            }
        }
        awaitCondition("the last undo to settle", timeoutMillis = 240_000) { !model.state.value.busy }
        val backAtStart = model.state.value.params?.optDouble("print_exposure", 0.0) ?: 0.0
        assertEquals("twenty undos did not return to the starting exposure", expected, backAtStart, 1e-6)
    }

    @Test
    fun cancellingAnExportLeavesNothingBehind() {
        val model = viewModel()
        model.boot()
        awaitCondition("the engine") { model.state.value.capabilities != null }
        val uri = pushFixture("export-cancel.png")
        model.open(uri, "export-cancel.png")
        awaitFrame(model, "the canvas frame")

        val before = albumRows().size
        model.export(ExportSpec(format = ExportFormat.Png16), ExportTarget.Album)
        // Cancel while the render is in flight: `spk_cancel` unwinds it at the
        // next node boundary.
        SystemClock.sleep(300)
        model.cancelExport()

        awaitCondition("the cancel to settle", timeoutMillis = 240_000) { !model.state.value.busy }
        // Neither a published row nor a pending one may survive the cancel.
        assertEquals("a MediaStore row survived a cancelled export", before, albumRows().size)
        assertTrue("a pending row survived a cancelled export", albumRows().none { it.second })
        val leftovers = File(context.cacheDir, "export").listFiles()?.filter { it.name.endsWith(".part") } ?: emptyList()
        assertTrue("temporary export files survived: $leftovers", leftovers.isEmpty())
        assertNotNull(model.state.value.exportOutcome)
    }
}
