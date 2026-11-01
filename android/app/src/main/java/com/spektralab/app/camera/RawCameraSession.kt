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
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One open rear camera, raw-capable, with a capture path that writes a real DNG.
 *
 * ### Why Camera2 and not CameraX
 *
 * `ImageCapture.OUTPUT_FORMAT_RAW` exists, but the RAW buffer still has to go
 * through `DngCreator` to become a file the engine can read, and CameraX's RAW
 * support is narrower than Camera2's. `ANDROID_PLAN.md` §4.4 settles it: preview
 * on one path, RAW on Camera2. This class is that Camera2 path.
 *
 * ### What the RAW buffer is
 *
 * `RAW_SENSOR` is unprocessed Bayer — no black level, no lens shading, no white
 * balance, no colour matrix. `DngCreator` is the only platform code that writes
 * those into the file, which is why the capture goes through it and not through a
 * hand-rolled TIFF writer: without that metadata the engine's LibRaw gets a
 * plausible-looking but wrong picture.
 *
 * ### The two sizes that matter
 *
 * `rawSize` is the largest `RAW_SENSOR` output — the sensor's own frame, and what
 * the DNG contains. `previewSize` is a cheap size **with the same aspect**, pinned
 * on the SurfaceHolder by the caller. They must agree in shape or the viewfinder
 * lies about the edges; they must differ in size or the preview costs as much as
 * the capture.
 */
class RawCameraSession(
    private val context: Context,
    /** The lens this session owns; the caller compares ids to decide a reopen. */
    val lens: LensSpec,
) : AutoCloseable {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("raw-camera").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var characteristics: CameraCharacteristics? = null
    private var lastResult: TotalCaptureResult? = null
    private var pending: PendingCapture? = null
    private var capturing = false
    private var torchOn = false

    /** The DNG's frame size. */
    var rawSize: Size = Size(0, 0)
        private set

    /** The viewfinder's buffer size; the caller pins its SurfaceHolder to this. */
    var previewSize: Size = Size(0, 0)
        private set

    /** Latest exposure readout, for the C3 `AUTO · ISO 400 / 1/125 F4.0` line. */
    var readout: ExposureReadout = ExposureReadout.UNKNOWN
        private set

    /**
     * Open the camera and start the viewfinder.
     *
     * Suspends until the capture session is configured and returns the RAW size,
     * so the caller can lay out the frame before the first capture.
     */
    suspend fun open(previewTarget: Surface): Size = suspendCancellableCoroutine { cont ->
        previewSurface = previewTarget
        try {
            @Suppress("MissingPermission")
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

                        // Same shape as the capture, cheap enough for a preview.
                        previewSize = choosePreviewSize(
                            map.getOutputSizes(SurfaceHolder::class.java)?.toList().orEmpty(),
                            raw.width.toFloat() / raw.height.toFloat(),
                        )

                        val outputs = listOfNotNull(previewTarget, imageReader.surface)
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(
                            outputs,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    startPreview()
                                    if (cont.isActive) cont.resume(raw)
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    if (cont.isActive) {
                                        cont.resumeWithException(
                                            IllegalStateException("capture session configure failed"),
                                        )
                                    }
                                }
                            },
                            handler,
                        )
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("camera disconnected"))
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("camera error $error"))
                    }
                }
            }, handler)
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    /**
     * The flash button.
     *
     * `FLASH_MODE_TORCH` is the honest control: a lamp that stays on while you
     * frame, which is what the button on a viewfinder implies. A capture flash
     * would be `TEMPLATE_STILL_CAPTURE` + `FLASH_MODE_SINGLE`, and on a phone
     * without xenon that is a pre-flash burst nobody can compose with.
     */
    fun setTorch(on: Boolean) {
        if (torchOn == on) return
        torchOn = on
        if (session != null && device != null) startPreview()
    }

    /**
     * The EV dial's setting, in the camera's own integer steps.
     *
     * The prototype shows a floating EV reel beside the viewfinder, and this is
     * the field behind it: `CONTROL_AE_EXPOSURE_COMPENSATION` is an index, not a
     * number of stops -- `CONTROL_AE_COMPENSATION_STEP` says what a step is on
     * this device, and `CONTROL_AE_COMPENSATION_RANGE` how far it goes. The
     * screen converts; the session just applies whatever index it is given.
     */
    @Volatile
    var exposureCompensation: Int = 0
        private set

    /** Apply an EV index to the running preview, restarting the repeat. */
    fun setExposureCompensation(steps: Int) {
        if (steps == exposureCompensation) return
        exposureCompensation = steps
        startPreview()
    }

    /** Repeating request for the viewfinder, and the readout it implies. */
    private fun startPreview() {
        val d = device ?: return
        val s = session ?: return
        val target = previewSurface ?: return
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(target)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation)
            set(
                CaptureRequest.FLASH_MODE,
                if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
            )
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
        val r = reader
        if (d == null || s == null || r == null) {
            cont.resumeWithException(IllegalStateException("camera not ready"))
            return@suspendCancellableCoroutine
        }
        if (capturing) {
            cont.resumeWithException(IllegalStateException("a capture is already in flight"))
            return@suspendCancellableCoroutine
        }
        capturing = true
        pending = PendingCapture(dest, cont)
        try {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation)
                addTarget(r.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(
                    CaptureRequest.FLASH_MODE,
                    if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
                )
            }
            s.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    // The result and the image arrive on separate callbacks with
                    // no ordering guarantee between them; the pair is matched in
                    // onImage, which is why the result is stashed here.
                    lastResult = result
                }
            }, handler)
        } catch (e: Exception) {
            capturing = false
            pending = null
            cont.resumeWithException(e)
        }
    }

    /** The RAW frame plus the destination and the caller waiting on it. */
    private data class PendingCapture(
        val dest: File,
        val cont: CancellableContinuation<Long>,
    )

    private fun onImage(r: ImageReader) {
        val image = try {
            r.acquireNextImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquireNextImage failed", e)
            return
        } ?: return

        val p = pending
        val result = lastResult
        val chars = characteristics
        try {
            if (p == null || result == null || chars == null) {
                // Nothing waiting for it (a stale frame after cancel). Dropping
                // it is correct; DngCreator would have no metadata to attach.
                Log.w(TAG, "raw frame with no pending capture or no result")
                return
            }
            var written = 0L
            FileOutputStream(p.dest).use { out ->
                DngCreator(chars, result).use { creator ->
                    creator.setDescription("Latent ${lens.equivalentMm.toInt()}mm")
                    // The sensor's raster is landscape whatever the phone is
                    // doing. Without this tag every reader — the engine's LibRaw
                    // included — shows a portrait scene rotated and squashed,
                    // because nothing else in the file says how it was held.
                    creator.setOrientation(exifOrientation())
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

    /**
     * The EXIF orientation that makes the file look like the phone looked.
     *
     * `SENSOR_ORIENTATION` is the sensor's mounting angle (90° on most phones);
     * the device's rotation is subtracted, because the picture the user framed
     * was taken with the phone held that way. Plain EXIF integers rather than
     * `ExifInterface` constants: one dependency fewer for four numbers.
     */
    private fun exifOrientation(): Int {
        val sensor = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val rotation = try {
            val wm = context.getSystemService(WindowManager::class.java)
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        } catch (e: Exception) {
            Surface.ROTATION_0
        }
        return when (((sensor - rotation * 90) + 360) % 360) {
            90 -> 6
            180 -> 3
            270 -> 8
            else -> 1
        }
    }

    override fun close() {
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "session close failed", e)
        }
        try {
            device?.close()
        } catch (e: Exception) {
            Log.w(TAG, "device close failed", e)
        }
        reader?.close()
        session = null
        device = null
        reader = null
        previewSurface = null
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "RawCameraSession"

        /**
         * The capture frame's own size — the largest `RAW_SENSOR` output.
         *
         * The viewfinder is composed against this aspect, not the screen's, so
         * what the user frames is what the DNG contains.
         */
        fun rawSizeFor(context: Context, lens: LensSpec?): Size {
            if (lens == null) return Size(4000, 3000)
            return try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val map = manager.getCameraCharacteristics(lens.cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                map?.getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?.maxByOrNull { it.width.toLong() * it.height }
                    ?: Size(4000, 3000)
            } catch (e: Exception) {
                Size(4000, 3000)
            }
        }

        /**
         * How far this camera can compensate, in EV steps of [evStep].
         *
         * C3 draws the reel as a continuous ±offset, but the camera only takes
         * whole indices and its ends differ per device (phones commonly offer
         * ±2 or ±3 EV). Asking the characteristics is what keeps the reel's ends
         * honest instead of printing a range the hardware does not have.
         */
        fun evIndexRange(context: Context, lens: LensSpec?): IntRange {
            if (lens == null) return -4..4
            return try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val range = manager.getCameraCharacteristics(lens.cameraId)
                    .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                if (range == null) -4..4 else range.lower..range.upper
            } catch (e: Exception) {
                -4..4
            }
        }

        /** What one index of that range is worth, in stops. */
        fun evStep(context: Context, lens: LensSpec?): Float {
            if (lens == null) return 0.5f
            return try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val step = manager.getCameraCharacteristics(lens.cameraId)
                    .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                step?.toFloat() ?: 0.5f
            } catch (e: Exception) {
                0.5f
            }
        }

        /** The viewfinder's buffer size: same shape as the DNG, cheap to draw. */
        fun previewSizeFor(context: Context, lens: LensSpec?): Size {
            if (lens == null) return Size(1920, 1080)
            return try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val map = manager.getCameraCharacteristics(lens.cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return Size(1920, 1080)
                val raw = rawSizeFor(context, lens)
                choosePreviewSize(
                    map.getOutputSizes(SurfaceHolder::class.java)?.toList().orEmpty(),
                    raw.width.toFloat() / raw.height.toFloat(),
                )
            } catch (e: Exception) {
                Size(1920, 1080)
            }
        }

        /**
         * The one place a viewfinder size is decided.
         *
         * A 2 MP ceiling keeps the preview cheap and leaves the GPU for the
         * render. **Aspect is the hard requirement**: a preview with a different
         * shape than the DNG either crops or stretches, and both are the
         * viewfinder lying about the edges. Size only breaks ties among the
         * candidates that already match.
         */
        private fun choosePreviewSize(sizes: List<Size>, targetAspect: Float): Size {
            if (sizes.isEmpty()) return Size(1920, 1080)
            val withinBudget = sizes.filter { it.width.toLong() * it.height <= 2_100_000L }
                .ifEmpty { listOf(sizes.minByOrNull { it.width.toLong() * it.height }!!) }
            return withinBudget.minByOrNull { size ->
                val aspectError = kotlin.math.abs(size.width.toDouble() / size.height - targetAspect)
                aspectError * 1000 +
                    kotlin.math.abs(size.width.toLong() * size.height - 2_073_600L) / 1_000_000.0
            } ?: sizes.first()
        }
    }
}

/**
 * `AUTO · ISO 400` and `1/125 F4.0`, read from the capture result.
 *
 * These are the numbers C3 prints under the viewfinder. They come from the
 * result rather than the request because the device may override either
 * (`CONTROL_AE_MODE_ON` means it usually does), and a readout that disagrees
 * with the file is worse than no readout.
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
