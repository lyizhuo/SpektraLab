package com.spektralab.app.export

import android.content.Context
import android.net.Uri
import com.spektralab.engine.EngineClient
import com.spektralab.engine.EngineSession
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/**
 * The export itself, in one place because two callers need it: the editor's
 * export button and the WorkManager worker that keeps a long export alive
 * after the activity is gone.
 *
 * The flow is the plan's: full-resolution session (close/re-open, because the
 * canvas session is a half-size decode), render + encode inside the native
 * call, then publish — MediaStore with `IS_PENDING` for the album, a SAF
 * document for a picked directory — and delete the temporary file whatever
 * happened.
 */
class ExportRunner(private val context: Context, private val engine: EngineClient) {

    suspend fun run(
        source: Uri,
        displayName: String,
        params: JSONObject,
        spec: ExportSpec,
        target: ExportTarget,
        directory: Uri?,
        onProgress: (String) -> Unit = {},
    ): ExportOutcome {
        val cache = File(context.cacheDir, "export").apply { mkdirs() }
        val temp = File(cache, "${UUID.randomUUID()}.part")
        var session: EngineSession? = null
        return try {
            onProgress("decoding")
            session = engine.open(source.let { fdOf(it) }, displayName, halfSize = false, paramsDelta = null)
            session.setParams(params)
            onProgress("rendering")
            val info = session.export(temp.path, spec.toWireJson())
            onProgress("publishing")
            val name = ImagePublisher.displayName(spec.format, LocalDateTime.now())
            val uri = if (target == ExportTarget.Album) {
                ImagePublisher(context).publishToAlbum(temp, name, spec.format, System.currentTimeMillis())
            } else {
                val picked = directory ?: throw IllegalStateException("no directory was picked")
                ImagePublisher(context).publishToDirectory(temp, picked, name)
            }
            ExportOutcome.Success(
                uri = uri.toString(),
                width = info.width,
                height = info.height,
                bytes = info.bytes,
                format = spec.format,
                colorSpace = spec.colorSpace,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            ExportOutcome.Cancelled
        } catch (failure: Exception) {
            ExportOutcome.Failed(failure.message ?: failure.toString())
        } finally {
            temp.delete()
            // The full-resolution session is the expensive one: giving it back
            // here is what returns the cached negatives to the pool.
            runCatching { engine.closeSession() }
        }
    }

    /**
     * A file descriptor for a SAF uri. The JNI layer takes ownership of it, so
     * this must not be closed by the caller — which is why it is a one-liner
     * here rather than a `use { }` at each call site.
     */
    private fun fdOf(uri: Uri): Int {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.FileNotFoundException("cannot open $uri")
        return descriptor.detachFd()
    }
}
