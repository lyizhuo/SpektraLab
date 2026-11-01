package com.spektralab.app.ui

import androidx.compose.ui.graphics.Color
import com.spektralab.app.rolls.stockLabel

/**
 * How a film stock is written and tinted on screen.
 *
 * [stockLabel] is deliberately loud -- `PORTRA 400`, all caps -- because it is
 * what the negative's edge prints (C3's badge, A1's frame edge). C1's roll tile
 * shows the same stock as a product name, `Portra 160`, which is a different
 * job and a different string; both derive from the one id so the two surfaces
 * cannot disagree about which film is loaded.
 *
 * The tint is the wash behind the roll's label band. The prototype shows the
 * manufacturers' packaging, which this product does not license, so the band is
 * the stock's own colour instead -- the hue the emulsion is known by, which is
 * how a photographer recognises a roll on a shelf at a glance.
 */
data class StockStyle(
    /** `kodak_portra_160` -> `Portra 160`. */
    val title: String,
    /** The label band's wash. */
    val tint: Color,
    /** Ink for the label band: dark on light stocks, warm white on dark. */
    val ink: Color,
)

private val Table: Map<String, Pair<Color, Color>> = mapOf(
    // Kodak's portrait family: the violet/rose the Portra boxes carry.
    "kodak_portra_160" to (Color(0xFF6C5AB5) to Color.White),
    "kodak_portra_400" to (Color(0xFFC89A63) to Color.White),
    "kodak_portra_800" to (Color(0xFF9A6B4E) to Color.White),
    "kodak_portra_800_push1" to (Color(0xFF9A6B4E) to Color.White),
    "kodak_portra_800_push2" to (Color(0xFF9A6B4E) to Color.White),
    // Consumer colour: the yellow and red the Gold/Ektar boxes use.
    "kodak_gold_200" to (Color(0xFFD2A93F) to Color(0xFF24180B)),
    "kodak_ultramax_400" to (Color(0xFFC9913A) to Color(0xFF24180B)),
    "kodak_ektar_100" to (Color(0xFFB8402F) to Color.White),
    "kodak_ektachrome_100" to (Color(0xFF7A2E2E) to Color.White),
    "kodak_kodachrome_64" to (Color(0xFF9E3B2E) to Color.White),
    "kodak_verita_200d" to (Color(0xFF6B8F5E) to Color.White),
    // Fuji's own packaging: X-Tra is a white box with black type.
    "fujifilm_xtra_400" to (Color(0xFFEDEAE2) to Color(0xFF161310)),
    "fujifilm_c200" to (Color(0xFF8FB46B) to Color(0xFF161310)),
    "fujifilm_pro_400h" to (Color(0xFF7FA36E) to Color.White),
    "fujifilm_velvia_100" to (Color(0xFF5E7F52) to Color.White),
    "fujifilm_provia_100f" to (Color(0xFF4F6B46) to Color.White),
    "fujifilm_crystal_archive_typeii" to (Color(0xFF6F7E8C) to Color.White),
    // Motion picture: the stock's own blue-grey.
    "kodak_vision3_500t" to (Color(0xFF3E5C72) to Color.White),
    "kodak_vision3_250d" to (Color(0xFF4A6A5E) to Color.White),
    "kodak_vision3_200t" to (Color(0xFF42566B) to Color.White),
    "kodak_vision3_50d" to (Color(0xFF55707E) to Color.White),
    // Black and white: the two-tone box.
    "kodak_tri_x_400" to (Color(0xFF2A2724) to Color(0xFFF5F1EA)),
    "kodak_tmax_100" to (Color(0xFF3A3733) to Color(0xFFF5F1EA)),
    "ilford_hp5_plus_400" to (Color(0xFF1F1E1C) to Color(0xFFF5F1EA)),
    "fujifilm_neopan_acros_100_ii" to (Color(0xFF33312D) to Color(0xFFF5F1EA)),
)

private val Fallback = Color(0xFF3A3A3A) to Color.White

/** The stock's display name, tint and ink. Unknown ids still get a label. */
fun stockStyle(stockId: String): StockStyle {
    val (tint, ink) = Table[stockId] ?: Fallback
    // A blank id is not an unknown stock, it is an absent one: the engine
    // reports `paper_stock: ""` before a pair is resolved, and `stockTitle("")`
    // would return an empty label that renders as a blank line.
    return StockStyle(title = stockTitle(stockId).ifBlank { "未选" }, tint = tint, ink = ink)
}

/**
 * `kodak_portra_160` -> `Portra 160`, `kodak_vision3_500t` -> `Vision3 500T`.
 *
 * Title case with the trailing letter of an `500T` kept: the id's last token is
 * either a speed (`400`) or a speed and a suffix (`500t`), and only the suffix
 * is upper case in the product's own spelling.
 */
fun stockTitle(stockId: String): String {
    val words = stockLabel(stockId).lowercase().split(' ')
    return words.map { word ->
        when {
            word.isEmpty() -> word
            word.all { it.isDigit() } || word.all { it.isDigit() || it == 't' || it == 'd' } && word.any { it.isDigit() } ->
                // `500t` is a tungsten stock and prints as `500T`; `400` stays
                // as it is.
                word.filter { it.isDigit() } + word.filter { !it.isDigit() }.uppercase()
            else -> word.replaceFirstChar { it.uppercase() }
        }
    }.joinToString(" ")
}

/**
 * The paper a roll is printed on, as the card's second line spells it:
 * `Portra 400 · Supra Endura`.
 */
fun stockPairLabel(filmId: String, paperId: String): String {
    val film = stockLabel(filmId).lowercase().split(' ').joinToString(" ") { token ->
        token.replaceFirstChar { it.uppercase() }
    }
    val paper = stockLabel(paperId).lowercase().split(' ').joinToString(" ") { token ->
        token.replaceFirstChar { it.uppercase() }
    }
    return if (paper.isBlank()) film else "$film · $paper"
}
