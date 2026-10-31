package com.spektralab.app.camera

/**
 * The C3 shutter's enabled state, and the one line that explains it.
 *
 * The prototype draws a camera screen with a single "normal" state. A real one
 * has at least six reasons to refuse the shutter, and this repository's rule is
 * that **a disabled control must be able to state its reason** (the design
 * notes' §8.3, and trap 16's general shape: a guard nobody can see is a guard
 * nobody checks). So the readiness is modelled as data — one of these — rather
 * than as a boolean the UI re-derives from four places.
 *
 * Pure Kotlin on purpose: the instrumented tests need a device with a camera,
 * and this is the part worth testing on every build.
 */
sealed interface CaptureReadiness {

    /** The shutter may fire, at [mode]. */
    data class Ready(val mode: CaptureMode) : CaptureReadiness

    /** Nothing may fire; [reason] is shown under the shutter. */
    data class Blocked(val reason: BlockReason) : CaptureReadiness

    companion object {

        /**
         * The smallest DNG we will start without. A 12 MP 16-bit Bayer frame is
         * ~24 MB before `DngCreator` adds metadata and thumbnails; leaving room
         * for the write to finish is cheaper than a half-written file.
         */
        const val MIN_FREE_BYTES: Long = 96L * 1024 * 1024

        /** At or above this `getCurrentThermalStatus()` value, refuse. */
        const val THERMAL_STATUS_SEVERE: Int = 3

        /** Below this headroom, step down before the OS does. */
        const val HEADROOM_FLOOR: Float = 0.5f

        /**
         * One answer, in priority order.
         *
         * The order is: can we at all (permission, camera) → is something else
         * using it → will the write fit → will the render fit → is the device
         * about to cook. Anything that is the user's to fix comes before
         * anything that is a wait.
         */
        fun evaluate(c: CaptureConditions): CaptureReadiness {
            if (!c.permissionGranted) return Blocked(BlockReason.PERMISSION)
            if (c.cameraCount <= 0) return Blocked(BlockReason.NO_CAMERA)
            if (c.busy) return Blocked(BlockReason.BUSY)
            if (c.freeBytes in 0..<MIN_FREE_BYTES) return Blocked(BlockReason.STORAGE_FULL)
            if (c.exceedsMemoryBudget) return Blocked(BlockReason.OUT_OF_MEMORY)
            if (c.thermalStatus >= THERMAL_STATUS_SEVERE) return Blocked(BlockReason.TOO_HOT)
            if (!c.thermalHeadroom.isNaN() && c.thermalHeadroom < HEADROOM_FLOOR) {
                return Blocked(BlockReason.TOO_HOT)
            }
            return Ready(
                if (c.lensHasRaw) CaptureMode.RAW else CaptureMode.CONVENIENCE,
            )
        }
    }
}

/** What a successful shutter press produces. */
enum class CaptureMode {
    /**
     * `RAW_SENSOR` → DNG. The faithful path: the film model gets the sensor's
     * own linear response.
     */
    RAW,

    /**
     * The camera has no RAW, or the chosen lens has none. We still shoot, but
     * through the system's JPEG/HEIF encoder, which means the vendor's tone
     * curve is already in the file — see `AGENTS.md` trap 12, "external decodes
     * are not invertible". The UI says so; this is not a silent downgrade.
     */
    CONVENIENCE,
}

/**
 * Why the shutter is disabled. Each constant is one line of UI text; they are
 * enumerated so that "why can't I shoot" has exactly one answer at a time and
 * the priority order is written down (see [CaptureReadiness.evaluate]).
 */
enum class BlockReason {
    /** `CAMERA` permission not granted. Recoverable: ask, or fall back to import. */
    PERMISSION,

    /** No camera device, or every camera failed to open. */
    NO_CAMERA,

    /** The user's chosen lens has neither RAW nor a JPEG path (should not happen). */
    LENS_UNAVAILABLE,

    /** A previous capture is still being written or developed. */
    BUSY,

    /** The filesystem refused the DNG (no space, or scoped storage). */
    STORAGE_FULL,

    /**
     * The thermal forecast says this device is about to throttle hard.
     * `PowerManager.getThermalHeadroom(forecastSeconds)` is the API that gives
     * warning *before* the OS starts dropping frames, which is the only useful
     * moment to step down.
     */
    TOO_HOT,

    /** The engine refused the capture for a memory reason (`MemoryBudget`). */
    OUT_OF_MEMORY,
}

/**
 * Everything the decision needs, as plain values.
 *
 * Built by the Android layer from `Context`, `PowerManager`, the probe result
 * and `MemoryBudget`; kept separate so this decision can be tested without any
 * of them.
 */
data class CaptureConditions(
    val permissionGranted: Boolean,
    val cameraCount: Int,
    /** True when the lens that would be used has `RAW_SENSOR`. */
    val lensHasRaw: Boolean,
    val busy: Boolean,
    val freeBytes: Long,
    /** `PowerManager.getCurrentThermalStatus()`, 0 = none … 6 = shutdown. */
    val thermalStatus: Int,
    /**
     * `PowerManager.getThermalHeadroom(forecastSeconds)`, or [Float.NaN] when
     * the device does not report it (it is API 30+ and some devices return NaN).
     * Values below 1.0 mean throttling is expected within the forecast window.
     */
    val thermalHeadroom: Float,
    /** The engine's budget refuses a frame at the capture's pixel count. */
    val exceedsMemoryBudget: Boolean,
)

