@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spektralab.app.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spektralab.app.R
import com.spektralab.app.ui.components.ActionButton
import com.spektralab.app.ui.components.ChipPill
import com.spektralab.app.ui.stockStyle
import com.spektralab.app.ui.stockTitle
import com.spektralab.app.ui.theme.Tokens

/**
 * A2 — 胶片与相纸, the one sheet the prototype actually draws as a sheet.
 *
 * The layout is the frame's: a drag handle, the title and a close, a body card
 * that reads "this film → that paper", five tabs, a two-column grid of stocks at
 * 178×96, the paper list, and the amber 配光 action.
 *
 * **Tabs.** 胶片 and 印相 switch the panel. 片边 and 日期 are drawn and disabled:
 * the engine refuses both on this backend (`backend.unsupported_features`, the
 * plan's §4.5.1), and a tab that opens onto nothing is worse than one that says
 * why. 放大机 lists the print's illuminants, which the engine does carry.
 */
@Composable
fun FilmSheet(
    films: List<String>,
    papers: List<String>,
    illuminants: List<String>,
    filmStock: String?,
    paperStock: String?,
    illuminant: String?,
    onPickFilm: (String) -> Unit,
    onPickPaper: (String) -> Unit,
    onPickIlluminant: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(FilmTab.Film) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Tokens.Sheet,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Margin)) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(120.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Tokens.Ink.copy(alpha = 0.35f)),
                )
            }
            Row(
                Modifier.fillMaxWidth().height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("胶片与相纸", style = Tokens.Type.SheetTitle, color = Tokens.Ink)
                Spacer(Modifier.weight(1f))
                Icon(
                    painter = painterResource(R.drawable.ic_x),
                    contentDescription = "关闭",
                    tint = Tokens.InkTertiary,
                    modifier = Modifier.size(16.dp).clickable(onClick = onDismiss),
                )
            }

            BodyCard(filmStock = filmStock, paperStock = paperStock)

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilmTab.entries.forEach { entry ->
                    val enabled = entry.enabled
                    Box(
                        Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (tab == entry) Tokens.Ink.copy(alpha = 0.14f) else Tokens.Raise)
                            .border(
                                1.dp,
                                if (tab == entry) Tokens.Ink.copy(alpha = 0.2f) else Tokens.Ink.copy(alpha = 0.06f),
                                RoundedCornerShape(14.dp),
                            )
                            .clickable(enabled = enabled) { tab = entry }
                            .testTag("film-tab-${entry.name.lowercase()}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = entry.label,
                            style = Tokens.Type.Caption.copy(fontSize = 9.5.sp),
                            color = when {
                                !enabled -> Tokens.InkTertiary.copy(alpha = 0.45f)
                                tab == entry -> Tokens.Ink
                                else -> Tokens.InkSecondary
                            },
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (tab) {
                    FilmTab.Film -> {
                        StockGrid(films = films, selected = filmStock, onPick = onPickFilm)
                        Spacer(Modifier.height(16.dp))
                        PaperList(papers = papers, selected = paperStock, onPick = onPickPaper)
                    }
                    FilmTab.Paper -> PaperList(papers = papers, selected = paperStock, onPick = onPickPaper)
                    FilmTab.Aperture, FilmTab.Date -> RefusedTab(tab = tab)
                    FilmTab.Enlarger -> PaperList(
                        papers = illuminants,
                        selected = illuminant,
                        onPick = onPickIlluminant,
                        title = "放大机光源",
                        searchable = "光源",
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            ActionButton(
                label = "配光",
                onClick = onDismiss,
                height = 44.dp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).testTag("film-apply"),
            )
        }
    }
}

/** A2's five tabs, in the frame's order. */
private enum class FilmTab(val label: String, val enabled: Boolean = true) {
    Film("胶片"),
    Paper("印相"),
    Aperture("片边", enabled = false),
    Date("日期", enabled = false),
    Enlarger("放大机"),
}

/**
 * The "film → paper" card: what this frame will be developed with and printed on.
 *
 * Two swatches with an arrow between them, because the pair is the product's
 * central choice and the two halves are not interchangeable.
 */
@Composable
private fun BodyCard(filmStock: String?, paperStock: String?) {
    // `""` counts as absent: the engine reports an empty string until the pair
    // is resolved, and a blank id would print a blank name.
    val film = filmStock?.ifBlank { null } ?: "kodak_portra_400"
    val paper = paperStock?.ifBlank { null } ?: "kodak_supra_endura"
    val filmStyle = stockStyle(film)
    val paperStyle = stockStyle(paper)

    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Tokens.Ink.copy(alpha = 0.05f))
            .border(1.dp, Tokens.Hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(listOf(filmStyle.tint, filmStyle.tint.copy(alpha = 0.55f)))),
        )
        Spacer(Modifier.width(10.dp))
        // Both halves take a share of the row: with the two halves sized to
        // their text and two spacers eating the slack, the paper's name was
        // measured at zero width and simply did not appear.
        Column(Modifier.weight(1f)) {
            Text(filmStyle.title, style = Tokens.Type.Value.copy(fontSize = 11.sp), color = Tokens.Ink, maxLines = 1)
            Text(
                text = "ISO ${isoOf(film)} · 负片",
                style = Tokens.Type.Caption.copy(fontSize = 8.5.sp, fontFamily = Tokens.Mono),
                color = Tokens.InkTertiary,
                maxLines = 1,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_arrow_right),
            contentDescription = null,
            tint = Tokens.InkTertiary,
            modifier = Modifier.padding(horizontal = 10.dp).size(16.dp),
        )
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(paperStyle.tint),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(paperStyle.title, style = Tokens.Type.Value.copy(fontSize = 11.sp), color = Tokens.Ink, maxLines = 1)
            Text(
                text = paperKind(paper),
                style = Tokens.Type.Caption.copy(fontSize = 8.5.sp, fontFamily = Tokens.Mono),
                color = Tokens.InkTertiary,
                maxLines = 1,
            )
        }
    }
}

/** Two columns of 178×96 stock cards, the chosen one ringed in amber. */
@Composable
private fun StockGrid(films: List<String>, selected: String?, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        films.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { id ->
                    StockCard(
                        id = id,
                        selected = id == selected,
                        onClick = { onPick(id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StockCard(id: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val style = stockStyle(id)
    Box(
        modifier
            .height(96.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Tokens.Ink.copy(alpha = 0.09f) else Tokens.Ink.copy(alpha = 0.04f))
            .border(
                width = if (selected) 1.6.dp else 1.dp,
                color = if (selected) Tokens.Accent.copy(alpha = 0.9f) else Tokens.Ink.copy(alpha = 0.09f),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(10.dp)
            .testTag("stock-$id"),
    ) {
        Column {
            // The card's art: the stock's own colour wash, at A2's 178×44 band.
            // The label lives under it, so nothing is printed twice.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(style.tint.copy(alpha = 0.95f), style.tint.copy(alpha = 0.55f)),
                        ),
                    ),
            )
            Spacer(Modifier.height(8.dp))
            Text(style.title, style = Tokens.Type.Caption.copy(fontSize = 10.sp), color = Tokens.Ink, maxLines = 1)
            Text(
                text = "ISO ${isoOf(id)}",
                style = Tokens.Type.Caption.copy(fontSize = 8.sp, fontFamily = Tokens.Mono),
                color = Tokens.InkTertiary,
            )
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Tokens.Accent),
            )
        }
    }
}

/** The paper list: a card per stock, with its own colour chip. */
@Composable
private fun PaperList(
    papers: List<String>,
    selected: String?,
    onPick: (String) -> Unit,
    title: String = "相纸",
    searchable: String = "相纸",
) {
    Column {
        Text(title, style = Tokens.Type.Caption.copy(fontSize = 9.5.sp), color = Tokens.InkTertiary)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            papers.forEach { id ->
                val style = stockStyle(id)
                val chosen = id == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Tokens.Ink.copy(alpha = 0.04f))
                        .border(1.dp, Tokens.Ink.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .clickable { onPick(id) }
                        .padding(horizontal = 12.dp)
                        .testTag("paper-$id"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(style.tint),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(style.title, style = Tokens.Type.Caption.copy(fontSize = 10.sp), color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = "$searchable · ${if (id.contains("multigrade") || id.contains("ilford")) "黑白" else "彩色"}",
                            style = Tokens.Type.Caption.copy(fontSize = 8.sp, fontFamily = Tokens.Mono),
                            color = Tokens.InkTertiary,
                        )
                    }
                    if (chosen) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Tokens.Accent))
                    }
                }
            }
        }
    }
}

/** A tab whose backing feature this backend refuses. */
@Composable
private fun RefusedTab(tab: FilmTab) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = "${tab.label} 在本平台不可用",
            style = Tokens.Type.Caption.copy(fontSize = 10.sp),
            color = Tokens.InkSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "引擎的非 Apple 后端没有实现这一项（backend.unsupported_features）。" +
                "页签保留在原位，是对这台机器能做的事的说明，不是遗漏。",
            style = Tokens.Type.Caption,
            color = Tokens.InkTertiary,
        )
    }
}

/**
 * `kodak_portra_400` → `400`, or `—` when the number is not a speed.
 *
 * `kodak_2383` and `kodak_2393` are print-film catalogue numbers: they look
 * like speeds and are not, so the table names the values that are.
 */
fun isoOf(stockId: String): String {
    val digits = stockId.split('_').lastOrNull { token -> token.any { it.isDigit() } }.orEmpty()
    val number = digits.filter { it.isDigit() }
    return if (number in ISO_VALUES) number else "—"
}

/** The speeds a stock is actually sold at. */
private val ISO_VALUES = setOf("25", "50", "100", "125", "160", "200", "400", "500", "800", "1600", "3200")

/** The paper's display name, through the same table the roll tiles use. */
fun paperTitle(stockId: String): String = stockTitle(stockId)

/** `kodak_supra_endura` → `彩色相纸`; `ilford_multigrade_iv_rc` → `黑白相纸`. */
fun paperKind(stockId: String): String =
    if (stockId.contains("ilford") || stockId.contains("multigrade")) "黑白相纸" else "彩色相纸"
