package com.spektralab.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektralab.app.EditorViewModel
import com.spektralab.app.export.ExportOutcome
import com.spektralab.app.ui.screens.crop.CropScreen
import com.spektralab.app.ui.screens.editor.EditorScreen
import com.spektralab.app.ui.screens.editor.EditorTool
import com.spektralab.app.ui.screens.editor.StripEntry
import com.spektralab.app.ui.screens.export.ExportScreen
import com.spektralab.app.ui.screens.params.ParamsScreen
import com.spektralab.engine.RenderInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The S5 flows on a real device, driven through the real screens.
 *
 * Four things are deliberate:
 *
 *  * **One engine per run.** The engine is created once and shared (plan D8: one
 *    instance, one worker thread), so the class boots it lazily rather than
 *    standing up a Vulkan device per test. The fixture is a raster pushed into
 *    `filesDir`: it takes the same import path a camera file would, without
 *    needing a RAW file's licence to commit.
 *  * **Each test opens its own copy** of the fixture, so the per-photograph edit
 *    store cannot leak state between tests.
 *  * **Waits are on the view model's state**, not on the Compose clock: frames
 *    come back from the engine's worker thread, and the tests assert on what
 *    arrived rather than on when the UI happened to redraw.
 *  * **The screens are driven one at a time.** 参数, 裁剪 and 导出 are full pages
 *    now, not sheets over the editor — which is what the prototype draws — so a
 *    test composes the page it is about instead of hunting for it inside the
 *    editor's tree.
 */
@RunWith(AndroidJUnit4::class)
class EditorFlowTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var viewModel: EditorViewModel

    @Before
    fun setUp() {
        viewModel = sharedViewModel(context.applicationContext as Application)
    }

    private val fixtureWidth = 192
    private val fixtureHeight = 128

    // --- flow 1: import and render -----------------------------------------

    @Test
    fun importAndRenderShowsAFrame() {
        val uri = pushFixture("flow-import.png")
        showEditor(uri, "flow-import.png")
        viewModel.open(uri, "flow-import.png")
        awaitFrame("the first render", "flow-import.png")

        val state = viewModel.state.value
        assertEquals(EditorViewModel.Phase.Ready, state.phase)
        assertEquals("live", state.tier)
        assertEquals(fixtureWidth, state.source?.width)
        assertEquals(fixtureHeight, state.source?.height)

        val bitmap = requireNotNull(state.bitmap)
        assertEquals(state.lastRender?.width, bitmap.width)
        assertEquals(state.lastRender?.height, bitmap.height)
        assertTrue("the frame is a flat card: ${distinctLevels(bitmap)} levels", distinctLevels(bitmap) > 8)

        rule.waitForIdle()
        rule.onNodeWithTag("film-strip").assertExists()
        rule.onNodeWithTag("tool-params").assertExists()
        rule.onNodeWithTag("strip-add-photos").assertExists()
        rule.onNodeWithTag("tool-export").assertExists()
    }

    // --- flow 2: a parameter change refreshes the frame --------------------

    @Test
    fun parameterChangeRefreshesTheFrame() {
        val uri = pushFixture("flow-params.png")
        showParams(uri, "flow-params.png")
        viewModel.open(uri, "flow-params.png")
        awaitFrame("the first render", "flow-params.png")
        val before = digest(requireNotNull(viewModel.state.value.bitmap))
        val previousRender = viewModel.state.value.lastRender

        // 颗粒 is the page's first switch, and it writes `grain_active` — the
        // same field the engine reads. A switch is clickable; a slider's track
        // needs a drag, which is a different assertion (see the bounds test).
        rule.onNodeWithTag("switch-grain").performScrollTo().performClick()

        awaitCondition("the grain switch to reach the engine") {
            viewModel.state.value.params?.optBoolean("grain_active") == false
        }
        awaitNewRender(previousRender, "the re-develop with grain off")
        val after = digest(requireNotNull(viewModel.state.value.bitmap))
        assertTrue("the frame did not change when grain was switched off", before != after)
    }

    /** The labels the page shows are the engine's fields, not invented ones. */
    @Test
    fun theParamsPageOffersTheEnginesFields() {
        val uri = pushFixture("flow-labels.png")
        showParams(uri, "flow-labels.png")
        viewModel.open(uri, "flow-labels.png")
        awaitFrame("the first render", "flow-labels.png")

        rule.onNodeWithTag("params").assertExists()
        rule.onNodeWithTag("params-before").assertExists()
        rule.onNodeWithTag("field-exposure_compensation_ev").assertExists()
        rule.onNodeWithTag("switch-grain").assertExists()
        rule.onNodeWithTag("switch-halation").assertExists()
        // The refused family is shown, disabled, with its reason.
        rule.onNodeWithTag("params-metering").assertExists()

        // 显影后 is the other face of the same page.
        rule.onNodeWithTag("params-after").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("field-print_exposure").assertExists()
    }

    // --- flow 3: crop, then export ----------------------------------------

    @Test
    fun cropThenExportPublishesTheFile() {
        val uri = pushFixture("flow-crop.png")
        showCrop(uri, "flow-crop.png")
        viewModel.open(uri, "flow-crop.png")
        awaitFrame("the first render", "flow-crop.png")

        // Start from a crop the engine can take (these are the very fields the
        // page's pills write, so it is the same path).
        viewModel.setParams(
            JSONObject()
                .put("geometry_crop_x", 0.1)
                .put("geometry_crop_y", 0.1)
                .put("geometry_crop_w", 0.8)
                .put("geometry_crop_h", 0.8),
        )
        val croppedRender = viewModel.state.value.lastRender
        awaitCondition("the crop to reach the engine") {
            viewModel.state.value.params?.optDouble("geometry_crop_w") == 0.8
        }
        awaitNewRender(croppedRender, "the re-develop after the crop")

        rule.waitForIdle()
        rule.onNodeWithTag("crop").assertExists()
        rule.onNodeWithTag("aspect-1:1").performClick()
        rule.waitForIdle()

        showExport(uri, "flow-crop.png")
        rule.waitForIdle()
        rule.onNodeWithTag("export").assertExists()
        rule.onNodeWithTag("export-run").performScrollTo().performClick()
        awaitCondition("the export to finish", timeoutMillis = 240_000) {
            viewModel.state.value.exportOutcome != null
        }

        val outcome = viewModel.state.value.exportOutcome
        assertTrue("export did not succeed: $outcome", outcome is ExportOutcome.Success)
        val success = outcome as ExportOutcome.Success
        assertTrue("the published file is empty", success.bytes > 0)
        assertTrue("the published file is not in MediaStore: ${success.uri}", success.uri.startsWith("content://"))
        assertTrue(
            "the crop did not reach the export: ${success.width}×${success.height}",
            success.height < fixtureHeight,
        )

        // Leave nothing behind in the user's album.
        context.contentResolver.delete(Uri.parse(success.uri), null, null)
    }

    // --- helpers -----------------------------------------------------------

    private fun showEditor(uri: Uri, name: String) {
        rule.setContent {
            EditorScreen(
                state = viewModel.state.value,
                strip = listOf(StripEntry(uri, name)),
                selectedIndex = 0,
                onSelectFrame = {},
                onOpenPhotos = {},
                onClose = {},
                onTool = {},
                tool = null,
                onExport = {},
                sheet = {},
            )
        }
    }

    private fun showParams(uri: Uri, name: String) {
        rule.setContent {
            ParamsScreen(
                params = viewModel.state.value.params,
                sourceName = name,
                onDelta = { viewModel.setParams(it) },
                onBack = {},
            )
        }
    }

    private fun showCrop(uri: Uri, name: String) {
        rule.setContent {
            CropScreen(
                bitmap = viewModel.state.value.bitmap,
                aspect = "原始",
                rotation = 0f,
                outputLabel = "输出 $fixtureWidth × $fixtureHeight",
                onAspect = {},
                onRotate = {},
                onFlip = {},
                onStraighten = {},
                onCancel = {},
                onDone = {},
            )
        }
    }

    private fun showExport(uri: Uri, name: String) {
        rule.setContent {
            ExportScreen(
                state = viewModel.state.value,
                exportCount = 1,
                directoryLabel = null,
                onClose = {},
                onPickDirectory = {},
                onExport = { viewModel.export(it, com.spektralab.app.export.ExportTarget.Album) },
            )
        }
    }

    private fun pushFixture(name: String): Uri {
        val target = File(context.filesDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.png").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(target)
    }

    private fun awaitFrame(what: String, name: String, timeoutMillis: Long = 180_000) {
        // Waiting for "a frame is present" is not enough: `open` is async and
        // the state still holds the previous photograph's bitmap and the Ready
        // phase while it runs, so that condition is true instantly, on a stale
        // picture. Everything after it then races the open. This is why the
        // cases pass alone and fail in a class -- alone there is no previous
        // bitmap for the condition to be satisfied by (AGENTS.md trap 24: the
        // test did not own its inputs, and the failure named the wrong subject).
        awaitCondition("$what to produce a frame of $name", timeoutMillis) {
            val state = viewModel.state.value
            state.source?.name == name && state.bitmap != null && !state.busy &&
                state.phase == EditorViewModel.Phase.Ready
        }
    }

    /**
     * Waits for a render that is *not* the one [previous]: the frames are reused
     * buffers, so the identity of `lastRender` is what tells the two apart.
     */
    private fun awaitNewRender(previous: RenderInfo?, what: String, timeoutMillis: Long = 180_000) {
        awaitCondition(what, timeoutMillis) {
            val state = viewModel.state.value
            state.lastRender !== previous && !state.busy && state.phase == EditorViewModel.Phase.Ready
        }
        assertTrue("$what failed: ${viewModel.state.value.error}", viewModel.state.value.error == null)
    }

    private fun awaitCondition(what: String, timeoutMillis: Long = 120_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting for $what; state=${viewModel.state.value}")
    }

    /** A content hash: the frames reuse their buffer, so identity will not do. */
    private fun digest(bitmap: android.graphics.Bitmap): Long {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var hash = 1125899906842597L
        for (pixel in pixels) hash = 31 * hash + pixel
        return hash
    }

    /** How many luminance levels the frame uses: a flat card uses one or two. */
    private fun distinctLevels(bitmap: android.graphics.Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val levels = IntArray(256)
        for (pixel in pixels) {
            val luma = (
                ((pixel shr 16 and 0xFF) * 299 + (pixel shr 8 and 0xFF) * 587 + (pixel and 0xFF) * 114) / 1000
                )
            levels[luma.coerceIn(0, 255)]++
        }
        return levels.count { it > 0 }
    }

    private companion object {
        const val POLL_MS = 50L
        val shared = AtomicReference<EditorViewModel?>(null)

        /** Boots the engine once for the whole run (plan D8: one instance). */
        fun sharedViewModel(application: Application): EditorViewModel = shared.get() ?: synchronized(shared) {
            shared.get() ?: EditorViewModel(application).also { model ->
                model.boot()
                val deadline = SystemClock.uptimeMillis() + 300_000
                while (SystemClock.uptimeMillis() < deadline &&
                    model.state.value.phase != EditorViewModel.Phase.Ready &&
                    model.state.value.phase != EditorViewModel.Phase.Error
                ) {
                    // `waitForIdleSync` blocks *this* thread until the main
                    // thread is idle, which is what lets the boot coroutine
                    // resume there. Sleeping instead starves it: the continuation
                    // is queued on the main dispatcher, so a test that waits by
                    // sleeping waits forever.
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                }
                assertTrue(
                    "the engine did not start: ${model.state.value.error}",
                    model.state.value.phase == EditorViewModel.Phase.Ready,
                )
                shared.set(model)
            }
        }
    }
}
