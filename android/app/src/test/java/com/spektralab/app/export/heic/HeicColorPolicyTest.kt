package com.spektralab.app.export.heic

import com.spektralab.app.export.ExportColorSpace
import com.spektralab.app.export.ExportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which HEIC exports may be offered.
 *
 * The case that matters is ProPhoto: it is this engine's own working space, it
 * has no `nclx` code point, and a writer that silently drops it produces a file
 * every viewer reads as sRGB. That is not a cosmetic difference — the same
 * numbers mean about 1.5× the light (AGENTS.md trap 27) — so it must be
 * refused with a reason rather than exported wrong.
 */
class HeicColorPolicyTest {

    @Test
    fun `sRGB and P3 ride nclx and need no ICC`() {
        assertFalse(HeicColorPolicy.requiresIcc(ExportColorSpace.Srgb))
        assertFalse(HeicColorPolicy.requiresIcc(ExportColorSpace.DisplayP3))
        assertTrue(HeicColorPolicy.nclxExpressible.contains(ExportColorSpace.Srgb))
        assertTrue(HeicColorPolicy.nclxExpressible.contains(ExportColorSpace.DisplayP3))
    }

    @Test
    fun `ProPhoto needs an ICC profile because ROMM has no nclx code`() {
        assertTrue(HeicColorPolicy.requiresIcc(ExportColorSpace.ProPhoto))
        assertFalse(HeicColorPolicy.nclxExpressible.contains(ExportColorSpace.ProPhoto))
    }

    @Test
    fun `a writer without ICC support may still write P3`() {
        val d = HeicColorPolicy.allows(ExportFormat.Heic10, ExportColorSpace.DisplayP3, iccSupport = false)
        assertEquals(HeicColorPolicy.Decision.Allowed, d)
    }

    @Test
    fun `a writer without ICC support must refuse ProPhoto`() {
        val d = HeicColorPolicy.allows(ExportFormat.Heic10, ExportColorSpace.ProPhoto, iccSupport = false)
        assertTrue(d is HeicColorPolicy.Decision.Refused)
        val reason = (d as HeicColorPolicy.Decision.Refused).reason
        // The reason names both the missing mechanism and the consequence.
        assertTrue(reason.contains("ICC"))
        assertTrue(reason.contains("ProPhoto RGB"))
    }

    @Test
    fun `a writer with ICC support may write ProPhoto`() {
        assertEquals(
            HeicColorPolicy.Decision.Allowed,
            HeicColorPolicy.allows(ExportFormat.Heic10, ExportColorSpace.ProPhoto, iccSupport = true),
        )
    }

    @Test
    fun `both heic depths follow the same policy`() {
        for (format in listOf(ExportFormat.Heic8, ExportFormat.Heic10)) {
            assertTrue(
                HeicColorPolicy.allows(format, ExportColorSpace.ProPhoto, false)
                    is HeicColorPolicy.Decision.Refused,
            )
            assertEquals(
                HeicColorPolicy.Decision.Allowed,
                HeicColorPolicy.allows(format, ExportColorSpace.ProPhoto, true),
            )
        }
    }

    @Test
    fun `non-heic formats are never constrained by the heif rule`() {
        // TIFF and PNG already carry the profile through the host's writers;
        // refusing them here would take away a working export.
        for (format in listOf(ExportFormat.Tiff16, ExportFormat.Png16, ExportFormat.Png8, ExportFormat.Jpeg8)) {
            assertEquals(
                HeicColorPolicy.Decision.Allowed,
                HeicColorPolicy.allows(format, ExportColorSpace.ProPhoto, iccSupport = false),
            )
        }
    }

    @Test
    fun `only the heic formats declare that they carry their own profile`() {
        assertTrue(ExportFormat.Heic8.needsIccProfile)
        assertTrue(ExportFormat.Heic10.needsIccProfile)
        assertFalse(ExportFormat.Tiff16.needsIccProfile)
        assertFalse(ExportFormat.Jpeg8.needsIccProfile)
    }

    @Test
    fun `the heic wire names and mime types are the ones Android and viewers expect`() {
        assertEquals("image/heic", ExportFormat.Heic10.mime)
        assertEquals("heic", ExportFormat.Heic10.extension)
        assertEquals(10, ExportFormat.Heic10.bits)
        assertEquals(8, ExportFormat.Heic8.bits)
    }
}
