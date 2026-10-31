package com.spektralab.app.export

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektralab.engine.EngineAssets
import com.spektralab.engine.EngineClient
import com.spektralab.engine.HeifNative
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A real frame, rendered and exported as HEIC, end to end.
 *
 * `HeifEncodeInstrumentedTest` proves the encoder: given pixels and a profile
 * it produces a correct file. It cannot see the thing this test exists for —
 * that `exportImage`'s `heic10` branch actually reaches the encoder with the
 * *render's* pixels and the *requested space's* profile. That is glue, and glue
 * is exactly what a green unit test does not cover (AGENTS.md trap 16).
 *
 * The profile check is the point. ProPhoto's ICC is **synthesised at runtime**
 * (`engine/host/output.cpp:406`, `make_icc`), so a file carrying it proves the
 * whole chain: the space the caller asked for, the transform that resolved it,
 * and the `prof` box libheif wrote. A constant profile would pass a
 * "profile present" assertion and fail this one.
 *
 * The render is `preview` tier with a long-edge of 256 and a half-size open.
 * Not timidity: this runs on an emulator whose Vulkan is software, and a
 * full-tier render of the fixture takes longer than the whole test budget. The
 * export path is the same code at every tier.
 */
@RunWith(AndroidJUnit4::class)
class HeicExportInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context

    private lateinit var client: EngineClient

    @Before
    fun setUp() {
        // The encoder is opt-in at build time; when it is absent there is
        // nothing to verify and a failure here would be about the build
        // configuration rather than about the export path.
        assumeTrue("this build has no HEIC encoder", HeifNative.available)
        client = EngineClient(EngineAssets.ensure(context).absolutePath)
    }

    @After
    fun tearDown() {
        if (::client.isInitialized) client.close()
    }

    private fun fixtureFile(): File {
        val target = File(context.filesDir, "heic-export-fixture.png")
        testContext.assets.open("fixture.png").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    /** Big-endian 32-bit read, for box and ICC lengths. */
    private fun be32(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    private fun indexOf(haystack: ByteArray, needle: String): Int {
        val pattern = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..haystack.size - pattern.size) {
            for (j in pattern.indices) {
                if (haystack[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun exportAs(space: String, into: File): com.spektralab.engine.ExportInfo = runBlocking {
        client.start()
        val source = fixtureFile()
        val fd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        val session = try {
            client.open(fd.detachFd(), source.name, halfSize = true)
        } catch (failure: Exception) {
            fd.close()
            throw failure
        }
        return@runBlocking try {
            session.export(
                into.path,
                JSONObject().apply {
                    put("format", "heic10")
                    put("color_space", space)
                    put("tier", "preview")
                    put("long_edge", 256)
                    put("jpeg_quality", 90)
                },
            )
        } finally {
            client.closeSession()
        }
    }

    @Test
    fun aRenderedFrameExportsAsHeicWithTheProphotoProfileInside() {
        val target = File(context.cacheDir, "heic-prophoto.heic").apply { delete() }
        exportAs("ProPhoto RGB", target)

        assertTrue("the export wrote no file at ${target.path}", target.isFile)
        val bytes = target.readBytes()
        assertTrue("the export wrote an empty file", bytes.size > 1_000)

        // ISO-BMFF, and `heix` is the >8-bit HEVC image brand — the file
        // saying it holds more than 8 bits per channel.
        assertTrue("no ftyp box", indexOf(bytes, "ftyp") in 0..8)
        assertTrue("not the >8-bit HEVC brand", indexOf(bytes, "heix") >= 0)

        // The `colr` box, typed `prof`, holding an ICC profile whose declared
        // length fills its payload. A profile that is present but truncated
        // would satisfy a substring check and not this one.
        val prof = indexOf(bytes, "prof")
        assertTrue("no `prof` box: the colour is not carried as an ICC profile", prof >= 0)

        val iccStart = prof + 4
        val declared = be32(bytes, iccStart)
        val boxSize = be32(bytes, prof - 8)
        val payload = boxSize - 12   // size(4) + 'colr'(4) + 'prof'(4)
        assertTrue(
            "the ICC profile is truncated: it declares $declared bytes, the box carries $payload",
            declared in 1..payload,
        )
        assertTrue("the ICC profile is too small to be a colour profile: $declared", declared > 100)
    }

    @Test
    fun theExportReportsHowMuchTheDestinationCouldNotHold() {
        // The row under the size line. Two spaces on the same frame, and the
        // assertion that makes it a measurement rather than a decoration:
        // ProPhoto's gamut contains sRGB's, so it cannot have *more* pixels
        // outside. A constant report — or one computed from the wrong buffer —
        // fails this.
        val prophoto = exportAs("ProPhoto RGB", File(context.cacheDir, "heic-gamut-p.heic").apply { delete() })
        val srgb = exportAs("sRGB", File(context.cacheDir, "heic-gamut-s.heic").apply { delete() })

        assertTrue("the engine reported no pixels for ProPhoto", prophoto.gamut.total > 0)
        assertTrue("the engine reported no pixels for sRGB", srgb.gamut.total > 0)

        for ((name, info) in listOf("ProPhoto" to prophoto, "sRGB" to srgb)) {
            val g = info.gamut
            assertTrue("$name: outside exceeds total", g.outside <= g.total)
            assertTrue("$name: mapped exceeds outside", g.mapped <= g.outside)
            assertTrue("$name: clipped exceeds outside", g.clipped <= g.outside)
            assertTrue("$name: mapped + clipped exceeds outside", g.mapped + g.clipped <= g.outside)
        }

        assertTrue(
            "ProPhoto reported more out-of-gamut pixels than sRGB (${prophoto.gamut.outside} vs " +
                "${srgb.gamut.outside}); the report is not being computed in the destination space",
            prophoto.gamut.outside <= srgb.gamut.outside,
        )
    }
    @Test
    fun theProfileInTheFileFollowsTheSpaceThatWasAskedFor() {
        // Two spaces, two files. If the profile were a constant — the way a
        // "profile present" check would stay green — these payloads would be
        // identical, and this is the assertion that would catch it.
        val prophoto = File(context.cacheDir, "heic-prophoto-2.heic").apply { delete() }
        val srgb = File(context.cacheDir, "heic-srgb.heic").apply { delete() }

        exportAs("ProPhoto RGB", prophoto)
        exportAs("sRGB", srgb)

        val a = prophoto.readBytes()
        val b = srgb.readBytes()
        val profA = indexOf(a, "prof")
        val profB = indexOf(b, "prof")
        assertTrue("no profile in the ProPhoto export", profA >= 0)
        assertTrue("no profile in the sRGB export", profB >= 0)

        val sizeA = be32(a, profA + 4)
        val sizeB = be32(b, profB + 4)
        val profileA = a.copyOfRange(profA + 4, profA + 4 + minOf(sizeA, 4096))
        val profileB = b.copyOfRange(profB + 4, profB + 4 + minOf(sizeB, 4096))

        assertTrue(
            "both exports carry byte-identical profiles, so the space was not applied",
            !profileA.contentEquals(profileB),
        )
    }
}
