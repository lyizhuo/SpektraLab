package com.spektralab.engine

import android.graphics.Bitmap

/**
 * The JNI surface of `libspektralab.so` — one declaration per native entry
 * point, in the same order as `android/engine/src/main/cpp/jni/engine_jni.cpp`.
 *
 * Rules every caller has to keep (the same three as `spk_engine.h`):
 *
 *  * Nothing throws across the boundary. Replies are JSON strings of the shape
 *    `{"ok": bool, "status": int, "error": string, ...}`; handles are 0 on
 *    failure. `status` is the engine's own `spk_status`.
 *  * Ownership never crosses: every result the engine produced is freed inside
 *    the library, either into a [Bitmap] or as the session's pending result.
 *  * `open`/`setParams`/`render`/`exportImage`/`close` belong to one worker
 *    thread ([EngineClient] guarantees this); [cancel] is safe from any thread.
 *
 * `*fd*` parameters are **consumed**: pass `ParcelFileDescriptor.detachFd()`
 * and never close it yourself.
 */
internal object EngineNative {
    init {
        System.loadLibrary("spektralab")
    }

    /** `spk_engine_create(resourcesDir, null)`. Returns 0 on failure. */
    external fun create(resourcesDir: String): Long
    external fun destroy(handle: Long)

    external fun buildInfo(): String

    /** The compile-time state of the library: build type, `-O`, ABI, API level. */
    external fun nativeBuildInfo(): String

    external fun capabilities(handle: Long): String
    external fun paramsSchema(handle: Long): String
    external fun memoryReport(handle: Long): String
    external fun lastError(handle: Long): String

    /** `spk_warm_up` with the default stock pair (Portra 400 / Endura). */
    external fun warmUp(handle: Long): String

    /**
     * Decodes `fd` and opens a session on it. `paramsDelta` is the wire delta
     * plus the optional decode block:
     * `{"decode": {"headroom": bool, "white_balance": bool,
     * "temperature_k": double, "tint": double}}`.
     */
    external fun open(handle: Long, fd: Int, displayName: String, halfSize: Boolean, paramsDelta: String?): Long

    /** EXIF/format/size without decoding pixels. JSON reply. */
    external fun probe(fd: Int, displayName: String): String

    /** sRGB RGBA8 thumbnail, long edge <= `longEdge`; `outSize` gets [w, h]. */
    external fun thumbnail(fd: Int, displayName: String, longEdge: Int, outSize: IntArray): ByteArray?

    external fun setParams(handle: Long, deltaJson: String): String
    external fun getParams(handle: Long): String

    /**
     * The decoded frame's size, the file's EXIF, and the session's current
     * flat params — everything `open` learned, in one reply.
     */
    external fun sessionInfo(handle: Long): String

    /**
     * Renders `tier` ("live" | "preview" | "full"), `reprint` selecting
     * `spk_reprint` (the cached negative) over `spk_render`. With a [Bitmap] of
     * exactly the render's size the pixels are copied into it; without one (or
     * with a differently sized one) the result is kept as the session's
     * pending result and the reply carries `pixels = "pending"` plus the
     * dimensions, so the caller allocates the Bitmap and calls
     * [copyPendingToBitmap] instead of rendering again.
     */
    external fun render(handle: Long, tier: String, reprint: Boolean, bitmap: Bitmap?, progressId: Int): String
    external fun copyPendingToBitmap(handle: Long, bitmap: Bitmap): Boolean
    external fun releasePending(handle: Long)

    /**
     * Renders `full` and writes `tempPath` transactionally (hidden partial +
     * rename). Spec:
     * `{"format": "tiff16"|"png16"|"png8"|"jpeg8", "color_space": "sRGB"|
     * "Display P3"|"ProPhoto RGB", "jpeg_quality": 1..100, "long_edge": int}`
     * — and **no** `overwrite`: an existing destination fails.
     */
    external fun exportImage(handle: Long, tempPath: String, specJson: String, progressId: Int): String

    /** `spk_cancel` on whatever render is in flight. Safe from any thread. */
    external fun cancel(handle: Long, progressId: Int): Boolean
    external fun progress(handle: Long, progressId: Int): String

    /** `spk_session_release` + frees the JNI-side session. */
    external fun close(handle: Long)
}
