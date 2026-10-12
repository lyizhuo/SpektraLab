package com.spektralab.app.edits

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Edits survive the app being closed (plan S6): the wire params, the UI's own
 * state and the geometry, one JSON file per photograph.
 *
 * The key is a digest of what the photo *is* rather than its path: the uri,
 * its size and its last-modified stamp. A uri is stable for a document the
 * user picked, but a file replaced in place under the same name is not the
 * same photograph, and the size/stamp pair is what tells them apart. The key
 * is stored inside the file as well, so a file that was renamed on disk is
 * rejected on load instead of silently applied to the wrong frame.
 */
class EditStore(private val context: Context) {

    private val directory = File(context.filesDir, "edits")

    data class Stored(
        val key: String,
        val params: JSONObject,
        val ui: JSONObject,
        val geometry: JSONObject,
    )

    fun keyFor(uri: Uri): String {
        val resolver = context.contentResolver
        var size = -1L
        var modified = -1L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    val modifiedIndex = cursor.getColumnIndex("last_modified")
                    if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) modified = cursor.getLong(modifiedIndex)
                }
            }
        }
        // The document id is the stable part of a SAF uri; the rest can carry
        // a per-grant token.
        val identity = "$uri|$size|$modified"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun load(uri: Uri): Stored? {
        val key = keyFor(uri)
        val file = File(directory, "$key.json")
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            if (json.optString("key") != key) return null
            Stored(
                key = key,
                params = json.optJSONObject("params") ?: JSONObject(),
                ui = json.optJSONObject("ui") ?: JSONObject(),
                geometry = json.optJSONObject("geometry") ?: JSONObject(),
            )
        }.getOrNull()
    }

    fun save(uri: Uri, params: JSONObject, ui: JSONObject, geometry: JSONObject) {
        directory.mkdirs()
        val key = keyFor(uri)
        val json = JSONObject().apply {
            put("key", key)
            put("params", params)
            put("ui", ui)
            put("geometry", geometry)
            put("saved_at", System.currentTimeMillis())
        }
        // Same transactional shape as the exports: a partial file the reader
        // never sees, then a rename.
        val target = File(directory, "$key.json")
        val partial = File(directory, "$key.json.partial")
        partial.writeText(json.toString())
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
    }

    fun delete(uri: Uri) {
        File(directory, "${keyFor(uri)}.json").delete()
    }

    /** Everything the store holds, for the settings page's "clear" action. */
    fun clear() {
        directory.deleteRecursively()
    }
}
