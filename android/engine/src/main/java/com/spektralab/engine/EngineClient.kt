package com.spektralab.engine

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** One native reply, as JSON, with the three fields every reply carries. */
class EngineReply(val json: JSONObject) {
    val ok: Boolean get() = json.optBoolean("ok", false)
    val status: Int get() = json.optInt("status", 0)
    val error: String get() = json.optString("error", "")
}

class EngineException(val status: Int, message: String) : Exception(message) {
    /** The session the call named had already been released. */
    val isSessionGone: Boolean get() = status == SESSION_CLOSED

    companion object {
        const val SESSION_CLOSED = -1000
    }
}

data class Thumbnail(val width: Int, val height: Int, val rgba: ByteArray)

/** What `open` learned about the frame: decoded size and the file's EXIF. */
data class OpenInfo(
    val width: Int,
    val height: Int,
    val params: JSONObject,
    val metadata: JSONObject,
)

data class RenderInfo(
    val tier: String,
    val width: Int,
    val height: Int,
    val elapsedMs: Double,
    val reprint: Boolean,
    val negativeWasCached: Boolean,
    val memory: JSONObject?,
    val pending: Boolean,
)

data class ExportInfo(
    val path: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val format: String,
    val colorSpace: String,
)

/**
 * The engine, on one worker thread (plan D8).
 *
 * Every method hops to that thread: the engine is single-worker by
 * construction, a render is seconds long, and Compose must not share a thread
 * with a 12 MP dispatch. Cancellation is the exception and does not go through
 * the worker at all — `spk_cancel` is documented safe from another thread.
 */
class EngineClient(private val resourcesDir: String) : Closeable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spektralab-engine")
    }
    internal val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val idSource = AtomicLong(1)

    private var handle: Long = 0
    /** Every session this client opened and has not closed yet. */
    private val openSessions = LinkedHashSet<EngineSession>()

    /** The `capabilities` reply, available after [start]. */
    lateinit var capabilities: JSONObject
        private set

    /** The `params_schema` reply, available after [start]. */
    lateinit var paramsSchema: JSONObject
        private set

    val buildInfo: String get() = EngineNative.buildInfo()

    /** The compile-time state of the library (build type, `-O`, ABI). */
    val nativeBuildInfo: JSONObject get() = JSONObject(EngineNative.nativeBuildInfo())

    /** The most recently opened session, if it is still open. */
    val currentSession: EngineSession? get() = openSessions.lastOrNull()?.takeIf { !it.isClosed }

    fun nextProgressId(): Int = idSource.getAndIncrement().toInt()

    val isStarted: Boolean get() = handle != 0L

    /**
     * Creates the engine and pays the default stock pair's setup.
     *
     * Idempotent, because the editor and an export worker share this one
     * client and either may be the first to need it (plan S4: one engine per
     * process).
     */
    suspend fun start(): JSONObject = withContext(dispatcher) {
        if (handle != 0L) return@withContext capabilities
        val created = EngineNative.create(resourcesDir)
        if (created == 0L) {
            val error = EngineNative.lastError(0)
            throw EngineException(-1, "spk_engine_create failed: $error (resources=$resourcesDir)")
        }
        handle = created
        capabilities = JSONObject(EngineNative.capabilities(handle))
        paramsSchema = JSONObject(EngineNative.paramsSchema(handle))
        EngineNative.warmUp(handle)
        capabilities
    }

    suspend fun memoryReport(): JSONObject = withContext(dispatcher) {
        JSONObject(EngineNative.memoryReport(requireHandle()))
    }

    /**
     * Opens one frame from an already-detached descriptor. The descriptor is
     * consumed: pass `ParcelFileDescriptor.detachFd()` and do not close it.
     */
    suspend fun open(
        fd: Int,
        displayName: String,
        halfSize: Boolean,
        paramsDelta: JSONObject? = null,
    ): EngineSession = withContext(dispatcher) {
        releaseSession()
        val engine = requireHandle()
        val opened = EngineNative.open(engine, fd, displayName, halfSize, paramsDelta?.toString())
        if (opened == 0L) throw EngineException(-1, "open failed: ${EngineNative.lastError(engine)}")
        val info = JSONObject(EngineNative.sessionInfo(opened))
        if (!info.optBoolean("ok", false)) {
            EngineNative.close(opened)
            throw EngineException(info.optInt("status", -1), info.optString("error"))
        }
        val created = EngineSession(this@EngineClient, opened, info)
        register(created)
        created
    }

    /** EXIF/format/size without decoding pixels (consumed descriptor). */
    suspend fun probe(fd: Int, displayName: String): JSONObject = withContext(dispatcher) {
        JSONObject(EngineNative.probe(fd, displayName)).let { reply ->
            if (!reply.optBoolean("ok", false)) throw EngineException(reply.optInt("status", -1), reply.optString("error"))
            reply
        }
    }

    suspend fun thumbnail(fd: Int, displayName: String, longEdge: Int): Thumbnail = withContext(dispatcher) {
        val size = IntArray(2)
        val bytes = EngineNative.thumbnail(fd, displayName, longEdge, size)
            ?: throw EngineException(-1, "no thumbnail for $displayName")
        Thumbnail(size[0], size[1], bytes)
    }

    internal fun register(session: EngineSession) {
        openSessions.add(session)
    }

    internal fun render(sessionHandle: Long, tier: String, reprint: Boolean, bitmap: Bitmap?, progressId: Int): EngineReply =
        EngineReply(JSONObject(EngineNative.render(sessionHandle, tier, reprint, bitmap, progressId)))

    internal fun copyPending(sessionHandle: Long, bitmap: Bitmap): Boolean =
        EngineNative.copyPendingToBitmap(sessionHandle, bitmap)

    internal fun export(sessionHandle: Long, tempPath: String, spec: JSONObject, progressId: Int): EngineReply =
        EngineReply(JSONObject(EngineNative.exportImage(sessionHandle, tempPath, spec.toString(), progressId)))

    internal fun setParams(sessionHandle: Long, delta: JSONObject): EngineReply =
        EngineReply(JSONObject(EngineNative.setParams(sessionHandle, delta.toString())))

    internal fun getParams(sessionHandle: Long): JSONObject = JSONObject(EngineNative.getParams(sessionHandle))

    internal fun progress(sessionHandle: Long, progressId: Int): JSONObject =
        JSONObject(EngineNative.progress(sessionHandle, progressId))

    /**
     * Closes every session this client opened.
     *
     * It closes *all* of them because the engine holds one GPU device and the
     * expensive state (a frame's cached negatives) belongs to a session: the
     * export worker and the editor share this client, and an editor holding a
     * handle the worker's export already released is a use-after-free waiting
     * to happen. A released session says so on every later call
     * ([EngineSession.isClosed]) instead of reaching the engine.
     */
    internal fun releaseSession() {
        for (session in openSessions.toList()) {
            EngineNative.close(session.handle)
            session.markClosed()
        }
        openSessions.clear()
    }

    /** Closes the open frame, returning the engine's cached negatives with it. */
    suspend fun closeSession() = withContext(dispatcher) { releaseSession() }

    private fun requireHandle(): Long = handle.also { check(it != 0L) { "engine not created" } }

    override fun close() {
        releaseSession()
        if (handle != 0L) {
            EngineNative.destroy(handle)
            handle = 0
        }
        executor.shutdown()
    }
}

/** One open frame. Every method belongs to [EngineClient]'s worker thread. */
class EngineSession internal constructor(
    private val client: EngineClient,
    internal val handle: Long,
    info: JSONObject,
) {
    /** The decoded frame: `width`/`height` are what was handed to `spk_open`. */
    val width: Int = info.optInt("width")
    val height: Int = info.optInt("height")

    /** The file's own EXIF, as the probe reported it. */
    val metadata: JSONObject = info.optJSONObject("metadata") ?: JSONObject()

    var params: JSONObject = info.optJSONObject("params") ?: JSONObject()
        private set

    /** Set when the client closed this session; every call below checks it. */
    @Volatile
    private var closed = false

    val isClosed: Boolean get() = closed

    internal fun markClosed() {
        closed = true
    }

    private fun requireOpen() {
        if (closed) throw EngineException(EngineException.SESSION_CLOSED, "the session was closed by another export or open")
    }

    val decodedHalfSize: Boolean = info.optBoolean("half_size", false)

    /** Applies a wire delta; throws when the engine rejects a field. */
    suspend fun setParams(delta: JSONObject): JSONObject = withContext(client.dispatcher) {
        requireOpen()
        val reply = client.setParams(handle, delta)
        if (!reply.ok) throw EngineException(reply.status, reply.error)
        params = client.getParams(handle).optJSONObject("params") ?: params
        params
    }

    suspend fun refreshParams(): JSONObject = withContext(client.dispatcher) {
        requireOpen()
        params = client.getParams(handle).optJSONObject("params") ?: params
        params
    }

    /**
     * Renders `tier` into `reuse` when that Bitmap already has the right size.
     * Otherwise the native side holds the pixels and the Bitmap is allocated
     * here, so either way one render produces exactly one frame.
     */
    suspend fun renderInto(tier: String, reprint: Boolean, reuse: Bitmap?): Pair<Bitmap, RenderInfo> =
        withContext(client.dispatcher) {
            requireOpen()
            val progressId = client.nextProgressId()
            val reply = client.render(handle, tier, reprint, reuse, progressId)
            if (!reply.ok) throw EngineException(reply.status, reply.error)
            var info = renderInfo(tier, reply)
            var bitmap = reuse
            if (info.pending) {
                bitmap = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
                if (!client.copyPending(handle, bitmap)) {
                    throw EngineException(-1, "copyPendingToBitmap failed for $tier")
                }
                info = info.copy(pending = false)
            }
            bitmap!! to info
        }

    suspend fun export(tempPath: String, spec: JSONObject): ExportInfo = withContext(client.dispatcher) {
        requireOpen()
        val reply = client.export(handle, tempPath, spec, client.nextProgressId())
        if (!reply.ok) throw EngineException(reply.status, reply.error)
        ExportInfo(
            path = reply.json.optString("path"),
            width = reply.json.optInt("width"),
            height = reply.json.optInt("height"),
            bytes = reply.json.optLong("bytes"),
            format = reply.json.optString("format"),
            colorSpace = reply.json.optString("color_space"),
        )
    }

    suspend fun progress(): JSONObject = withContext(client.dispatcher) {
        requireOpen()
        client.progress(handle, 0)
    }

    /**
     * Cancels whatever render is in flight. Safe from any thread, including
     * the main one: `spk_cancel` deliberately does not take the session lock.
     */
    fun cancelFromAnyThread(): Boolean = !closed && EngineNative.cancel(handle, 0)

    private fun renderInfo(tier: String, reply: EngineReply): RenderInfo {
        val json = reply.json
        return RenderInfo(
            tier = tier,
            width = json.optInt("width"),
            height = json.optInt("height"),
            elapsedMs = json.optDouble("elapsed_ms", 0.0),
            reprint = json.optInt("reprint") == 1,
            negativeWasCached = json.optInt("negative_was_cached") == 1,
            memory = json.optJSONObject("memory"),
            pending = json.optString("pixels") == "pending",
        )
    }
}
