package org.dwarftsch.wickel.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.dwarftsch.wickel.LastEntry
import org.dwarftsch.wickel.PeriodStats
import org.dwarftsch.wickel.WickelStats
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
            TopAppBar(
                title = { Text("🧷 Wickel-Tracker") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = viewModel::aktualisieren) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Aktualisieren")
                    }
                    IconButton(onClick = onEinstellungen) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(snackbar) { daten -> Snackbar(daten) }
        },
    ) { innenAbstand ->
        Box(modifier = Modifier.padding(innenAbstand).fillMaxSize()) {
            when {
                state.laedt && state.stats == null && state.fehler == null ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

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
            titel = "Letzten Eintrag löschen?",
            text = "Der zuletzt angelegte Wickel-Eintrag wird entfernt.",
            onAbbrechen = { zeigeUndoNachfrage = false },
            onLoeschen = {
                zeigeUndoNachfrage = false
                viewModel.letztenRueckgaengig()
            },
        )
    }
}

// --- Inhalt ------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Inhalt(
    state: HomeUiState,
    onStoffwindel: (Boolean) -> Unit,
    onAnlegen: (WickelType) -> Unit,
    onLetztenRueckgaengig: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        state.stats?.let { stats ->
            item(key = "zuletzt") {
                LetzterEintragKarte(
                    last = stats.last,
                    stoffwindelEnabled = state.stoffwindelEnabled,
                )
            }
        }

        item(key = "eingabe") {
            Spacer(Modifier.height(16.dp))
            Text("Neuer Eintrag", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            if (state.stoffwindelEnabled) {
                StoffwindelSchalter(
                    aktiv = state.stoffwindelActive,
                    onWechsel = onStoffwindel,
                )
                Spacer(Modifier.height(8.dp))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WickelType.entries.forEach { type ->
                    // Kategorie-Buttons nach dem Chip-Muster: Pastellfläche (300)
                    // mit 900er-Text, Radius 12, Höhe 44 (Touch-Minimum).
                    Button(
                        onClick = { onAnlegen(type) },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = type.buttonFlaeche(),
                            contentColor = type.buttonInhalt(),
                        ),
                    ) {
                        Icon(type.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(type.label)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onLetztenRueckgaengig,
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Letzten rückgängig")
            }
        }

        state.stats?.let { stats ->
            item(key = "stats") {
                Spacer(Modifier.height(24.dp))
                Text("Statistik", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                PeriodenKarte("Heute", stats.today, state.stoffwindelEnabled, hervorgehoben = true)
                Spacer(Modifier.height(8.dp))
                PeriodenKarte("Letzte 7 Tage", stats.week, state.stoffwindelEnabled)
                Spacer(Modifier.height(8.dp))
                PeriodenKarte("Letzte 3 Wochen", stats.threeWeeks, state.stoffwindelEnabled)
                Spacer(Modifier.height(8.dp))
                PeriodenKarte("Letzte 30 Tage", stats.month, state.stoffwindelEnabled)
            }
        }
    }
}

// --- Stoffwindel-Schalter --------------------------------------------------------

@Composable
private fun StoffwindelSchalter(aktiv: Boolean, onWechsel: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.LocalLaundryService,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (aktiv) stoffwindelAkzent() else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text("Stoffwindel", modifier = Modifier.weight(1f))
            Switch(
                checked = aktiv,
                onCheckedChange = onWechsel,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Mh.gruen300,
                    checkedThumbColor = Mh.gruen900,
                ),
            )
        }
    }
}

// --- Letzter Eintrag -------------------------------------------------------------

@Composable
private fun LetzterEintragKarte(last: LastEntry, stoffwindelEnabled: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val type = last.type
            // Avatar nach dem Hinweis-Muster: zarte 100er-Fläche,
            // Icon in der text-tauglichen 700er-Stufe (Dark: 300er).
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        type?.avatarFlaeche() ?: MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    type?.icon ?: Icons.Filled.History,
                    contentDescription = null,
                    tint = type?.akzent() ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                if (type == null) {
                    Text("Noch kein Eintrag", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Lege unten den ersten Wickel-Vorgang an.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Zuletzt: ${type.label}", style = MaterialTheme.typography.bodyLarge)
                    last.time?.let { zeit ->
                        val suffix = if (stoffwindelEnabled && last.stoffwindel) " · 🧷 Stoffwindel" else ""
                        Text(
                            "${tagesLabel(zeit)} um ${hhmm(zeit)} · ${relativ(zeit)}$suffix",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// --- Statistik ---------------------------------------------------------------------

@Composable
private fun PeriodenKarte(
    titel: String,
    periode: PeriodStats,
    stoffwindelEnabled: Boolean,
    hervorgehoben: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            // "Heute" hervorgehoben als zarte Minze-100-Fläche (Dark-Äquivalent).
            containerColor = if (hervorgehoben) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (hervorgehoben) 0.dp else 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(titel, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${periode.total} gesamt",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Spacer(Modifier.height(12.dp))
            for (type in WickelType.entries) {
                ProzentZeile(
                    icon = { Icon(type.icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = type.akzent()) },
                    label = type.label,
                    pct = periode.pctOf(type),
                    balkenFarbe = type.akzent(),
                    balkenGrund = type.avatarFlaeche(),
                )
                Spacer(Modifier.height(8.dp))
            }
            if (stoffwindelEnabled) {
                ProzentZeile(
                    icon = {
                        Icon(
                            Icons.Filled.LocalLaundryService,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = stoffwindelAkzent(),
                        )
                    },
                    label = "Stoffwindel",
                    pct = periode.stoffwindelPct,
                    balkenFarbe = stoffwindelAkzent(),
                    balkenGrund = stoffwindelFlaeche(),
                )
            }
        }
    }
}

@Composable
private fun ProzentZeile(
    icon: @Composable () -> Unit,
    label: String,
    pct: Int,
    balkenFarbe: androidx.compose.ui.graphics.Color,
    balkenGrund: androidx.compose.ui.graphics.Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.width(92.dp), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(
            progress = { pct / 100f },
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(6.dp)),
            color = balkenFarbe,
            trackColor = balkenGrund,
            drawStopIndicator = {},
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$pct%",
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// --- Fehleransicht --------------------------------------------------------------

@Composable
private fun FehlerAnsicht(meldung: String, onErneut: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(meldung, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onErneut, shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Erneut versuchen")
        }
    }
}

// --- Datums-Helfer ---------------------------------------------------------------

internal fun hhmm(zeit: Instant): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(zeit)

internal fun tagesLabel(zeit: Instant): String {
    val heute = LocalDate.now()
    val tag = zeit.atZone(ZoneId.systemDefault()).toLocalDate()
    return when (heute.toEpochDay() - tag.toEpochDay()) {
        0L -> "Heute"
        1L -> "Gestern"
        else -> tag.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }
}

/** „vor X min/h/d“ – wie in der Flutter-App. */
internal fun relativ(zeit: Instant): String {
    val differenz = Duration.between(zeit, Instant.now())
    return when {
        differenz.toMinutes() < 1 -> "gerade eben"
        differenz.toMinutes() < 60 -> "vor ${differenz.toMinutes()} min"
        differenz.toHours() < 24 -> "vor ${differenz.toHours()} h"
        else -> "vor ${differenz.toDays()} d"
    }
}
