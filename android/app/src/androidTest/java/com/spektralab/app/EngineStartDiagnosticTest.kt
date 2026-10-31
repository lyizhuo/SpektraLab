package com.spektralab.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektralab.engine.EngineAssets
import com.spektralab.engine.EngineClient
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostics for "the engine did not start", not a gate.
 *
 * `EditorFlowTest` asserts that the engine boots; when it fails the assertion
 * says only *that* it failed. This test asks the engine the same question and
 * puts its own answer in the failure message, which is the difference between
 * "the device is not supported" and "the shaders are missing".
 *
 * It is expected to pass only where the engine is expected to run. On an
 * emulator whose Vulkan is a translated `vulkan.ranchu.so`, a failure here is
 * information, not a regression — read the message it prints.
 */
@RunWith(AndroidJUnit4::class)
class EngineStartDiagnosticTest {

    @Test
    fun theEngineReportsWhyItWillNotStart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Which resources the app actually resolves. `EngineAssets` is the same
        // door the ViewModel uses, so this is what the engine would be given.
        val resourcesDir = try {
            EngineAssets.ensure(context).absolutePath
        } catch (e: Exception) {
            throw AssertionError("engine assets could not be extracted: ${e.message}", e)
        }

        val client = EngineClient(resourcesDir)
        val reply = try {
            runBlocking { client.start() }
        } catch (e: Exception) {
            throw AssertionError("engine start failed: ${e.message}", e)
        } finally {
            runCatching { client.close() }
        }

        // A successful start is worth recording too: it says which backend and
        // which build this device got.
        val backend = reply.optJSONObject("backend")
        val core = backend?.optString("render_core").orEmpty()
        val version = reply.optString("transport_version").orEmpty()
        check(core.isNotEmpty()) { "the reply has no backend.render_core: $reply" }
        val gpu = backend?.optString("gpu").orEmpty()
        println("engine started: core=$core gpu=$gpu transport_version=$version")
    }
}
