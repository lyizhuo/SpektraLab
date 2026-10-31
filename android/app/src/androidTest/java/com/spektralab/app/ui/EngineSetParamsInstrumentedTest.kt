package com.spektralab.app.ui

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektralab.engine.EngineAssets
import com.spektralab.engine.EngineClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `set_params` → `render` through the engine, with no UI in the way.
 *
 * This exists because two cases in `EditorFlowTest` time out waiting for a
 * control's change to reach the engine, and there are two very different
 * explanations: the click never reaches the view model, or the view model sends
 * the parameter and the engine does not apply it. Guessing between them cost
 * more time than this test does, and the wrong guess would have "fixed" the UI
 * for a wire problem.
 *
 * So: the engine's own round trip, asserted directly. If this test is green and
 * the flow test still times out, the fault is in the click path, which is a UI
 * question and a much smaller one.
 */
@RunWith(AndroidJUnit4::class)
class EngineSetParamsInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context

    private lateinit var client: EngineClient

    @Before
    fun setUp() {
        client = EngineClient(EngineAssets.ensure(context).absolutePath)
    }

    @After
    fun tearDown() {
        if (::client.isInitialized) client.close()
    }

    private fun fixture(name: String): File {
        val target = File(context.filesDir, name)
        testContext.assets.open("fixture.png").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    @Test
    fun aParameterTheUiSendsIsAppliedByTheEngine() = runBlocking {
        client.start()
        val source = fixture("setparams-fixture.png")
        val fd = android.os.ParcelFileDescriptor
            .open(source, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val session = client.open(fd.detachFd(), source.name, halfSize = true)
        try {
            session.renderInto("live", reprint = false, reuse = null)

            // The exact call `ToggleField` makes.
            val updated = session.setParams(JSONObject().put("grain_active", false))

            assertEquals(
                "the engine did not apply grain_active=false; this is a wire problem, not a click problem",
                false,
                updated.optBoolean("grain_active", true),
            )
        } finally {
            client.closeSession()
        }
    }

    @Test
    fun aGeometryChangeReachesTheEngineToo() = runBlocking {
        // The crop case in the flow test fails the same way, so the two are
        // checked separately rather than assumed to share a cause.
        client.start()
        val source = fixture("setparams-crop.png")
        val fd = android.os.ParcelFileDescriptor
            .open(source, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val session = client.open(fd.detachFd(), source.name, halfSize = true)
        try {
            session.renderInto("live", reprint = false, reuse = null)
            val updated = session.setParams(
                JSONObject().apply {
                    put("geometry_crop_w", 0.5)
                    put("geometry_crop_h", 0.5)
                },
            )
            assertTrue(
                "the engine did not apply the crop: w=${updated.optDouble("geometry_crop_w")}",
                kotlin.math.abs(updated.optDouble("geometry_crop_w", 1.0) - 0.5) < 1e-6,
            )
        } finally {
            client.closeSession()
        }
    }
}
