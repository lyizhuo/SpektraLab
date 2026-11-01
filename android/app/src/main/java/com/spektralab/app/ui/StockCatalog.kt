package com.spektralab.app.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * The stock names the engine can actually be asked for.
 *
 * `film_stock` / `print_stock` / `enlarger_illuminant` are `str` fields, so
 * the schema cannot tell the UI what the legal values are — the engine checks
 * them against the resources it loaded and fails `set_params` otherwise
 * (`engine/src/core/params.cpp`'s `validate_delta`). The lists therefore come
 * from the same resource directory the engine was pointed at
 * (`filesDir/engine`, unpacked by `EngineAssets`):
 *
 *  * film stocks   — one JSON profile per stock under `profiles/`;
 *  * print stocks  — `print_luts.json`'s keys (the index `spk_print_lut_table`
 *    is documented against, `spk_engine.h:263-266`);
 *  * illuminants   — `neutral_print_filters.json[print_stock]`'s keys, the
 *    enlarger channels `digest` looks up (`engine/src/core/params.cpp:581`).
 *
 * Nothing is hard-coded: a stock the build does not carry never appears.
 */
/**
 * Where a parameter's choices come from.
 *
 * It lived in `ui/Catalog.kt`, which the redesign deleted; `Stocks` is the only
 * thing that answers it now, so it belongs beside it.
 */
enum class ChoiceSource { Static, FilmStocks, PrintStocks, Illuminants }

data class Stocks(
    val films: List<String> = emptyList(),
    val prints: List<String> = emptyList(),
    val illuminants: List<String> = emptyList(),
    /**
     * What each film says about itself.
     *
     * A profile is not just a name: `info` carries `type` (negative or
     * positive), `support` (film or paper), `stage` (filming or printing) and
     * `target_print` — the paper the stock was measured against. Guessing any of
     * that from the file name is how the app came to offer papers as films and
     * to print every film on whatever paper happened to be selected.
     */
    val filmInfo: Map<String, FilmInfo> = emptyMap(),
) {
    fun choicesFor(source: ChoiceSource): List<String> = when (source) {
        ChoiceSource.Static -> emptyList()
        ChoiceSource.FilmStocks -> films
        ChoiceSource.PrintStocks -> prints
        ChoiceSource.Illuminants -> illuminants
    }

    /** The paper this film was measured against, when it names one. */
    fun targetPrintFor(film: String): String? = filmInfo[film]?.targetPrint?.takeIf { it.isNotBlank() }

    /** `Kodak Kodachrome 64` for the wire id, or the id when the profile is unreadable. */
    fun displayNameOf(film: String): String = filmInfo[film]?.name?.takeIf { it.isNotBlank() } ?: film
}

/** The parts of a profile's `info` block this app acts on. */
data class FilmInfo(
    val id: String,
    val name: String,
    /** `negative` or `positive`; the engine branches on this at `params.cpp:552`. */
    val type: String,
    val support: String,
    val stage: String,
    /** The paper the stock was measured against; blank for the positives. */
    val targetPrint: String,
) {
    val isPositive: Boolean get() = type == "positive"
    val isFilm: Boolean get() = support == "film" && stage == "filming"
}

object StockCatalog {

    /** The unpacked resources directory (`EngineAssets.ensure`'s target). */
    fun resourcesDir(context: Context): File = File(context.filesDir, "engine")

    suspend fun load(context: Context): Stocks = withContext(Dispatchers.IO) {
        val resources = resourcesDir(context)
        val info = profiles(resources)
        Stocks(
            // A film is `support: film` at `stage: filming`. Everything else in
            // `profiles/` is a paper or a printing stock, and offering those as
            // films is what produced renders the model cannot mean.
            films = info.values.filter { it.isFilm }.map { it.id }.sorted(),
            prints = printStocks(resources),
            illuminants = illuminants(resources),
            filmInfo = info,
        )
    }

    /** Every profile's `info` block, keyed by the id the wire takes. */
    private fun profiles(resources: File): Map<String, FilmInfo> {
        val dir = resources.resolve("profiles")
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }.orEmpty()
        val out = linkedMapOf<String, FilmInfo>()
        for (file in files) {
            val id = file.name.removeSuffix(".json")
            val json = readJson(file) ?: continue
            val info = json.optJSONObject("info") ?: continue
            out[id] = FilmInfo(
                id = id,
                name = info.optString("name"),
                type = info.optString("type"),
                support = info.optString("support"),
                stage = info.optString("stage"),
                targetPrint = info.optString("target_print"),
            )
        }
        return out
    }

    private fun printStocks(resources: File): List<String> =
        keysOf(resources.resolve("print_luts.json"))

    /** The union of every print stock's illuminant channels, as `TH-KG3`. */
    private fun illuminants(resources: File): List<String> {
        val root = readJson(resources.resolve("neutral_print_filters.json")) ?: return emptyList()
        val names = sortedSetOf<String>()
        root.keys().forEach { print ->
            root.optJSONObject(print)?.keys()?.forEach { names.add(it) }
        }
        return names.toList()
    }

    private fun keysOf(file: File): List<String> {
        val json = readJson(file) ?: return emptyList()
        return json.keys().asSequence().sorted().toList()
    }

    private fun readJson(file: File): JSONObject? {
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }
}
