package com.spektralab.app.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektralab.app.EditorViewModel
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The editor's own session flow, without Compose in the way.
 *
 * `EditorFlowTest#cropThenExportPublishesTheFile` passes when it runs alone and
 * fails when it runs second — and the only thing the case before it does
 * differently is open a photograph. `EngineSetParamsInstrumentedTest` showed
 * the *engine* is fine with a second session, so the question left is this one:
 * **can the editor open a second frame and keep editing it?** Opening another
 * photograph is the most ordinary thing an editor does.
 *
 * Green here means the fault is in the Compose test's interaction and the
 * product is fine. Red means a real defect in `EditorViewModel`, and the flow
 * test was the first thing to notice it.
 */
@RunWith(AndroidJUnit4::class)
class EditorSessionFlowInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context

    private fun fixture(name: String): Uri {
        val target = File(context.filesDir, name)
        testContext.assets.open("fixture.png").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(target)
    }

    /** Pumps the main thread until [condition] holds, or the deadline passes. */
    private fun await(what: String, model: EditorViewModel, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (SystemClock.uptimeMillis() < deadline && !condition()) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        assertTrue(
            "$what; phase=${model.state.value.phase} busy=${model.state.value.busy} " +
                "error=${model.state.value.error}",
            condition(),
        )
    }

    /**
     * Idle *and* showing [name], because the state keeps the previous
     * photograph's bitmap while `open` runs — so "a frame is present" is true
     * on a stale picture the instant it is asked (AGENTS.md trap 24).
     */
    private fun awaitIdle(what: String, model: EditorViewModel, name: String) = await(what, model) {
        model.state.value.source?.name == name && model.state.value.bitmap != null &&
            !model.state.value.busy && model.state.value.phase == EditorViewModel.Phase.Ready
    }

    @Test
    fun aSecondFrameCanBeOpenedAndEdited() {
        val model = EditorViewModel(context.applicationContext as Application)
        model.boot()
        await("the engine did not start", model) {
            model.state.value.phase == EditorViewModel.Phase.Ready
        }

        // First frame, the way the import case does it.
        val first = fixture("flow-import-like.png")
        model.open(first, first.lastPathSegment ?: "first.png")
        awaitIdle("the first frame did not render", model, "flow-import-like.png")

        // Does editing work at all in this harness? Asked on the *first*
        // frame, so that a failure here and a failure after the second open
        // mean two different things.
        model.setParams(JSONObject().put("geometry_crop_w", 0.5))
        await("the crop did not reach the engine on the FIRST frame", model) {
            model.state.value.params?.optDouble("geometry_crop_w", 1.0) == 0.5
        }

        // Second frame: the ordinary "open another photograph" action.
        val second = fixture("flow-crop-like.png")
        model.open(second, second.lastPathSegment ?: "second.png")
        awaitIdle("the second frame did not render", model, "flow-crop-like.png")

        // ...and it must still be editable.
        model.setParams(
            JSONObject().apply {
                put("geometry_crop_x", 0.1)
                put("geometry_crop_y", 0.1)
                put("geometry_crop_w", 0.8)
                put("geometry_crop_h", 0.8)
            },
        )
        await("the crop did not reach the engine on the second frame", model) {
            model.state.value.params?.optDouble("geometry_crop_w", 1.0) == 0.8
        }
    }
}
