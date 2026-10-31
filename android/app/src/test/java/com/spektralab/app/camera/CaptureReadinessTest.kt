package com.spektralab.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shutter's decision table. Every case here is one line on screen, so the
 * test names read as the UI: if one of these changes, the sentence under the
 * shutter changes with it.
 */
class CaptureReadinessTest {

    private fun conditions(
        permission: Boolean = true,
        cameras: Int = 2,
        raw: Boolean = true,
        busy: Boolean = false,
        freeBytes: Long = 2L * 1024 * 1024 * 1024,
        thermal: Int = 0,
        headroom: Float = 2f,
        overBudget: Boolean = false,
    ) = CaptureConditions(
        permissionGranted = permission,
        cameraCount = cameras,
        lensHasRaw = raw,
        busy = busy,
        freeBytes = freeBytes,
        thermalStatus = thermal,
        thermalHeadroom = headroom,
        exceedsMemoryBudget = overBudget,
    )

    private fun blocked(r: CaptureReadiness): BlockReason =
        (r as CaptureReadiness.Blocked).reason

    @Test
    fun `a healthy device shoots raw`() {
        val r = CaptureReadiness.evaluate(conditions())
        assertEquals(CaptureReadiness.Ready(CaptureMode.RAW), r)
    }

    @Test
    fun `a lens without raw still shoots, but says it is convenience mode`() {
        val r = CaptureReadiness.evaluate(conditions(raw = false))
        assertEquals(CaptureReadiness.Ready(CaptureMode.CONVENIENCE), r)
    }

    @Test
    fun `no permission outranks everything else`() {
        // A device with no camera, no space, and a hot chassis still answers
        // "permission" first, because that is the only one the user can fix.
        val r = CaptureReadiness.evaluate(
            conditions(permission = false, cameras = 0, freeBytes = 0, thermal = 6),
        )
        assertEquals(BlockReason.PERMISSION, blocked(r))
    }

    @Test
    fun `no camera is not a permission problem`() {
        assertEquals(BlockReason.NO_CAMERA, blocked(CaptureReadiness.evaluate(conditions(cameras = 0))))
    }

    @Test
    fun `a capture in flight blocks the next one`() {
        assertEquals(BlockReason.BUSY, blocked(CaptureReadiness.evaluate(conditions(busy = true))))
    }

    @Test
    fun `a nearly full disk refuses before writing half a dng`() {
        val r = CaptureReadiness.evaluate(conditions(freeBytes = CaptureReadiness.MIN_FREE_BYTES - 1))
        assertEquals(BlockReason.STORAGE_FULL, blocked(r))
    }

    @Test
    fun `exactly the minimum is enough`() {
        val r = CaptureReadiness.evaluate(conditions(freeBytes = CaptureReadiness.MIN_FREE_BYTES))
        assertEquals(CaptureReadiness.Ready(CaptureMode.RAW), r)
    }

    @Test
    fun `severe thermal status refuses the shutter`() {
        val r = CaptureReadiness.evaluate(conditions(thermal = CaptureReadiness.THERMAL_STATUS_SEVERE))
        assertEquals(BlockReason.TOO_HOT, blocked(r))
    }

    @Test
    fun `thermal below severe is fine`() {
        val r = CaptureReadiness.evaluate(conditions(thermal = CaptureReadiness.THERMAL_STATUS_SEVERE - 1))
        assertEquals(CaptureReadiness.Ready(CaptureMode.RAW), r)
    }

    @Test
    fun `a low headroom forecast refuses before the os throttles`() {
        val r = CaptureReadiness.evaluate(
            conditions(thermal = 0, headroom = CaptureReadiness.HEADROOM_FLOOR - 0.01f),
        )
        assertEquals(BlockReason.TOO_HOT, blocked(r))
    }

    @Test
    fun `a device that does not report headroom is not treated as cold`() {
        // NaN is what API 30+ returns on devices without the sensor. Refusing
        // on it would disable the shutter on real hardware, so NaN means
        // "unknown" and the status integer is the only thermal signal left.
        val r = CaptureReadiness.evaluate(
            conditions(headroom = Float.NaN, thermal = CaptureReadiness.THERMAL_STATUS_SEVERE),
        )
        assertEquals(BlockReason.TOO_HOT, blocked(r))
    }

    @Test
    fun `a frame the engine cannot hold is refused with its own reason`() {
        val r = CaptureReadiness.evaluate(conditions(overBudget = true))
        assertEquals(BlockReason.OUT_OF_MEMORY, blocked(r))
    }

    @Test
    fun `capability problems outrank transient ones`() {
        // BUSY is a wait; a lens with no camera is not. Do not hide the second
        // behind the first.
        assertEquals(BlockReason.BUSY, blocked(CaptureReadiness.evaluate(conditions(busy = true))))
    }
}
