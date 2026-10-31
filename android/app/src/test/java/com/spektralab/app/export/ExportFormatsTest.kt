package com.spektralab.app.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the export picker may offer.
 *
 * This exists because the enum and the pipeline can disagree, and they did: the
 * HEIC values were added to `ExportFormat` before there was anything that could
 * write one, and the picker rendered `ExportFormat.entries` — so the app showed
 * two chips that would fail after a full render. A format that cannot be
 * produced must not be selectable, and it must say why rather than vanish.
 */
class ExportFormatsTest {

    @Test
    fun onlyFormatsTheEngineWriterProducesAreOffered() {
        val offered = ExportFormats.offered(hasHeifWriter = false)
        assertTrue(offered.contains(ExportFormat.Tiff16))
        assertTrue(offered.contains(ExportFormat.Png16))
        assertTrue(offered.contains(ExportFormat.Png8))
        assertTrue(offered.contains(ExportFormat.Jpeg8))
    }

    @Test
    fun heicFollowsTheWriterRatherThanTheEnum() {
        // Two builds, two answers, and the picker is the thing that has to
        // follow: a build without libheif must not offer a format whose only
        // outcome is a failure at the end of a full render.
        assertFalse(
            "HEIC was offered by a build with no encoder",
            ExportFormat.Heic10 in ExportFormats.offered(hasHeifWriter = false),
        )
        assertTrue(
            "HEIC was withheld from a build that has an encoder",
            ExportFormat.Heic10 in ExportFormats.offered(hasHeifWriter = true),
        )
    }

    @Test
    fun heicEightIsNeverOffered() {
        // The encoder in this build is 10-bit, so an 8-bit request would be
        // refused by the encoder regardless. Offering it would be offering a
        // choice whose only outcome is a refusal.
        for (hasWriter in listOf(false, true)) {
            assertFalse(ExportFormat.Heic8 in ExportFormats.offered(hasHeifWriter = hasWriter))
        }
    }

    @Test
    fun everyFormatThatIsNotOfferedCanSayWhy() {
        val offered = ExportFormats.offered(hasHeifWriter = true).toSet()
        for (format in ExportFormat.entries) {
            if (format in offered) continue
            val reason = ExportFormats.pendingReason(format)
            assertNotNull("$format is unavailable with no explanation", reason)
            assertTrue("$format's reason is too short: $reason", (reason ?: "").length > 8)
        }
    }

    @Test
    fun theEngineWrittenSetIsTheFourTheHostActuallyProduces() {
        // Pinned rather than derived: `ARCHITECTURE.md` §8.8 puts file writing
        // in the host, and these four are what it writes. A fifth entry here is
        // a decision, not an accident.
        assertTrue(ExportFormats.engineWritten.size == 4)
    }
}
