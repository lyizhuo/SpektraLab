package com.spektralab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan's D9 acceptance lines, as arithmetic: 12 MP must fit an 8 GB
 * device, 25 MP a 16 GB one, and anything larger must be refused with a
 * number the user can act on rather than silently downscaled.
 *
 * The constants are the plan's own (`0.35 × RAM − 600 MB`, 180 B/px), and the
 * measured bytes-per-pixel in `android/DEVICE-BASELINE.md` (113.8 B/px for a
 * fresh export session, 190.7 B/px for an editing session that already holds
 * live and preview negatives) is why 180 is the value that is *not* raised:
 * it sits between the two.
 */
class MemoryBudgetTest {

    private val gb = 1L shl 30

    @Test
    fun budgetFollowsTheFormula() {
        // A device's RAM is reported in binary bytes (8 GiB = 8.59e9), so the
        // plan's decimal table (8 GB -> 2.2 GB) is the *floor* of what this
        // gives: 0.35 × 8.59e9 − 0.629e9 = 2.38 GB. The band is asserted
        // rather than the exact value, because rounding the fraction either
        // way is not what the budget is about.
        assertInBand(MemoryBudget(8 * gb).nativeBudgetBytes, 2_200_000_000, 2_500_000_000)
        assertInBand(MemoryBudget(16 * gb).nativeBudgetBytes, 5_000_000_000, 5_500_000_000)
        assertInBand(MemoryBudget(6 * gb).nativeBudgetBytes, 1_500_000_000, 1_800_000_000)
        // A device that reports nothing, or reports very little, still gets
        // the 1 GB floor rather than a negative budget.
        assertEquals(1L shl 30, MemoryBudget(0).nativeBudgetBytes)
        assertEquals(1L shl 30, MemoryBudget(2 * gb).nativeBudgetBytes)
    }

    private fun assertInBand(value: Long, low: Long, high: Long) {
        assertTrue("$value is below $low", value >= low)
        assertTrue("$value is above $high", value <= high)
    }

    @Test
    fun twelveMegapixelsMustFitEightGigabytes() {
        val budget = MemoryBudget(8 * gb)
        assertTrue(
            "8 GB must afford a 12 MP export, got ${budget.allowedPixels} px",
            budget.allowedPixels >= 12_000_000,
        )
        val verdict = budget.verdict(4000, 3000, maxPixels = 151_301_008, maxTextureDimension2d = 16384)
        assertTrue("12 MP on 8 GB was refused: $verdict", verdict is MemoryBudget.Verdict.Allowed)
    }

    @Test
    fun twentyFiveMegapixelsMustFitSixteenGigabytes() {
        val budget = MemoryBudget(16 * gb)
        assertTrue(
            "16 GB must afford a 25 MP export, got ${budget.allowedPixels} px",
            budget.allowedPixels >= 25_000_000,
        )
        val verdict = budget.verdict(6250, 4000, maxPixels = 151_301_008, maxTextureDimension2d = 16384)
        assertTrue("25 MP on 16 GB was refused: $verdict", verdict is MemoryBudget.Verdict.Allowed)
    }

    @Test
    fun smallDevicesAreRefusedWithTheLargestUsableEdge() {
        val budget = MemoryBudget(6 * gb)
        val verdict = budget.verdict(4000, 3000, maxPixels = 151_301_008, maxTextureDimension2d = 16384)
        assertTrue("12 MP on 6 GB should be refused, got $verdict", verdict is MemoryBudget.Verdict.Refused)
        val refused = verdict as MemoryBudget.Verdict.Refused
        // The number the export page shows: a 3:2 frame at the refused aspect.
        assertTrue("refusal names no usable edge: $refused", refused.maxLongEdge in 3000..4000)
        assertTrue("refusal does not explain itself: ${refused.reason}", refused.reason.contains("memory budget"))
    }

    @Test
    fun engineLimitsBeatTheBudget() {
        val budget = MemoryBudget(16 * gb)
        // Above the engine's own pixel cap (a Phase One IQ4 150), the refusal
        // must be the engine's wording, not the budget's: the budget cannot be
        // raised, the frame size can.
        val tooBig = budget.verdict(24000, 8000, maxPixels = 151_301_008, maxTextureDimension2d = 16384)
        assertTrue(tooBig is MemoryBudget.Verdict.Refused)
        assertTrue((tooBig as MemoryBudget.Verdict.Refused).reason.startsWith("frame is too large:"))

        // And the device's texture limit is a different refusal again.
        val tooWide = budget.verdict(20000, 3000, maxPixels = 151_301_008, maxTextureDimension2d = 16384)
        assertTrue(tooWide is MemoryBudget.Verdict.Refused)
        assertTrue((tooWide as MemoryBudget.Verdict.Refused).reason.contains("texture limit"))
    }

    @Test
    fun longestEdgeFollowsTheFramesAspect() {
        val budget = MemoryBudget(16 * gb)
        val landscape = budget.maxLongEdgeFor(6000, 4000)
        val square = budget.maxLongEdgeFor(4000, 4000)
        // A 3:2 frame affords a longer edge than a square one at the same
        // budget, because the other side is proportionally shorter.
        assertTrue("$landscape should exceed $square", landscape > square)
        assertTrue(square > 0)
    }
}
