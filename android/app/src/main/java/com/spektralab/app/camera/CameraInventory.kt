package com.spektralab.app.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.SizeF

/**
 * What this device's rear cameras can actually do, read once and kept.
 *
 * Two things make this less trivial than it looks.
 *
 * **Physical lenses are not the same list as camera ids.** A logical
 * multi-camera device reports one back camera id, whose
 * [CameraCharacteristics.getPhysicalCameraIds] names the per-lens ids. Most
 * vendors *also* list the physical ids in `CameraManager.cameraIdList`, so the
 * same 26 mm lens can appear twice — once as the logical camera and once as its
 * own id — with identical focal length. Deduplicating by equivalent focal
 * length is what stops the C3 bar from showing `1×` twice.
 *
 * **`getCameraCharacteristics` on a physical id can throw** on some devices
 * even when `getPhysicalCameraIds` returned it (the id is valid for session
 * configuration, not for a full characteristic read). That is caught per lens:
 * one unreadable physical camera must not cost us the main one.
 *
 * The result is a [LensPlan] — pure data — plus the flags the readiness
 * decision needs. Nothing here touches a capture session, so it is safe to call
 * while the UI is composing.
 */
class CameraInventory(context: Context) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** Reads every rear camera. Never throws for a single bad device. */
    fun probe(): ProbeResult {
        val specs = linkedMapOf<String, LensSpec>()
        var sawAny = false

        val ids = try {
            manager.cameraIdList
        } catch (e: Exception) {
            Log.w(TAG, "cameraIdList failed", e)
            return ProbeResult(emptyList(), LensPlanner.plan(emptyList()))
        }

        for (id in ids) {
            val chars = try {
                manager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                Log.w(TAG, "characteristics for $id failed", e)
                continue
            }
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                continue
            }
            sawAny = true
            specOf(id, chars)?.let { addDeduped(specs, it) }

            // The logical camera's own id is one lens; its physical children are
            // the rest of the bar. Only walk them when the device says it is a
            // logical multi-camera — otherwise getPhysicalCameraIds throws.
            val multi = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
            if (multi) {
                val physical = try {
                    chars.physicalCameraIds
                } catch (e: Exception) {
                    emptySet()
                }
                for (pid in physical) {
                    if (specs.containsKey(pid)) continue
                    val pChars = try {
                        manager.getCameraCharacteristics(pid)
                    } catch (e: Exception) {
                        continue
                    }
                    specOf(pid, pChars)?.let { addDeduped(specs, it) }
                }
            }
        }

        val lenses = specs.values.sortedBy { it.equivalentMm }
        // One line per probe: which physical lenses the device really
        // exposes, in millimetres and in RAW capability. Without it a
        // lens bar that offers nothing looks like a UI bug.
        Log.i(
            TAG,
            "lenses=" + lenses.joinToString {
                "${it.cameraId}:${it.equivalentMm.toInt()}mm raw=${it.hasRaw}"
            } + " stops=" + LensPlanner.plan(lenses).zoomStops.joinToString {
                "${it.ratio}x lens=${it.lens?.cameraId ?: "none"}"
            },
        )
        return ProbeResult(lenses, LensPlanner.plan(lenses), sawAny)
    }

    /**
     * One lens, or null when the device does not report enough to place it.
     *
     * A lens with no `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` or no physical sensor
     * size cannot be mapped to a zoom ratio, and a stop whose label is a guess
     * is worse than a stop that says it is unavailable.
     */
    private fun specOf(id: String, chars: CameraCharacteristics): LensSpec? {
        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: return null
        val focal = focals.firstOrNull() ?: return null
        val size: SizeF = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        if (size.width <= 0f || size.height <= 0f) return null

        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val hasRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val hasManual = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)

        return LensSpec(
            cameraId = id,
            equivalentMm = LensPlanner.equivalentFocalMm(focal, size.width, size.height),
            focalMm = focal,
            hasRaw = hasRaw,
            hasManualSensor = hasManual,
        )
    }

    /**
     * One entry per *lens*, not per id.
     *
     * The logical camera and its main physical child usually report the same
     * focal length and sensor size. They are kept as separate ids by the
     * platform but are the same piece of glass, so they collapse to whichever
     * id can actually deliver RAW — that is the id worth opening.
     */
    private fun addDeduped(into: MutableMap<String, LensSpec>, spec: LensSpec) {
        val key = "%.2f".format(spec.equivalentMm)
        val existing = into[key]
        if (existing == null) {
            into[key] = spec
        } else if (!existing.hasRaw && spec.hasRaw) {
            // Prefer the id that can shoot RAW even if the other id sorts first.
            into[key] = spec.copy(cameraId = existing.cameraId)
        }
    }

    data class ProbeResult(
        val lenses: List<LensSpec>,
        val plan: LensPlan,
        /** False when the device has no back camera at all. */
        val hasBackCamera: Boolean = true,
    )

    private companion object {
        const val TAG = "CameraInventory"
    }
}
