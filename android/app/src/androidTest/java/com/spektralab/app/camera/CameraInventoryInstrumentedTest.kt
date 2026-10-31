package com.spektralab.app.camera

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The lens probe against a **real** `CameraManager`.
 *
 * `LensPlanTest` covers the arithmetic; this covers the half that only a device
 * can answer — that `CameraInventory` survives whatever `cameraIdList` and
 * `getCameraCharacteristics` actually return, that a physical-only id does not
 * throw out of the probe, and that the plan it produces is usable.
 *
 * The assertions are deliberately loose about *values*: an emulator's virtual
 * scene camera reports one lens and no RAW, a phone reports three and maybe
 * one. What must hold on both is the shape — three stops, a main stop that owns
 * a lens, and every unavailable stop carrying a reason rather than a null.
 */
@RunWith(AndroidJUnit4::class)
class CameraInventoryInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theProbeReturnsAPlanTheCameraScreenCanDraw() {
        val result = CameraInventory(context).probe()

        // A device with no back camera is legal (a tablet, some emulators), and
        // the plan still has to be drawable — one stop carrying NO_PHYSICAL_CAMERA.
        if (!result.hasBackCamera) {
            assertEquals(1, result.plan.zoomStops.size)
            assertEquals(UnavailableReason.NO_PHYSICAL_CAMERA, result.plan.zoomStops[0].unavailableReason)
            return
        }

        assertTrue("expected at least one rear lens", result.lenses.isNotEmpty())
        assertEquals("the bar is always three stops", 3, result.plan.zoomStops.size)

        val main = result.plan.main
        assertNotNull("a device with a back camera has a main stop", main)
        assertNotNull("...and it owns a lens", main!!.lens)

        // Every unavailable stop explains itself; every available one has glass.
        for (stop in result.plan.zoomStops) {
            if (stop.lens == null) {
                assertNotNull("stop ${stop.ratio} is unavailable without a reason", stop.unavailableReason)
            }
        }

        // Equivalent focal lengths are ordered, which is what the left-to-right
        // bar relies on.
        val available = result.plan.zoomStops.mapNotNull { it.lens?.equivalentMm }
        assertEquals(available.sorted(), available)
    }

    @Test
    fun theLensIdsAreIdsTheCameraManagerRecognises() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = manager.cameraIdList.toSet()
        val probe = CameraInventory(context).probe()

        for (lens in probe.lenses) {
            assertTrue(
                "lens ${lens.cameraId} is not in cameraIdList",
                lens.cameraId in ids,
            )
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun theThermalForecastIsReadWithoutThrowing() {
        // The readiness rule treats NaN as "unknown"; the only failure this can
        // have is throwing, which would disable the shutter on real hardware.
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val headroom = pm.getThermalHeadroom(10)
        assertTrue("headroom is a number or NaN, never negative", headroom.isNaN() || headroom >= 0f)
    }
}
