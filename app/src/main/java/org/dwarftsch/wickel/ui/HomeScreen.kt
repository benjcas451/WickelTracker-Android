package org.dwarftsch.wickel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.dwarftsch.wickel.LastEntry
import org.dwarftsch.wickel.PeriodStats
import org.dwarftsch.wickel.R
import org.dwarftsch.wickel.WickelType
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onEinstellungen: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var zeigeUndoNachfrage by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.meldungen.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            MhKopf(
                titel = "Wickel-Tracker",
                symbole = {
                    MhSymbolButton(R.drawable.ic_mh_refresh, "Aktualisieren", viewModel::aktualisieren)
                    MhSymbolButton(R.drawable.ic_mh_sliders, "Einstellungen", onEinstellungen)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Mh.farben.bg,
    ) { innenAbstand ->
        Box(modifier = Modifier.padding(innenAbstand).fillMaxSize()) {
            when {
                state.laedt && state.stats == null && state.fehler == null -> MhLaden()

                state.fehler != null ->
                    FehlerAnsicht(
                        meldung = state.fehler.orEmpty(),
                        onErneut = viewModel::aktualisieren,
                    )

                else -> PullToRefreshBox(
                    isRefreshing = state.laedt,
                    onRefresh = viewModel::aktualisieren,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Inhalt(
                        state = state,
                        onStoffwindel = viewModel::setzeStoffwindelActive,
                        onAnlegen = viewModel::anlegen,
                        onLetztenRueckgaengig = { zeigeUndoNachfrage = true },
                    )
                }
            }
        }
    }

    if (zeigeUndoNachfrage) {
        LoeschDialog(
            titel = "Letzten Eintrag löschen",
            text = "Der zuletzt angelegte Eintrag wird entfernt.",
            onAbbrechen = { zeigeUndoNachfrage = false },
            onLoeschen = {
                zeigeUndoNachfrage = false
                viewModel.letztenRueckgaengig()
            },
        )
    }
}

// --- Inhalt ------------------------------------------------------------------

@Composable
private fun Inhalt(
    state: HomeUiState,
    onStoffwindel: (Boolean) -> Unit,
    onAnlegen: (WickelType) -> Unit,
    onLetztenRueckgaengig: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Mh.rand, end = Mh.rand, top = Mh.abstand1, bottom = Mh.abstand8),
    ) {
        // Offline-Hinweis über allem: der Nutzer soll sofort sehen, dass er
        // zwar weiterarbeiten kann, der Stand aber noch nicht beim Server ist.
        if (state.offlineGrund != null || state.ausstehend > 0) {
            item(key = "offline") {
                OfflineHinweis(grund = state.offlineGrund, ausstehend = state.ausstehend)
                Spacer(Modifier.height(Mh.abstand4))
            }
        }

        if (state.stoffwindelEnabled) {
            item(key = "stoffwindel") {
                MhSchalterZeile(
                    titel = "Stoffwindel",
                    an = state.stoffwindelActive,
                    onWechsel = onStoffwindel,
                    symbol = R.drawable.ic_mh_diaper,
                )
                Spacer(Modifier.height(Mh.abstand2))
            }
        }

        item(key = "eingabe") {
            Column(verticalArrangement = Arrangement.spacedBy(Mh.abstand2)) {
                WickelTypReihenfolge.forEach { type ->
                    WickelEintragZeile(type, onClick = { onAnlegen(type) })
                }
            }
            val last = state.stats?.last
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Mh.abstand2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (last?.isEmpty == true) "Kein Eintrag vorhanden" else "Letzten Eintrag zurücknehmen",
                    style = MhTypo.small,
                    color = Mh.farben.textSecondary,
                    modifier = Modifier.weight(1f).padding(start = 2.dp),
                )
                MhLoeschenButton(
                    "Rückgängig",
                    onLetztenRueckgaengig,
                    symbol = R.drawable.ic_mh_undo,
                    enabled = last?.isEmpty != true,
                )
            }
        }

        state.stats?.let { stats ->
            item(key = "zuletzt") {
                LetzterEintrag(last = stats.last, stoffwindelEnabled = state.stoffwindelEnabled)
            }
            item(key = "stats") {
                PeriodenBlock("Heute", stats.today, state.stoffwindelEnabled)
                PeriodenBlock("Letzte 7 Tage", stats.week, state.stoffwindelEnabled)
                PeriodenBlock("Letzte 21 Tage", stats.threeWeeks, state.stoffwindelEnabled)
                PeriodenBlock("Letzte 30 Tage", stats.month, state.stoffwindelEnabled)
            }
        }
    }
}

// --- Letzter Eintrag -------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LetzterEintrag(last: LastEntry, stoffwindelEnabled: Boolean) {
    val f = Mh.farben
    Column(Modifier.fillMaxWidth().padding(top = Mh.abstand6)) {
        MhTrennlinie()
        // Zeitangabe rechts; reicht der Platz nicht (große Schrift), rutscht
        // sie in die nächste Zeile.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = Mh.abstand4),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.padding(end = Mh.abstand3)) {
                Text("Letzter Eintrag", style = MhTypo.label, color = f.textSecondary)
                Spacer(Modifier.height(Mh.abstand1))
                val type = last.type
                if (type == null) {
                    Text("Noch kein Eintrag", style = MhTypo.body, color = f.textSecondary)
                } else {
                    // FlowRow: die Marke rutscht bei großer Schrift in die
                    // nächste Zeile, statt umzubrechen.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(Mh.abstand1),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            WickelTypPunkt(type)
                            Text(
                                type.label,
                                style = MhTypo.body.copy(fontWeight = FontWeight.Medium),
                                color = f.textStrong,
                            )
                        }
                        if (stoffwindelEnabled && last.stoffwindel) MhMarke("Stoffwindel")
                    }
                }
            }
            last.time?.let { zeit ->
                Text(
                    "${relativ(zeit)} · ${absolut(zeit)}",
                    style = MhTypo.small.copy(fontFeatureSettings = "tnum"),
                    color = f.textSecondary,
                    textAlign = TextAlign.End,
                )
            }
        }
        MhTrennlinie()
    }
}

// --- Statistik ---------------------------------------------------------------------

@Composable
private fun PeriodenBlock(titel: String, periode: PeriodStats, stoffwindelEnabled: Boolean) {
    MhGruppenLabel(
        titel,
        rechts = if (periode.total == 1) "1 Eintrag" else "${periode.total} Einträge",
        modifier = Modifier.padding(top = Mh.abstand2),
    )
    for (type in WickelTypReihenfolge) {
        ProzentZeile(
            punkt = { WickelTypPunkt(type) },
            label = type.label,
            pct = periode.pctOf(type),
            farbe = type.datenFarbe(),
        )
    }
    if (stoffwindelEnabled) {
        ProzentZeile(
            punkt = { Spacer(Modifier.width(10.dp)) },
            label = "Stoffwindel",
            pct = periode.stoffwindelPct,
            farbe = Mh.farben.textTertiary,
        )
    }
}

@Composable
private fun ProzentZeile(
    punkt: @Composable () -> Unit,
    label: String,
    pct: Int,
    farbe: Color,
) {
    val f = Mh.farben
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.weight(1.35f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            punkt()
            Text(label, style = MhTypo.small, color = f.text)
        }
        MhBalken(pct / 100f, Modifier.weight(1f), farbe = farbe)
        Text(
            "$pct%",
            modifier = Modifier.widthIn(min = 44.dp),
            textAlign = TextAlign.End,
            style = MhTypo.small.copy(fontFeatureSettings = "tnum"),
            color = f.textSecondary,
        )
    }
}

// --- Fehleransicht --------------------------------------------------------------

@Composable
private fun FehlerAnsicht(meldung: String, onErneut: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Mh.abstand8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MhSymbol(R.drawable.ic_mh_alert, null, groesse = 32.dp, farbe = Mh.farben.danger)
        Spacer(Modifier.height(Mh.abstand4))
        Text(meldung, style = MhTypo.body, color = Mh.farben.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Mh.abstand6))
        MhHauptButton("Erneut versuchen", onErneut, symbol = R.drawable.ic_mh_refresh)
    }
}

/**
 * Hinweis über dem Inhalt: Verbindung weg, App weiter benutzbar.
 *
 * [grund] null bedeutet „wieder online, aber es wartet noch etwas auf die
 * Übertragung“.
 */
@Composable
private fun OfflineHinweis(grund: String?, ausstehend: Int) {
    val titel = if (grund == null) "Übertragung läuft" else "Offline-Modus – $grund"
    val text = when (ausstehend) {
        0 -> "Angezeigt wird der zuletzt geladene Stand."
        1 -> "Ein Eintrag wartet auf die Übertragung und geht raus, sobald die Verbindung steht."
        else ->
            "$ausstehend Einträge warten auf die Übertragung und gehen raus, " +
                "sobald die Verbindung steht."
    }
    MhHinweis(
        text = text,
        titel = titel,
        art = MhHinweisArt.Warnung,
        symbol = if (grund == null) R.drawable.ic_mh_upload else R.drawable.ic_mh_cloud_off,
    )
}

// --- Datums-Helfer ---------------------------------------------------------------

/** „vor 5 Min.“, „vor 3 Std.“, „vor 2 Tagen“ – wie auf der Webseite. */
internal fun relativ(zeit: Instant): String {
    val sekunden = Duration.between(zeit, Instant.now()).seconds
    return when {
        sekunden < 60 -> "gerade eben"
        sekunden < 3600 -> "vor ${sekunden / 60} Min."
        sekunden < 86400 -> "vor ${sekunden / 3600} Std."
        else -> {
            val tage = sekunden / 86400
            if (tage == 1L) "vor 1 Tag" else "vor $tage Tagen"
        }
    }
}

/** Heute „08:24 Uhr“, sonst „21.11., 08:24“ – wie auf der Webseite. */
internal fun absolut(zeit: Instant): String {
    val lokal = zeit.atZone(ZoneId.systemDefault())
    return if (lokal.toLocalDate() == LocalDate.now()) {
        lokal.format(DateTimeFormatter.ofPattern("HH:mm")) + " Uhr"
    } else {
        lokal.format(DateTimeFormatter.ofPattern("dd.MM., HH:mm"))
    }
}
