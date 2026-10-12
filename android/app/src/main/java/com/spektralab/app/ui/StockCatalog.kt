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
data class Stocks(
    val films: List<String> = emptyList(),
    val prints: List<String> = emptyList(),
    val illuminants: List<String> = emptyList(),
) {
    fun choicesFor(source: ChoiceSource): List<String> = when (source) {
        ChoiceSource.Static -> emptyList()
        ChoiceSource.FilmStocks -> films
        ChoiceSource.PrintStocks -> prints
        ChoiceSource.Illuminants -> illuminants
    }
}

object StockCatalog {

    /** The unpacked resources directory (`EngineAssets.ensure`'s target). */
    fun resourcesDir(context: Context): File = File(context.filesDir, "engine")

    suspend fun load(context: Context): Stocks = withContext(Dispatchers.IO) {
        val resources = resourcesDir(context)
        Stocks(
            films = filmStocks(resources),
            prints = printStocks(resources),
            illuminants = illuminants(resources),
        )
    }

    private fun filmStocks(resources: File): List<String> =
        resources.resolve("profiles").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?.sorted()
            ?: emptyList()

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
