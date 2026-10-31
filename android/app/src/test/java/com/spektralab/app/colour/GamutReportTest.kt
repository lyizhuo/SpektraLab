package com.spektralab.app.colour

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The export page's gamut row. These are the numbers a colour product is judged
 * on, so the classification is pinned rather than left to whatever the last
 * refactor did.
 */
class GamutReportTest {

    @Test
    fun `an in-gamut frame reports three zeroes`() {
        val rgb = floatArrayOf(
            0f, 0f, 0f,
            1f, 1f, 1f,
            0.5f, 0.25f, 0.75f,
        )
        val r = GamutReport.of(rgb)
        assertEquals(3, r.totalPixels)
        assertEquals(0, r.outsidePixels)
        assertEquals(0, r.mappedPixels)
        assertEquals(0, r.clippedPixels)
        assertEquals("超出目标色域 0% · 被映射 0% · 被夹限 0%", r.label())
    }

    @Test
    fun `a negative channel is outside but recoverable`() {
        // A saturated cyan that Display P3 cannot reach: red goes negative.
        val r = GamutReport.of(floatArrayOf(-0.08f, 0.9f, 0.95f))
        assertEquals(1, r.totalPixels)
        assertEquals(1, r.outsidePixels)
        assertEquals(1, r.mappedPixels)
        assertEquals(0, r.clippedPixels)
        assertEquals("超出目标色域 100.0% · 被映射 100.0% · 被夹限 0%", r.label())
    }

    @Test
    fun `a channel above one is clipped, and clipping wins over mapping`() {
        // Both violated: one channel negative, another over unity. The lossier
        // treatment is the one reported.
        val r = GamutReport.of(floatArrayOf(-0.02f, 0.5f, 1.4f))
        assertEquals(1, r.outsidePixels)
        assertEquals(0, r.mappedPixels)
        assertEquals(1, r.clippedPixels)
    }

    @Test
    fun `the prototype's example ratios come out of the same arithmetic`() {
        // 1000 px: 18 fully outside (12 mapped, 6 clipped) -> 1.8 / 1.2 / 0.6.
        // The prototype's own row is 1.8 / 0.6 / 0.1, so this checks the shape
        // and the rounding, not the picture it was measured on.
        val rgb = FloatArray(1000 * 3) { 0.5f }
        for (i in 0 until 12) { rgb[i * 3] = -0.1f }          // mapped
        for (i in 12 until 18) { rgb[i * 3 + 2] = 1.1f }      // clipped
        val r = GamutReport.of(rgb)
        assertEquals(18, r.outsidePixels)
        assertEquals(12, r.mappedPixels)
        assertEquals(6, r.clippedPixels)
        assertEquals("超出目标色域 1.8% · 被映射 1.2% · 被夹限 0.6%", r.label())
    }

    @Test
    fun `an rgba buffer is read with a stride of four`() {
        val rgba = floatArrayOf(
            0f, 0f, 0f, 1f,
            -0.5f, 0f, 0f, 1f,
        )
        val r = GamutReport.of(rgba, stride = 4)
        assertEquals(2, r.totalPixels)
        assertEquals(1, r.outsidePixels)
        assertEquals(1, r.mappedPixels)
    }

    @Test
    fun `a short buffer is counted, not overrun`() {
        // 7 floats = two whole pixels and a stray channel.
        val r = GamutReport.of(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f))
        assertEquals(2, r.totalPixels)
    }

    @Test
    fun `an empty buffer is the empty report, not a divide by zero`() {
        val r = GamutReport.of(floatArrayOf())
        assertEquals(GamutReport.EMPTY, r)
        assertEquals("超出目标色域 0% · 被映射 0% · 被夹限 0%", r.label())
    }

    @Test
    fun `a stride below three is a programming error`() {
        try {
            GamutReport.of(floatArrayOf(0f, 0f, 0f), stride = 2)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // the point of the test
        }
    }
}
