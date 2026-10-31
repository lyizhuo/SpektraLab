package com.spektralab.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lens mapping is the one part of the capture path that runs without a
 * phone, so it carries the weight in this package's tests.
 *
 * The numbers below are real devices' published equivalents: a 1/1.28" main
 * camera at 23 mm, a 1/2.76" ultrawide at 13 mm and a 1/1.56" 3× tele at 69 mm
 * — the shape of a current flagship. The arithmetic has to put those on
 * `0.57 / 1× / 3×`, not on the prototype's `0.7 / 2×`, because the label is
 * derived from the device rather than fixed.
 */
class LensPlanTest {

    private fun lens(id: String, eqMm: Float, raw: Boolean = false) =
        LensSpec(cameraId = id, equivalentMm = eqMm, focalMm = eqMm / 3f, hasRaw = raw)

    @Test
    fun `a flagship's three cameras land on their real ratios`() {
        val plan = LensPlanner.plan(
            listOf(
                lens("wide", 23f, raw = true),
                lens("ultra", 13f),
                lens("tele", 69f),
            ),
        )

        assertEquals(3, plan.zoomStops.size)
        assertEquals(13f / 23f, plan.zoomStops[0].ratio, 0.01f)
        assertEquals("ultra", plan.zoomStops[0].lens?.cameraId)
        assertEquals(1f, plan.zoomStops[1].ratio, 0.0001f)
        assertEquals("wide", plan.zoomStops[1].lens?.cameraId)
        assertEquals(69f / 23f, plan.zoomStops[2].ratio, 0.01f)
        assertEquals("tele", plan.zoomStops[2].lens?.cameraId)
    }

    @Test
    fun `the main stop is the lens closest to 26 mm, not the first enumerated`() {
        // Vendors do not promise an order. Put the tele first to prove it.
        val plan = LensPlanner.plan(
            listOf(
                lens("tele", 69f),
                lens("ultra", 13f),
                lens("wide", 24f, raw = true),
            ),
        )

        assertEquals("wide", plan.main?.lens?.cameraId)
        assertEquals(1f, plan.main?.ratio)
    }

    @Test
    fun `a phone with no ultrawide reports the reason instead of dropping the button`() {
        val plan = LensPlanner.plan(listOf(lens("wide", 26f, raw = true), lens("tele", 52f)))

        assertEquals(3, plan.zoomStops.size)
        assertNull(plan.zoomStops[0].lens)
        assertEquals(UnavailableReason.NO_ULTRAWIDE, plan.zoomStops[0].unavailableReason)
        // The label still reads like a phone camera's, not a hole in the bar.
        assertEquals(0.7f, plan.zoomStops[0].ratio, 0.0001f)
    }

    @Test
    fun `a phone with no tele reports that reason too`() {
        val plan = LensPlanner.plan(listOf(lens("wide", 26f, raw = true), lens("ultra", 16f)))

        assertEquals(UnavailableReason.NO_TELE, plan.zoomStops[2].unavailableReason)
        assertNull(plan.zoomStops[2].lens)
    }

    @Test
    fun `a device with one camera is not a broken device`() {
        val plan = LensPlanner.plan(listOf(lens("only", 26f, raw = true)))

        assertEquals("only", plan.main?.lens?.cameraId)
        assertTrue(plan.hasRaw)
        assertEquals(UnavailableReason.NO_ULTRAWIDE, plan.zoomStops[0].unavailableReason)
        assertEquals(UnavailableReason.NO_TELE, plan.zoomStops[2].unavailableReason)
    }

    @Test
    fun `no cameras at all is still one stop with a reason`() {
        val plan = LensPlanner.plan(emptyList())

        // The bar keeps its shape: a single 1× stop carrying the reason, rather
        // than an empty row the UI has to special-case.
        assertEquals(1, plan.zoomStops.size)
        assertEquals(UnavailableReason.NO_PHYSICAL_CAMERA, plan.zoomStops[0].unavailableReason)
        // `main` is that stop, but it owns no lens — which is what the readiness
        // rule reads to answer NO_CAMERA.
        assertNull(plan.main?.lens)
        assertNull(plan.rawLensFor(plan.zoomStops[0]))
        assertTrue(!plan.hasRaw)
    }

    @Test
    fun `raw falls back to the main lens when the requested one has none`() {
        val plan = LensPlanner.plan(
            listOf(
                lens("wide", 24f, raw = true),
                lens("ultra", 13f),      // no RAW, as on most phones
                lens("tele", 70f),
            ),
        )

        val ultra = plan.zoomStops[0]
        assertEquals("wide", plan.rawLensFor(ultra)?.cameraId)
        // ...and asking for a tele stop that has no RAW also lands on the main.
        assertEquals("wide", plan.rawLensFor(plan.zoomStops[2])?.cameraId)
    }

    @Test
    fun `no lens with raw means no raw lens, not a quiet jpeg`() {
        val plan = LensPlanner.plan(listOf(lens("wide", 24f), lens("tele", 70f)))

        assertNull(plan.rawLensFor(plan.main!!))
        assertTrue(!plan.hasRaw)
    }

    @Test
    fun `crop factor uses the diagonal, not the width`() {
        // 1/1.28" sensor: 9.8 x 7.3 mm, diagonal 12.22 mm -> 3.54x.
        val crop = LensPlanner.cropFactor(9.8f, 7.3f)
        assertEquals(3.54f, crop, 0.02f)

        // A 6.4 mm lens on that sensor is 22.7 mm equivalent, which is what the
        // C3 readout should print.
        assertEquals(22.7f, LensPlanner.equivalentFocalMm(6.4f, 9.8f, 7.3f), 0.15f)
    }

    @Test
    fun `an unknown sensor size degrades to the raw focal length`() {
        assertEquals(6.4f, LensPlanner.equivalentFocalMm(6.4f, 0f, 0f), 0.0001f)
        assertEquals(1f, LensPlanner.cropFactor(0f, 0f), 0.0001f)
    }
}
