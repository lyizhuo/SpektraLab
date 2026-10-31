package com.spektralab.app.rolls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roll's arithmetic. `RollStore` needs a device to test; this does not, and
 * this is where the rules that matter live: a full roll refuses rather than
 * truncates, the frame number always agrees with the count, and progress cannot
 * leave 0..1.
 */
class RollTest {

    private fun roll(capacity: Int = 36, frames: List<String> = emptyList()) =
        Roll(
            id = "roll-1",
            filmStockId = "kodak_portra_400",
            paperStockId = "kodak_portra_endura",
            capacity = capacity,
            createdAtMillis = 0L,
            frameIds = frames,
        )

    @Test
    fun `a new roll is empty and offers frame 1`() {
        val r = roll()
        assertTrue(r.isEmpty)
        assertFalse(r.isFull)
        assertEquals(0, r.count)
        assertEquals(36, r.remaining)
        assertEquals("0 / 36", r.counterLabel)
        assertEquals(1, r.nextFrameNumber)
    }

    @Test
    fun `appending advances the counter and the frame number together`() {
        val r = roll().append("a")!!.append("b")!!
        assertEquals(2, r.count)
        assertEquals("2 / 36", r.counterLabel)
        assertEquals(3, r.nextFrameNumber)
        assertEquals(34, r.remaining)
    }

    @Test
    fun `the 37th frame is refused, not dropped`() {
        val full = roll(capacity = 2, frames = listOf("a", "b"))
        assertTrue(full.isFull)
        assertNull(full.nextFrameNumber)
        assertNull(full.append("c"))
        // And it did not quietly become a 2-frame version of a 3-frame request.
        assertEquals(listOf("a", "b"), full.frameIds)
    }

    @Test
    fun `the last frame still fits`() {
        val r = roll(capacity = 2, frames = listOf("a"))
        assertFalse(r.isFull)
        assertEquals(2, r.nextFrameNumber)
        assertEquals(2, r.append("b")!!.count)
    }

    @Test
    fun `appending the same frame twice is not two photographs`() {
        val r = roll().append("a")!!.append("a")!!
        assertEquals(1, r.count)
    }

    @Test
    fun `frame numbers are 1-based because a film edge counts from one`() {
        assertEquals(1, roll(frames = emptyList()).nextFrameNumber)
        assertEquals(2, roll(frames = listOf("a")).nextFrameNumber)
        assertEquals(17, roll(frames = (1..16).map { "f$it" }).nextFrameNumber)
    }

    @Test
    fun `progress is the fraction of the roll that is exposed`() {
        assertEquals(0f, roll().progress(), 0.0001f)
        assertEquals(0.5f, roll(capacity = 4, frames = listOf("a", "b")).progress(), 0.0001f)
        assertEquals(1f, roll(capacity = 2, frames = listOf("a", "b")).progress(), 0.0001f)
    }

    @Test
    fun `a zero capacity roll cannot divide by zero`() {
        assertEquals(0f, roll(capacity = 0).progress(), 0.0001f)
    }

    @Test
    fun `newRoll clamps a nonsense capacity instead of storing it`() {
        assertEquals(1, Roll.newRoll("r", "f", "p", capacity = 0).capacity)
        assertEquals(1, Roll.newRoll("r", "f", "p", capacity = -5).capacity)
    }

    @Test
    fun `removing a frame frees a slot and is a no-op for an unknown id`() {
        val r = roll(capacity = 2, frames = listOf("a", "b"))
        val after = r.remove("a")
        assertFalse(after.isFull)
        assertEquals(listOf("b"), after.frameIds)
        assertEquals(r, r.remove("zzz"))
    }

    @Test
    fun `120 holds twelve exposures`() {
        val r = Roll.newRoll("r", "f", "p", capacity = Roll.CAPACITY_120)
        assertEquals(12, r.capacity)
        assertEquals("0 / 12", r.counterLabel)
    }
}
