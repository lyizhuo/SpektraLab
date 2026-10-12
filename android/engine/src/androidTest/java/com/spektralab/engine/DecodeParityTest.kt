package com.spektralab.engine

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * S3's acceptance test: what the device's decode says about a file must be
 * what `spektralab-host probe` says about the same file on Linux.
 *
 * The expectation is *not* hard-coded here — a RAW's EXIF is the file's, not
 * the build's — so the test takes it from instrumentation arguments:
 *
 * ```
 * adb shell am instrument -w -e raw /sdcard/Download/IMG_0001.ARW \
 *   -e raw_probe '{"kind":"raw","width":6000,"height":4000,"make":"SONY",...}' \
 *   -e raw_md5 <md5 of the same file on the host> \
 *   com.spektralab.engine.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * `raw_probe` is the JSON `spektralab-host probe` printed for that file on the
 * Linux side; `android/TESTING.md` has the one-liner that produces it. With no
 * `-e raw` the test is skipped (assumption failed) rather than failed, because
 * a camera file cannot live in this repository.
 *
 * The repository fixture (a linear ProPhoto TIFF) is checked unconditionally:
 * it is a real file with a real profile, and its size and depth must survive
 * the decode.
 */
@RunWith(AndroidJUnit4::class)
class DecodeParityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun copyAsset(path: String): File {
        val target = File(context.cacheDir, File(path).name)
        if (!target.isFile) {
            assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        return target
    }

    private fun descriptor(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()

    @Test
    fun repositoryFixtureMatchesItsHostProbe() = runBlocking {
        val client = EngineClient(EngineAssets.ensure(context, "test").absolutePath)
        try {
            client.start()
            val file = copyAsset("fixtures/portrait_leaves_16bit.tif")
            val probe = client.probe(descriptor(file), file.name)
            assertTrue("probe failed: $probe", probe.optBoolean("ok"))
            assertEquals("tiff", probe.optString("kind"))
            assertEquals(667, probe.optInt("width"))
            assertEquals(1000, probe.optInt("height"))

            val session = client.open(descriptor(file), file.name, halfSize = false)
            assertEquals(probe.optInt("width"), session.width)
            assertEquals(probe.optInt("height"), session.height)
            val (bitmap, _) = session.renderInto("live", reprint = false, reuse = null)
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
        } finally {
            client.close()
        }
    }

    @Test
    fun rawMatchesTheHostProbe() = runBlocking {
        val rawPath = arguments.getString("raw")
        val expectedJson = arguments.getString("raw_probe")
        assumeTrue("no -e raw passed: this test needs a camera file", rawPath != null && expectedJson != null)
        val raw = File(rawPath)
        assumeTrue("no such file on the device: $rawPath", raw.isFile)

        val expected = JSONObject(expectedJson!!)
        val client = EngineClient(EngineAssets.ensure(context, "test").absolutePath)
        try {
            client.start()
            val probe = client.probe(descriptor(raw), raw.name)
            assertTrue("probe failed: $probe", probe.optBoolean("ok"))
            assertEquals("kind", expected.optString("kind"), probe.optString("kind"))
            assertEquals("width", expected.optInt("width"), probe.optInt("width"))
            assertEquals("height", expected.optInt("height"), probe.optInt("height"))
            for (field in listOf("make", "model", "datetime_original")) {
                if (expected.has(field)) {
                    assertEquals(field, expected.optString(field), probe.optString(field))
                }
            }
            for (field in listOf("iso", "shutter_s", "aperture", "focal_mm", "orientation")) {
                if (expected.has(field)) {
                    assertEquals(field, expected.optDouble(field), probe.optDouble(field), 1e-6)
                }
            }

            // Both decodes must work, and the half-size one must actually be
            // the smaller frame: a flag that is accepted and ignored is worse
            // than a flag that is refused.
            val full = client.open(descriptor(raw), raw.name, halfSize = false)
            val fullEdge = maxOf(full.width, full.height)
            client.closeSession()
            val half = client.open(descriptor(raw), raw.name, halfSize = true)
            val halfEdge = maxOf(half.width, half.height)
            assertTrue("half-size decode did not halve the frame ($fullEdge -> $halfEdge)", halfEdge < fullEdge)
            assertTrue("half-size decode lost too much ($fullEdge -> $halfEdge)", halfEdge >= fullEdge / 3)
        } finally {
            client.close()
        }
    }

    /**
     * The plan's S7 fixture is a 32-bit *float* TIFF with predictor 3, which
     * the repository's TIFF reader refuses ("TIFF predictor 3 not supported"):
     * it is a probe-only file, and the render fixture beside it
     * (`portrait_leaves_16bit.tif`) is the one the render paths use. Asserting
     * the refusal here is what keeps that from looking like a decode bug on
     * whichever platform hits it first.
     */
    @Test
    fun floatPredictorTiffIsProbedButNotDecoded() = runBlocking {
        val client = EngineClient(EngineAssets.ensure(context, "test").absolutePath)
        try {
            client.start()
            val file = copyAsset("fixtures/portrait_leaves_32bit_linear_prophoto_rgb.tif")
            val probe = client.probe(descriptor(file), file.name)
            assertTrue("probe failed: $probe", probe.optBoolean("ok"))
            assertEquals(667, probe.optInt("width"))
            assertEquals(200, probe.optInt("iso"))

            var refused = false
            try {
                client.open(descriptor(file), file.name, halfSize = false)
            } catch (failure: EngineException) {
                refused = true
            }
            assertTrue("a float-predictor TIFF must be refused rather than decoded wrong", refused)
        } finally {
            client.close()
        }
    }

    @Test
    fun whiteBalanceAtDecodeIsRawOnly() = runBlocking {
        val client = EngineClient(EngineAssets.ensure(context, "test").absolutePath)
        try {
            client.start()
            val file = copyAsset("fixtures/portrait_leaves_16bit.tif")
            var refused = false
            try {
                client.open(
                    descriptor(file),
                    file.name,
                    halfSize = false,
                    paramsDelta = JSONObject().put(
                        "decode",
                        JSONObject().put("white_balance", true).put("temperature_k", 5200.0).put("tint", 8.0),
                    ),
                )
            } catch (failure: EngineException) {
                refused = true
            }
            assertTrue("a decode-time white balance on a TIFF must be refused", refused)
        } finally {
            client.close()
        }
    }
}
