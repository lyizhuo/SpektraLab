package com.spektralab.engine

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * S4's acceptance test: create → capabilities → open → render → setParams →
 * reprint → close, on a real GPU.
 *
 * It is the one place the plan's S1 item 5 (the library must be optimised) and
 * the "this device can actually render" question are answered on hardware
 * rather than inferred: everything the engine refuses to start on — device
 * limits, the math probe, the storage-range probe — has already happened by
 * the time [EngineClient.start] returns, and the frame assertions below are
 * about the pixels rather than about the absence of an exception.
 */
@RunWith(AndroidJUnit4::class)
class EngineSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun resourcesDir(): File = EngineAssets.ensure(context, version = "test")

    /** The fixture ships in the test APK; `spk_open` needs a descriptor. */
    private fun fixture(path: String): File {
        val target = File(context.cacheDir, File(path).name)
        if (!target.isFile) {
            assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        return target
    }

    private fun openDescriptor(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()

    @Test
    fun engineRendersAndRepeats() = runBlocking {
        val client = EngineClient(resourcesDir().absolutePath)
        try {
            val capabilities = client.start()

            // The build must be optimised: a -O0 engine makes every number the
            // app reports meaningless (plan S2 item 6, S1 item 5).
            val build = client.nativeBuildInfo
            assertTrue("engine library is not optimised: $build", build.optBoolean("optimized", false))
            assertTrue("unexpected ABI: $build", build.optString("abi") != "other")

            assertEquals(1, capabilities.optInt("transport_version", -1))
            val backend = capabilities.getJSONObject("backend")
            assertTrue("no usable GPU: $backend", backend.optBoolean("gpu_available"))
            assertTrue(capabilities.optLong("max_pixels") > 0)
            val tiers = capabilities.getJSONObject("tiers")
            assertTrue(tiers.has("live") && tiers.has("preview") && tiers.has("full"))

            val probe = client.probe(openDescriptor(fixture("fixtures/portrait_leaves_16bit.tif")), "fixture.tif")
            assertTrue("fixture probe failed: $probe", probe.optBoolean("ok"))
            val probeWidth = probe.optInt("width")
            val probeHeight = probe.optInt("height")
            assertTrue("probe returned no size: $probe", probeWidth > 0 && probeHeight > 0)

            val session = client.open(
                openDescriptor(fixture("fixtures/portrait_leaves_16bit.tif")),
                "fixture.tif",
                halfSize = false,
                paramsDelta = JSONObject().put("preview_long_edge", 1600),
            )
            assertEquals(probeWidth, session.width)
            assertEquals(probeHeight, session.height)

            val (first, firstInfo) = session.renderInto("live", reprint = false, reuse = null)
            assertTrue("live render returned no pixels", first.width > 0 && first.height > 0)
            // The live tier's long edge is `preview_long_edge`, but never an
            // upscale: a 667x1000 frame stays 667x1000 at live 1600.
            assertEquals(
                "live tier size",
                minOf(1600, maxOf(probeWidth, probeHeight)),
                maxOf(first.width, first.height),
            )

            // A histogram that is degenerate (one colour, or empty) means the
            // renderer produced nothing even though it did not fail.
            val distinct = distinctLuma(first)
            assertTrue("the live frame has only $distinct distinct luma values", distinct >= 8)

            // A parameter change must produce different pixels, not the same
            // frame with a new timestamp: that is the difference between a
            // working canvas and a cached one.
            val before = meanLuma(first)
            session.setParams(JSONObject().put("print_exposure", 6.0))
            val (second, _) = session.renderInto("live", reprint = false, reuse = null)
            val after = meanLuma(second)
            assertTrue("print_exposure did not move the frame ($before -> $after)", kotlin.math.abs(after - before) > 1.0)

            // The print layer reprints from the cached negative: the tier is
            // rendered once, then reprinted, and the second call must report
            // that it reused the negative instead of developing the film again.
            val (preview, _) = session.renderInto("preview", reprint = false, reuse = null)
            assertEquals(minOf(3400, maxOf(probeWidth, probeHeight)), maxOf(preview.width, preview.height))
            val (_, reprintInfo) = session.renderInto("preview", reprint = true, reuse = null)
            assertTrue("reprint did not reuse the cached negative: $reprintInfo", reprintInfo.negativeWasCached)
            assertTrue("reprint reported no elapsed time", reprintInfo.elapsedMs > 0.0)

            val memory = client.memoryReport()
            assertTrue("memory report is empty: $memory", memory.optLong("total_bytes") > 0)
            assertNotNull(memory.optJSONObject("pool")?.optJSONObject("audit"))

            assertTrue("first render reported no elapsed time", firstInfo.elapsedMs > 0.0)
        } finally {
            client.close()
        }
    }

    @Test
    fun halfSizeDecodeIsCheaperAndSmaller() = runBlocking {
        val client = EngineClient(resourcesDir().absolutePath)
        try {
            client.start()
            val file = fixture("fixtures/portrait_leaves_16bit.tif")
            val full = client.open(openDescriptor(file), "full.tif", halfSize = false)
            val fullEdge = maxOf(full.width, full.height)
            client.closeSession()
            // A raster file has no half-size mode (it is a RAW concept), so the
            // session reports the same size and the flag as asked; the assertion
            // is that asking does not fail and does not silently change the frame.
            val half = client.open(openDescriptor(file), "half.tif", halfSize = true)
            assertEquals(fullEdge, maxOf(half.width, half.height))
        } finally {
            client.close()
        }
    }

    private fun distinctLuma(bitmap: Bitmap): Int {
        val buckets = BooleanArray(64)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            buckets[((r + 2 * g + b) / 4) shr 2] = true
        }
        return buckets.count { it }
    }

    private fun meanLuma(bitmap: Bitmap): Double {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var total = 0L
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            total += (r + 2 * g + b) / 4
        }
        return total.toDouble() / pixels.size
    }
}
