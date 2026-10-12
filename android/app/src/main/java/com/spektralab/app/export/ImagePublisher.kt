package com.spektralab.app.export

import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * Where an export goes once the engine has written it: the engine's own
 * writer lands a hidden partial file and renames it (plan S6), and this class
 * only publishes finished files.
 *
 * Album exports use MediaStore with `IS_PENDING`, which is the API 29+ way to
 * make a half-written file invisible: the row is created pending, the bytes
 * are copied, and only then is it published. A failure anywhere deletes the
 * pending row, so a rejected or cancelled export leaves nothing behind.
 */
class ImagePublisher(private val context: Context) {

    /** Copies `source` into the user's album, returning the published uri. */
    fun publishToAlbum(source: File, displayName: String, format: ExportFormat, takenAtMillis: Long): Uri {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SpektraLab")
            put(MediaStore.Images.Media.DATE_TAKEN, takenAtMillis)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw FileNotFoundException("MediaStore refused a row for $displayName")
        try {
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output) { "no output stream for $uri" }
                copy(source, output)
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
        return uri
    }

    /** Copies `source` into a user-picked directory (SAF). */
    fun publishToDirectory(source: File, directory: Uri, displayName: String): Uri {
        val document = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            directory,
            android.provider.DocumentsContract.getTreeDocumentId(directory),
        )
        val target = android.provider.DocumentsContract.createDocument(
            context.contentResolver,
            document,
            mimeForName(displayName),
            displayName,
        ) ?: throw FileNotFoundException("the picked directory refused $displayName")
        try {
            context.contentResolver.openOutputStream(target, "w").use { output ->
                requireNotNull(output) { "no output stream for $target" }
                copy(source, output)
            }
        } catch (failure: Throwable) {
            runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, target) }
            throw failure
        }
        return target
    }

    private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "tif", "tiff" -> "image/tiff"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }

    private fun copy(source: File, output: OutputStream) {
        source.inputStream().use { input -> input.copyTo(output, bufferSize = 1 shl 16) }
        output.flush()
    }

    companion object {
        /** `SpektraLab_2026-10-12_143012.tif`, which sorts by day and time. */
        fun displayName(format: ExportFormat, at: java.time.LocalDateTime): String {
            val stamp = at.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
            return "SpektraLab_$stamp.${format.extension}"
        }
    }
}
