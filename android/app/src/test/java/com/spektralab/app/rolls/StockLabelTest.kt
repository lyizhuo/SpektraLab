package com.spektralab.app.rolls

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The C3 badge prints `PORTRA 400`, not `KODAK PORTRA 400`.
 *
 * The manufacturer is already implied by the product, and the badge has 16
 * characters to work with. This is a display convention, so it is pinned: two
 * surfaces deriving a label independently is how they start disagreeing.
 */
class StockLabelTest {

    @Test
    fun aManufacturerPrefixIsDroppedFromAStockWithASpeed() {
        assertEquals("PORTRA 400", stockLabel("kodak_portra_400"))
        assertEquals("PORTRA ENDURA", stockLabel("kodak_portra_endura"))
        assertEquals("HP5 PLUS", stockLabel("ilford_hp5_plus"))
    }

    @Test
    fun aTwoTokenStockIsAlreadyAName() {
        assertEquals("TRI X", stockLabel("tri_x"))
        assertEquals("PORTRA", stockLabel("portra"))
    }

    @Test
    fun somethingWithNoUnderscoresIsJustUppercased() {
        assertEquals("PORTRA400", stockLabel("portra400"))
        assertEquals("", stockLabel(""))
    }
}