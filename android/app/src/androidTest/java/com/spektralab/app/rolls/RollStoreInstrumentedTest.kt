package com.spektralab.app.rolls

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `RollStore` against a real filesystem.
 *
 * `RollTest` covers the arithmetic; this covers the part that only a device can
 * prove — that a roll written as JSON comes back identical, that the
 * `.partial`-then-rename write leaves no half file behind, and that a file
 * renamed on disk is rejected instead of being applied to the wrong roll.
 *
 * Each test uses its own directory under `filesDir` and removes it afterwards,
 * because a roll store that leaks between tests is the `UserDefaults` problem
 * from `AGENTS.md` trap 24 in a different costume.
 */
@RunWith(AndroidJUnit4::class)
class RollStoreInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var store: RollStore

    @Before
    fun setUp() {
        clearRolls()
        store = RollStore(context)
    }

    @After
    fun tearDown() {
        clearRolls()
    }

    private fun clearRolls() {
        File(context.filesDir, "rolls").deleteRecursively()
    }

    @Test
    fun aRollSurvivesTheRoundTrip() {
        val roll = Roll.newRoll("roll-100", "kodak_portra_400", "kodak_portra_endura", capacity = 36)
        val filled = roll.append("frame-a")!!.append("frame-b")!!
        store.save(filled)

        val loaded = store.load("roll-100")
        assertNotNull(loaded)
        assertEquals("kodak_portra_400", loaded!!.filmStockId)
        assertEquals("kodak_portra_endura", loaded.paperStockId)
        assertEquals(36, loaded.capacity)
        assertEquals(listOf("frame-a", "frame-b"), loaded.frameIds)
        assertEquals(3, loaded.nextFrameNumber)
        assertEquals("2 / 36", loaded.counterLabel)
    }

    @Test
    fun theWriteIsAtomicSoNoPartialFileIsEverReadable() {
        store.save(Roll.newRoll("roll-200", "f", "p"))

        val dir = File(context.filesDir, "rolls")
        val partials = dir.listFiles { f -> f.name.endsWith(".partial") } ?: emptyArray()
        assertTrue("a .partial file was left behind", partials.isEmpty())
        assertNotNull(store.load("roll-200"))
    }

    @Test
    fun aFileRenamedOnDiskIsRejectedRatherThanAppliedToTheWrongRoll() {
        store.save(Roll.newRoll("roll-300", "f", "p"))
        val dir = File(context.filesDir, "rolls")
        val original = File(dir, "roll-300.json")
        val renamed = File(dir, "roll-999.json")
        assertTrue(original.renameTo(renamed))

        // The id inside the file still says roll-300, so it must not answer for
        // roll-999 — that is a different roll's photographs.
        assertNull(store.load("roll-999"))
    }

    @Test
    fun appendingToTheCurrentRollAdvancesTheSameRoll() {
        val first = store.appendToCurrent("frame-1", "f", "p", capacity = 3) { "roll-$it" }
        val second = store.appendToCurrent("frame-2", "f", "p", capacity = 3) { "roll-$it" }

        assertEquals("the same roll keeps receiving frames", first.id, second.id)
        assertEquals(2, second.count)
    }

    @Test
    fun aFullRollStartsANewOneRatherThanDroppingTheFrame() {
        val full = store.appendToCurrent("a", "f", "p", capacity = 1) { "roll-$it" }
        val next = store.appendToCurrent("b", "f", "p", capacity = 1) { "roll-${it + 1}" }

        assertTrue(full.isFull)
        assertEquals("a second roll was not started", 2, store.all().size)
        assertEquals(listOf("b"), next.frameIds)
    }

    @Test
    fun aRollWithADifferentFilmStartsANewRoll() {
        store.appendToCurrent("a", "kodak_portra_400", "p") { "roll-$it" }
        val second = store.appendToCurrent("b", "kodak_trix_400", "p") { "roll-${it + 1}" }

        assertEquals("kodak_trix_400", second.filmStockId)
        assertEquals(2, store.all().size)
    }
}
