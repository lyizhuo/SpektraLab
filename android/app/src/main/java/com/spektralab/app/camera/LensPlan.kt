package com.spektralab.app.camera

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Which physical camera serves which zoom stop, and what each one can deliver.
 *
 * The C3 screen offers `0.7 / 1× / 2×`, and those are **lens swaps, not digital
 * zoom**: the sensor behind each stop has its own optics, its own sensor size
 * and — the part that decides the whole capture path — often its own answer to
 * "can this camera give me RAW". On a typical phone the main camera has
 * `RAW_SENSOR`, the ultrawide and the tele usually do not.
 *
 * So the plan is computed once per device from
 * `CameraCharacteristics`, kept as plain data, and every UI state derives from
 * it. Doing it here rather than inside the Camera2 plumbing is deliberate: the
 * mapping is arithmetic over focal lengths, and arithmetic is the only part of
 * a camera path that can be tested without a phone. The instrumented tests run
 * on a device that may have no rear camera at all; this file runs everywhere.
 *
 * ### Equivalent focal length
 *
 * Crop factor is `43.2666 / diagonal_mm` — the industry convention, not the
 * width ratio. Using width instead moves a 1/1.28" main camera by ~0.5 mm
 * equivalent, which is enough to put an ultrawide on the wrong side of the
 * 0.8× boundary. The diagonal of 36×24 mm is 43.266615...; [FULL_FRAME_DIAGONAL_MM]
 * carries it.
 *
 * ### Why the stops are ratios of the main lens
 *
 * The screen prints `0.7 / 1× / 2×`, i.e. relative to the main camera's field
 * of view — the way a phone camera app labels them. A device whose ultrawide is
 * 0.6× therefore shows `0.6`, not a hardcoded `0.7`; the label is derived, and
 * [ZoomStop.ratio] is what the UI formats.
 */
data class LensPlan(
    val zoomStops: List<ZoomStop>,
) {
    /** The stop the app opens on: 1×, or the closest thing to it. */
    val main: ZoomStop? = zoomStops.minByOrNull { abs(it.ratio - 1.0f) }

    /** True when at least one stop can deliver scene-linear sensor data. */
    val hasRaw: Boolean = zoomStops.any { it.lens?.hasRaw == true }

    /**
     * The stop a capture should actually use when the user asked for [requested]
     * and RAW is preferred: the requested one if it has RAW, else the main lens
     * if *it* has RAW, else `null` (convenience mode, JPEG/HEIF only).
     *
     * This is the "deliver the honest thing rather than the pretty one" rule:
     * silently shooting a JPEG from the ultrawide when the user picked 0.7× and
     * expected film physics would look like a bug in the film model.
     */
    fun rawLensFor(requested: ZoomStop): LensSpec? =
        requested.lens?.takeIf { it.hasRaw }?.let { it }
            ?: main?.lens?.takeIf { it.hasRaw }
}

/**
 * One button on the C3 lens bar.
 *
 * [lens] is null when the device has no camera for this stop; the button is
 * then shown disabled with [unavailableReason] rather than hidden, because a
 * bar that changes width as lenses are probed is worse than one stable bar with
 * a greyed button (the prototype's §8.3 rule: a disabled control must be able
 * to state its reason).
 */
data class ZoomStop(
    val ratio: Float,
    val lens: LensSpec?,
    val unavailableReason: UnavailableReason? = null,
) {
    val available: Boolean get() = lens != null || unavailableReason == null
}

/** Everything the capture path needs to know about one physical camera. */
data class LensSpec(
    val cameraId: String,
    /** 35 mm-equivalent focal length, from [LensPlan.equivalentFocalMm]. */
    val equivalentMm: Float,
    /** Raw focal length as the device reports it, for the EXIF/date back. */
    val focalMm: Float,
    val hasRaw: Boolean,
    /**
     * `MANUAL_SENSOR`: the camera accepts explicit ISO and shutter. Only the
     * main lens of a flagship usually has it, and it is what turns the EV dial
     * into a real exposure control. Defaulted so a caller that only cares about
     * RAW (and every test) need not state it.
     */
    val hasManualSensor: Boolean = false,
)

/**
 * Why a stop cannot be used. Rendered as one line under the lens bar; the enum
 * exists so the reason is data, not a string composed at the call site — the
 * same reason `MemoryBudget` returns a typed refusal instead of a message.
 */
enum class UnavailableReason {
    /** The device exposes one logical camera and no physical lenses to split. */
    NO_PHYSICAL_CAMERA,
    /** Nothing on this device is wide enough for the x0.7 stop. */
    NO_ULTRAWIDE,
    /** Nothing is long enough for the x2 stop. */
    NO_TELE,
}

object LensPlanner {

    /** Diagonal of a 36×24 mm frame, in millimetres. */
    const val FULL_FRAME_DIAGONAL_MM: Float = 43.266615f

    /** A stop is "ultrawide" below this fraction of the main lens's length. */
    const val ULTRAWIDE_MAX_RATIO: Float = 0.8f

    /** A stop is "tele" at or above this multiple of the main lens's length. */
    const val TELE_MIN_RATIO: Float = 1.7f

    /**
     * The C3 bar. Three stops always, in the prototype's order.
     *
     * The ratios are derived from the device when it has the lenses, and fall
     * back to the prototype's `0.7 / 2×` when it does not, so the bar looks the
     * same on every phone and the *labels* stay truthful.
     */
    fun plan(lenses: List<LensSpec>): LensPlan {
        if (lenses.isEmpty()) {
            return LensPlan(listOf(ZoomStop(1f, null, UnavailableReason.NO_PHYSICAL_CAMERA)))
        }

        val main = pickMain(lenses)
        val others = lenses - main

        val ultrawide = others
            .filter { it.equivalentMm < main.equivalentMm * ULTRAWIDE_MAX_RATIO }
            .maxByOrNull { it.equivalentMm }     // the closest to the main lens
        val tele = others
            .filter { it.equivalentMm >= main.equivalentMm * TELE_MIN_RATIO }
            .minByOrNull { it.equivalentMm }     // the closest to the main lens

        // Only the stops this device can actually shoot. The frame draws three
        // because the prototype device had three; a bar offering a lens the
        // phone does not have is a dead button, and a dead button in a camera is
        // worse than a shorter bar. Measured: the MI 8 exposes one rear camera,
        // so its bar is one stop — which is the truth about that phone.
        val stops = buildList {
            ultrawide?.let { add(ZoomStop(round2(it.equivalentMm / main.equivalentMm), it)) }
            add(ZoomStop(1f, main))
            tele?.let { add(ZoomStop(round2(it.equivalentMm / main.equivalentMm), it)) }
        }.sortedBy { it.ratio }

        return LensPlan(stops)
    }

    /**
     * The main camera is the one closest to a 26 mm equivalent — the wide lens
     * every phone labels `1×`. Picking "the last one" or "the first one" would
     * make the 1× button depend on enumeration order, which is not stable
     * across vendors.
     */
    private fun pickMain(lenses: List<LensSpec>): LensSpec =
        lenses.minByOrNull { abs(it.equivalentMm - MAIN_EQUIVALENT_MM) } ?: lenses.first()

    /** `35 / diagonal` — the crop factor of a sensor of [widthMm] × [heightMm]. */
    fun cropFactor(widthMm: Float, heightMm: Float): Float {
        val diagonal = sqrt(widthMm * widthMm + heightMm * heightMm)
        if (diagonal <= 0f) return 1f
        return FULL_FRAME_DIAGONAL_MM / diagonal
    }

    /** 35 mm-equivalent focal length. Returns [focalMm] when the size is unknown. */
    fun equivalentFocalMm(focalMm: Float, widthMm: Float, heightMm: Float): Float =
        focalMm * cropFactor(widthMm, heightMm)

    private fun round2(v: Float): Float = Math.round(v * 100f) / 100f

    /** The reference the "1×" stop is anchored to. */
    private const val MAIN_EQUIVALENT_MM = 26f
}
