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
    fun heicIsNotOfferedUntilTheRenderBridgeExists() {
        // The encoder works (see HeifEncodeInstrumentedTest), but `ExportRunner`
        // asks the *engine* to write a file and the engine has no HEIF writer.
        // Until the render's 16-bit pixels are handed to LibheifWriter, this
        // must stay true — and the day it flips, this test should be the one
        // that has to change.
        for (hasWriter in listOf(false, true)) {
            val offered = ExportFormats.offered(hasHeifWriter = hasWriter)
            assertFalse("HEIC was offered without a render→encode bridge", ExportFormat.Heic10 in offered)
            assertFalse("HEIC 8-bit can never be offered by this encoder", ExportFormat.Heic8 in offered)
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
    fun anOfferedFormatHasNoPendingReason() {
        // The other direction: a format with a reason must not also be
        // selectable, or the panel contradicts itself in two adjacent lines.
        val offered = ExportFormats.offered(hasHeifWriter = true).toSet()
        for (format in offered) {
            assertNull("$format is offered but also carries a reason", ExportFormats.pendingReason(format))
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
