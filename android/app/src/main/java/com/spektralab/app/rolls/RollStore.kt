package com.spektralab.app.rolls

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Rolls on disk: one JSON file per roll under `filesDir/rolls/`.
 *
 * Same shape as [com.spektralab.app.edits.EditStore] on purpose — a `.partial`
 * file the reader never sees, then a rename — because a roll that is half
 * written when the process dies would either lose its frames or claim frames it
 * does not have, and both are worse than not saving at all.
 *
 * The filename is the roll id, and the id is inside the file too, so a file
 * renamed on disk is rejected on load rather than applied to the wrong roll.
 * Listing sorts by the id, which is time-prefixed (see `MainActivity`'s id
 * source) so "newest first" needs no stat on every file.
 */
class RollStore(context: Context) {

    private val directory = File(context.filesDir, "rolls")

    /** Every roll, newest first. Unreadable files are skipped, not thrown. */
    fun all(): List<Roll> {
        val files = directory.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files
            .sortedByDescending { it.name }
            .mapNotNull { read(it) }
    }

    /** The roll a new capture goes into: newest and not full. */
    fun current(): Roll? = all().firstOrNull { !it.isFull }

    fun load(id: String): Roll? = read(File(directory, "$id.json"))

    fun save(roll: Roll) {
        directory.mkdirs()
        val json = JSONObject().apply {
            put("id", roll.id)
            put("film_stock", roll.filmStockId)
            put("paper_stock", roll.paperStockId)
            put("capacity", roll.capacity)
            put("created_at", roll.createdAtMillis)
            put("frames", JSONArray(roll.frameIds))
        }
        val target = File(directory, "${roll.id}.json")
        val partial = File(directory, "${roll.id}.json.partial")
        partial.writeText(json.toString())
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
    }

    fun delete(id: String) {
        File(directory, "$id.json").delete()
    }

    /**
     * Append to the current roll, starting a new one when there is none or it
     * filled up. Returns the roll the frame landed in.
     *
     * `C3` calls this after a capture; the frame id is the capture's content
     * URI, which is what the strip and the editor already key on.
     */
    fun appendToCurrent(
        frameId: String,
        filmStockId: String,
        paperStockId: String,
        capacity: Int = Roll.CAPACITY_135,
        idFor: (Long) -> String = { "roll-$it" },
    ): Roll {
        val existing = current()
        val target = existing?.takeIf { !it.isFull && it.filmStockId == filmStockId }
            ?: Roll.newRoll(
                id = idFor(System.currentTimeMillis()),
                filmStockId = filmStockId,
                paperStockId = paperStockId,
                capacity = capacity,
            )
        val appended = target.append(frameId) ?: target
        save(appended)
        return appended
    }

    private fun read(file: File): Roll? = runCatching {
        val json = JSONObject(file.readText())
        val id = json.getString("id")
        if (file.name != "$id.json") return null
        val frames = json.optJSONArray("frames") ?: JSONArray()
        Roll(
            id = id,
            filmStockId = json.getString("film_stock"),
            paperStockId = json.getString("paper_stock"),
            capacity = json.optInt("capacity", Roll.CAPACITY_135),
            createdAtMillis = json.optLong("created_at", 0L),
            frameIds = (0 until frames.length()).map { frames.getString(it) },
        )
    }.getOrNull()
}
