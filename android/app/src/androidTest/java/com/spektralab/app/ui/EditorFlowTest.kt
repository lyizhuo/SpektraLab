package com.spektralab.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektralab.app.EditorViewModel
import com.spektralab.app.export.ExportOutcome
import com.spektralab.engine.RenderInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The plan's three S5 flows on a real device, driven through the real screen:
 * import & render, a parameter change refreshing the frame, crop & export —
 * plus a screenshot per breakpoint.
 *
 * Three things are deliberate:
 *
 *  * **One engine per run.** The engine is created once and shared (plan D8:
 *    one instance, one worker thread), so the class boots it lazily rather
 *    than standing up a Vulkan device per test. The fixture is a raster pushed
 *    into `filesDir`: it takes the same import path (`sf_decode` →
 *    `spk_open`) a camera file would, without needing a RAW file's licence to
 *    commit.
 *  * **Each test opens its own copy** of the fixture, so the per-photograph
 *    edit store (plan S6, keyed by uri + size + stamp) cannot leak state
 *    between tests.
 *  * **Waits are on the view model's state**, not on the Compose clock: frames
 *    come back from the engine's worker thread, and the tests assert on what
 *    arrived rather than on when the UI happened to redraw.
 */
@RunWith(AndroidJUnit4::class)
class EditorFlowTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val viewModel: EditorViewModel by lazy { sharedViewModel(context.applicationContext as Application) }

    private val fixtureWidth = 192
    private val fixtureHeight = 128

    // --- flow 1: import and render -----------------------------------------

    @Test
    fun importAndRenderShowsAFrame() {
        val uri = pushFixture("flow-import.png")
        show(uri, "flow-import.png")
        viewModel.open(uri, "flow-import.png")
        awaitFrame("the first render")

        val state = viewModel.state.value
        assertEquals(EditorViewModel.Phase.Ready, state.phase)
        assertEquals("live", state.tier)
        assertEquals(fixtureWidth, state.source?.width)
        assertEquals(fixtureHeight, state.source?.height)

        val bitmap = requireNotNull(state.bitmap)
        assertEquals(state.lastRender?.width, bitmap.width)
        assertEquals(state.lastRender?.height, bitmap.height)

        // A developed frame is not a flat card: the histogram has structure.
        val histogram = Histogram.of(bitmap)
        val populated = histogram.red.indices.count { histogram.red[it] + histogram.green[it] + histogram.blue[it] > 0 }
        assertTrue("the render is degenerate: only $populated of 256 levels are used", populated > 8)

        rule.waitForIdle()
        rule.onNodeWithTag("film-strip").assertExists()
        rule.onNodeWithTag("tool-develop").assertExists()
        rule.onNodeWithTag("strip-add-photos").assertExists()
    }

    // --- flow 2: a parameter change refreshes the frame --------------------

    @Test
    fun parameterChangeRefreshesTheFrame() {
        val uri = pushFixture("flow-params.png")
        show(uri, "flow-params.png")
        viewModel.open(uri, "flow-params.png")
        awaitFrame("the first render")
        val before = digest(requireNotNull(viewModel.state.value.bitmap))
        val previousRender = viewModel.state.value.lastRender

        // Through the UI: the Develop tool, the Grain group, its switch.
        rule.onNodeWithTag("tool-develop").performClick()
        rule.onNodeWithTag("group-Grain").performScrollTo().performClick()
        rule.onNodeWithTag("field-${Wire.GrainActive}").performScrollTo().performClick()

        awaitCondition("the grain switch to reach the engine") {
            viewModel.state.value.params?.optBoolean(Wire.GrainActive) == false
        }
        awaitNewRender(previousRender, "the re-develop with grain off")
        val after = digest(requireNotNull(viewModel.state.value.bitmap))
        assertTrue("the frame did not change when grain was switched off", before != after)
    }

    /** The bounds on screen are the schema's, not something the UI invented. */
    @Test
    fun sliderBoundsComeFromTheSchema() {
        val uri = pushFixture("flow-slider.png")
        show(uri, "flow-slider.png")
        viewModel.open(uri, "flow-slider.png")
        awaitFrame("the first render")

        rule.onNodeWithTag("tool-develop").performClick()
        rule.onNodeWithTag("group-Enlarger").performScrollTo().performClick()
        val node = rule.onNodeWithTag("field-${Wire.PrintExposure}").performScrollTo().fetchSemanticsNode()
        val range = node.config[SemanticsProperties.ProgressBarRangeInfo]
        // params_schema.json: print_exposure is a float with range [0.05, 20].
        // The bounds are asserted, not the current value: the resolved value
        // belongs to the stock pair the engine has loaded, while the bounds are
        // the schema's and must not come from anywhere else.
        assertEquals(0.05f..20f, range.range)
        assertTrue("the slider is not inside its own range: $range", range.current in range.range)
        assertEquals(0, range.steps)
    }

    // --- flow 3: crop, then export ----------------------------------------

    @Test
    fun cropThenExportPublishesTheFile() {
        val uri = pushFixture("flow-crop.png")
        show(uri, "flow-crop.png")
        viewModel.open(uri, "flow-crop.png")
        awaitFrame("the first render")

        // Start from a crop the map can actually drag (these are the very
        // fields the drag writes, so it is the same path).
        viewModel.setParams(
            JSONObject()
                .put(Wire.CropX, 0.1)
                .put(Wire.CropY, 0.1)
                .put(Wire.CropW, 0.8)
                .put(Wire.CropH, 0.8),
        )
        val croppedRender = viewModel.state.value.lastRender
        awaitCondition("the crop to reach the engine") {
            viewModel.state.value.params?.optDouble(Wire.CropW) == 0.8
        }
        awaitNewRender(croppedRender, "the re-develop after the crop")

        rule.onNodeWithTag("tool-crop").performClick()
        rule.onNodeWithTag("crop-map").assertExists()

        // Drag the rectangle up and left. The geometry is the shoot layer, so
        // nothing moves until the Re-develop button hands it over.
        rule.onNodeWithTag("crop-map").performTouchInput {
            down(center)
            moveBy(Offset(-30f, -30f))
            moveBy(Offset(-20f, -20f))
            up()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("crop-banner").assertExists()
        val beforeRedevelop = viewModel.state.value.lastRender
        rule.onNodeWithTag("crop-redevelop").performScrollTo().performClick()

        awaitCondition("the re-developed crop to reach the engine") {
            (viewModel.state.value.params?.optDouble(Wire.CropX) ?: 1.0) < 0.1
        }
        awaitNewRender(beforeRedevelop, "the render after the re-develop")

        viewModel.clearError()
        rule.onNodeWithTag("tool-export").performClick()
        rule.onNodeWithTag("export-run").performScrollTo().performClick()
        awaitCondition("the export to finish", timeoutMillis = 240_000) {
            viewModel.state.value.exportOutcome != null
        }

        val outcome = viewModel.state.value.exportOutcome
        assertTrue("export did not succeed: $outcome", outcome is ExportOutcome.Success)
        val success = outcome as ExportOutcome.Success
        assertTrue("the published file is empty", success.bytes > 0)
        assertTrue("the published file is not in MediaStore: ${success.uri}", success.uri.startsWith("content://"))
        assertTrue("the crop did not reach the export: ${success.width}×${success.height}", success.height < fixtureHeight)

        // Leave nothing behind in the user's album.
        context.contentResolver.delete(Uri.parse(success.uri), null, null)
    }

    // --- breakpoints and screenshots ---------------------------------------

    @Test
    fun phoneBreakpointScreenshot() {
        val uri = pushFixture("shot-phone.png")
        rule.setContent { AtWidth(420.dp, uri, "shot-phone.png") }
        viewModel.open(uri, "shot-phone.png")
        awaitFrame("the phone-width render")
        rule.onNodeWithTag("canvas-phone").assertExists()
        rule.onNodeWithTag("canvas-wide").assertDoesNotExist()
        capture("phone-420dp.png")
    }

    @Test
    fun tabletBreakpointScreenshot() {
        val uri = pushFixture("shot-tablet.png")
        rule.setContent { AtWidth(1000.dp, uri, "shot-tablet.png") }
        viewModel.open(uri, "shot-tablet.png")
        awaitFrame("the tablet-width render")
        rule.onNodeWithTag("canvas-wide").assertExists()
        rule.onNodeWithTag("canvas-phone").assertDoesNotExist()
        capture("tablet-1000dp.png")
    }

    // --- helpers -----------------------------------------------------------

    /**
     * The screen at a fixed width. A device narrower than [width] gets the
     * layout drawn smaller rather than clipped, so one test can show both
     * breakpoints on whatever hardware is attached (`AndroidComposeTestRule`
     * renders into the real window).
     */
    @Composable
    private fun AtWidth(width: Dp, uri: Uri, name: String) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val scale = (maxWidth / width).coerceAtMost(1f)
                Box(
                    Modifier
                        .requiredSize(width, maxHeight / scale)
                        .graphicsLayer(scaleX = scale, scaleY = scale, transformOrigin = TransformOrigin(0f, 0f)),
                ) {
                    screen(uri, name)
                }
            }
        }
    }

    private fun show(uri: Uri, name: String) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { screen(uri, name) }
        }
    }

    @Composable
    private fun screen(uri: Uri, name: String) {
        EditorScreen(
            viewModel = viewModel,
            strip = listOf(StripEntry(uri, name)),
            onAddPhotos = {},
            onAddFolder = {},
            onPickExportDirectory = {},
        )
    }

    private fun pushFixture(name: String): Uri {
        val target = File(context.filesDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.png").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(target)
    }

    private fun awaitFrame(what: String, timeoutMillis: Long = 180_000) {
        awaitCondition("$what to produce a frame", timeoutMillis) {
            val state = viewModel.state.value
            state.bitmap != null && !state.busy && state.phase == EditorViewModel.Phase.Ready
        }
    }

    /**
     * Waits for a render that is *not* the one [previous]: the frames are
     * reused buffers, so the identity of `lastRender` is what tells the two
     * apart.
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
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting for $what; state=${viewModel.state.value}")
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val parent = context.getExternalFilesDir(null) ?: context.filesDir
        val directory = File(parent, "screenshots").apply { mkdirs() }
        val file = File(directory, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("nothing was written to $file", file.length() > 0 && bitmap.width > 0)
    }

    /** A content hash: the frames reuse their buffer, so identity will not do. */
    private fun digest(bitmap: Bitmap): Long {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var hash = 1125899906842597L
        for (pixel in pixels) hash = 31 * hash + pixel
        return hash
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
                    Thread.sleep(POLL_MS)
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
