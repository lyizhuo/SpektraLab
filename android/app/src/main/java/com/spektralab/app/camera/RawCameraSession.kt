package com.spektralab.app.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One open rear camera, raw-capable, with a capture path that writes a real DNG.
 *
 * This is deliberately **Camera2 rather than CameraX**. CameraX can hand back
 * `OUTPUT_FORMAT_RAW`, but the RAW buffer still has to go through `DngCreator`
 * with the matching `CaptureResult`, and CameraX does not expose that pairing —
 * so the moment a capture has to be useful (a DNG that LibRaw can open with the
 * right black level, colour matrix and white balance) it is Camera2 anyway.
 * Preview from the same session also keeps the shutter responsive: there is no
 * second camera stack to reconfigure between viewfinder and still.
 *
 * ### Why the DNG path matters
 *
 * `RAW_SENSOR` is unprocessed Bayer. `DngCreator` is the only platform code
 * that assembles the metadata LibRaw needs to produce a *scene-linear* image:
 * black level, `SENSOR_NEUTRAL_COLOR_POINT`, `COLOR_CORRECTION_TRANSFORM`,
 * orientation. Write those by hand and the file opens, but the film model gets
 * a wrong scene estimate — which is `AGENTS.md` trap 12's whole subject, and it
 * looks like a colour bug in the engine rather than a capture bug.
 *
 * ### Threading
 *
 * Callbacks arrive on [thread]. `capture()` suspends the caller and resumes
 * when the file is closed and flushed, so the caller can hand the path straight
 * to the engine. Nothing in this class touches the main thread except the
 * `CameraDevice` open callback, which the framework delivers on the handler
 * this class supplies.
 */
class RawCameraSession(
    context: Context,
    /** The lens this session owns; the caller compares ids to decide a reopen. */
    val lens: LensSpec,
) : AutoCloseable {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("spk-camera").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var characteristics: CameraCharacteristics? = null

    /** The RAW size this sensor actually produces, e.g. 4000×3000 binned. */
    var rawSize: Size = Size(0, 0)
        private set

    /**
     * The result that belongs to the frame in the reader.
     *
     * `onCaptureCompleted` and `onImageAvailable` are separate callbacks with no
     * ordering guarantee between them, so the pair is matched by the capture
     * request's `CONTROL_AF_MODE`-agnostic tag: we issue exactly one capture at
     * a time and keep the newest result. Issuing a second capture before the
     * first image lands would break this, which is why [capturing] guards it.
     */
    private var lastResult: TotalCaptureResult? = null
    private var capturing = false

    /** Latest exposure readout, for the C3 `AUTO · ISO 400 / 1/125 F4.0` line. */
    @Volatile
    var readout: ExposureReadout = ExposureReadout.UNKNOWN
        private set

    suspend fun open(previewTarget: Surface): Size = suspendCancellableCoroutine { cont ->
        previewSurface = previewTarget
        try {
            manager.openCamera(lens.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    try {
                        val chars = manager.getCameraCharacteristics(lens.cameraId)
                        characteristics = chars
                        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            ?: throw IllegalStateException("no stream configuration map")
                        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                            ?: throw IllegalStateException("no RAW_SENSOR sizes")
                        // The largest RAW size is the sensor's own; on a binned-
                        // only device that is the 12.5 MP mode, which is exactly
                        // the pixel count this product targets.
                        val raw = rawSizes.maxByOrNull { it.width.toLong() * it.height }!!
                        rawSize = raw
                        val imageReader = ImageReader.newInstance(
                            raw.width, raw.height, ImageFormat.RAW_SENSOR, /* maxImages = */ 2,
                        )
                        reader = imageReader
                        imageReader.setOnImageAvailableListener({ r -> onImage(r) }, handler)

                        val outputs = listOfNotNull(previewTarget, imageReader.surface)
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                startPreview()
                                if (cont.isActive) cont.resume(raw)
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                if (cont.isActive) {
                                    cont.resumeWithException(IllegalStateException("capture session configure failed"))
                                }
                            }
                        }, handler)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); device = null
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("camera disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); device = null
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("camera error $error"))
                }
            }, handler)
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    /** Repeating request for the viewfinder, and the exposure readout it implies. */
    private fun startPreview() {
        val d = device ?: return
        val s = session ?: return
        val target = previewSurface ?: return
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(target)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        s.setRepeatingRequest(req.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                lastResult = result
                readout = ExposureReadout.of(result, lens)
            }
        }, handler)
    }

    /**
     * Fire the shutter and write [dest].
     *
     * Returns the DNG size on disk. Throws if the camera is not open, if a
     * capture is already in flight, or if `DngCreator` refuses the pair — all
     * three are programming errors for the caller, not user-visible states.
     */
    suspend fun capture(dest: File): Long = suspendCancellableCoroutine { cont ->
        val d = device
        val s = session
        val chars = characteristics
        if (d == null || s == null || chars == null) {
            cont.resumeWithException(IllegalStateException("camera not open"))
            return@suspendCancellableCoroutine
        }
        if (capturing) {
            cont.resumeWithException(IllegalStateException("capture already in flight"))
            return@suspendCancellableCoroutine
        }
        capturing = true
        lastResult = null
        pending = PendingCapture(dest, chars, cont)

        // One precapture trigger, then STILL_CAPTURE. Skipping the trigger is
        // what makes a first shot come out dark at night: AE has not converged.
        try {
            val trigger = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader!!.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            }.build()

            s.capture(trigger, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    lastResult = result
                    readout = ExposureReadout.of(result, lens)
                }
            }, handler)
        } catch (e: Exception) {
            capturing = false
            pending = null
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    private class PendingCapture(
        val dest: File,
        val chars: CameraCharacteristics,
        val cont: kotlinx.coroutines.CancellableContinuation<Long>,
    )

    private var pending: PendingCapture? = null

    /**
     * The RAW frame plus its metadata, which is the only place `DngCreator` may
     * be constructed from.
     */
    private fun onImage(r: ImageReader) {
        val image = try {
            r.acquireNextImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquireNextImage failed", e)
            return
        } ?: return

        val p = pending
        val result = lastResult
        try {
            if (p == null || result == null) {
                // Nothing waiting for it (a stale frame after cancel). Dropping
                // it is correct; DngCreator would have no metadata to attach.
                Log.w(TAG, "raw frame with no pending capture or no result")
                return
            }
            var written = 0L
            FileOutputStream(p.dest).use { out ->
                DngCreator(p.chars, result).use { creator ->
                    creator.setDescription("SpektraLab ${lens.equivalentMm.toInt()}mm")
                    creator.writeImage(out, image)
                }
                out.flush()
                written = p.dest.length()
            }
            capturing = false
            pending = null
            if (p.cont.isActive) p.cont.resume(written)
        } catch (e: Exception) {
            capturing = false
            pending = null
            if (p != null && p.cont.isActive) p.cont.resumeWithException(e)
            else Log.w(TAG, "raw write failed", e)
        } finally {
            image.close()
        }
    }

    override fun close() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        try {
            device?.close()
        } catch (_: Exception) {
        }
        device = null
        reader?.close()
        reader = null
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "RawCameraSession"
    }
}

/**
 * `AUTO · ISO 400` and `1/125 F4.0`, read from the capture result.
 *
 * These are the numbers the C3 screen prints under the viewfinder. They come
 * from the result rather than from the request because the device may override
 * either (`CONTROL_AE_MODE_ON` means it usually does), and a readout that
 * disagrees with the file is worse than no readout.
 */
data class ExposureReadout(
    val iso: Int,
    val exposureNs: Long,
    val aperture: Float,
) {
    /** `1/125` or `2.5"`, the way a camera prints it. */
    val shutterLabel: String
        get() = when {
            exposureNs <= 0L -> "—"
            exposureNs >= 1_000_000_000L / 2 -> String.format("%.1f\"", exposureNs / 1e9)
            else -> "1/${Math.round(1e9 / exposureNs)}"
        }

    val apertureLabel: String get() = if (aperture > 0f) String.format("F%.1f", aperture) else ""

    val isoLabel: String get() = if (iso > 0) "ISO $iso" else ""

    companion object {
        val UNKNOWN = ExposureReadout(0, 0L, 0f)

        fun of(result: CaptureResult, lens: LensSpec): ExposureReadout = ExposureReadout(
            iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
            exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            aperture = result.get(CaptureResult.LENS_APERTURE) ?: 0f,
        )
    }
}
