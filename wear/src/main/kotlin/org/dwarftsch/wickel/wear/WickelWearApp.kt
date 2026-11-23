package org.dwarftsch.wickel.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import kotlinx.coroutines.delay
import org.dwarftsch.wickel.wear.ui.Mh
import org.dwarftsch.wickel.wear.ui.MhWearTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// --- Minze & Honig 2.0 (Rollen dunkel, Figtree; siehe ui/MhTheme.kt) -------
// Farbe ist Signal, keine Fläche: die Kacheln tragen die neutrale Fläche,
// der Typ steht als Punkt in seiner Datenfarbe davor – Zuordnung wie auf
// Handy und Webseite: Urin = data2 (Honig), Stuhlgang = data3 (Flieder),
// Beides = halb Honig, halb Minze.

/** Die drei Typen samt Datenfarbe(n) für den Punkt. */
private data class TypKachel(
    val apiValue: String,
    val label: String,
    val punkt: List<Color>,
)

private val Typen = listOf(
    TypKachel("urin", "Urin", listOf(Mh.farben.data2)),
    TypKachel("stuhlgang", "Stuhlgang", listOf(Mh.farben.data3)),
    TypKachel("beides", "Beides", listOf(Mh.farben.data2, Mh.farben.data1)),
)

/** Punkt (10 dp) in der Datenfarbe; zwei Farben = je eine Hälfte. */
@Composable
private fun TypPunkt(farben: List<Color>) {
    Row(Modifier.size(10.dp).clip(CircleShape)) {
        farben.forEach { farbe ->
            Box(Modifier.weight(1f).fillMaxHeight().background(farbe))
        }
    }
}

private fun typLabel(apiValue: String?): String = when (apiValue) {
    "urin" -> "Urin"
    "stuhlgang" -> "Stuhlgang"
    "beides" -> "Beides"
    else -> "?"
}

private val UhrzeitFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun tagesLabel(zeit: LocalDateTime): String {
    val heute = LocalDate.now()
    return when (heute.toEpochDay() - zeit.toLocalDate().toEpochDay()) {
        0L -> "Heute"
        1L -> "Gestern"
        else -> zeit.format(DateTimeFormatter.ofPattern("dd.MM."))
    }
}

// --- App ----------------------------------------------------------------------

@Composable
fun WickelWearApp(model: WatchModel) {
    MhWearTheme {
        val listState = rememberScalingLazyListState()

        // Bestätigung nach dem Anlegen kurz einblenden.
        val bestaetigung = model.bestaetigung
        LaunchedEffect(bestaetigung) {
            if (bestaetigung != null) {
                delay(1600)
                model.bestaetigungGesehen()
            }
        }

        Scaffold(
            timeText = { TimeText() },
            vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        ) {
            when {
                bestaetigung != null -> BestaetigungsAnsicht(bestaetigung)
                model.dashboard == null && model.laedt -> LadeAnsicht()
                model.dashboard == null -> FehlerAnsicht(
                    meldung = model.fehler ?: "Noch keine Daten.",
                    onErneut = model::aktualisieren,
                )
                else -> Inhalt(model, listState)
            }
        }
    }
}

@Composable
private fun Inhalt(
    model: WatchModel,
    listState: androidx.wear.compose.foundation.lazy.ScalingLazyListState,
) {
    val dashboard = model.dashboard ?: return
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Kopf: letzter Eintrag + Heute-Zähler.
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(top = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (dashboard.lastType != null) {
                    val suffix = if (dashboard.stoffwindelEnabled && dashboard.lastStoffwindel) " · Stoffwindel" else ""
                    Text(
                        "Zuletzt: ${typLabel(dashboard.lastType)}$suffix",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Mh.farben.textStrong,
                        textAlign = TextAlign.Center,
                    )
                    dashboard.lastTime?.let { zeit ->
                        Text(
                            "${tagesLabel(zeit)} · ${zeit.format(UhrzeitFormat)}",
                            fontSize = 11.sp,
                            color = Mh.farben.textSecondary,
                        )
                    }
                } else {
                    Text(
                        "Noch kein Eintrag",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Mh.farben.textStrong,
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Heute ", fontSize = 11.sp, color = Mh.farben.textSecondary)
                    // Große Zahlen dünn (400) in Textfarbe.
                    Text(
                        "${dashboard.todayTotal}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Normal,
                        color = Mh.farben.textStrong,
                    )
                }
            }
        }

        // Nicht-blockierender Hinweis, wenn das Handy gerade nicht antwortet.
        model.fehler?.let { meldung ->
            item {
                Text(
                    meldung,
                    fontSize = 11.sp,
                    color = Mh.farben.danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(top = 4.dp),
                )
            }
        }

        // Stoffwindel-Umschalter (nur wenn die Funktion am Telefon aktiviert ist).
        if (dashboard.stoffwindelEnabled) {
            item {
                ToggleChip(
                    checked = model.stoffwindelActive,
                    onCheckedChange = { model.stoffwindelActive = it },
                    label = { Text("Stoffwindel", fontSize = 13.sp) },
                    toggleControl = {
                        androidx.wear.compose.material.Icon(
                            imageVector = ToggleChipDefaults.switchIcon(model.stoffwindelActive),
                            contentDescription = null,
                        )
                    },
                    colors = ToggleChipDefaults.toggleChipColors(
                        checkedStartBackgroundColor = Mh.farben.selectedBg,
                        checkedEndBackgroundColor = Mh.farben.selectedBg,
                        checkedToggleControlColor = Mh.farben.primary,
                        checkedContentColor = Mh.farben.onSelected,
                        uncheckedStartBackgroundColor = Mh.farben.surface,
                        uncheckedEndBackgroundColor = Mh.farben.surface,
                        uncheckedToggleControlColor = Mh.farben.textTertiary,
                        uncheckedContentColor = Mh.farben.text,
                    ),
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(top = 8.dp),
                )
            }
        }

        // Die drei Typ-Kacheln.
        item {
            Text(
                "Anlegen",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Mh.farben.textSecondary,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
        }
        Typen.forEach { typ ->
            item {
                Chip(
                    onClick = { model.anlegen(typ.apiValue, typ.label) },
                    enabled = model.legtAn == null,
                    label = {
                        Text(typ.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    },
                    icon = {
                        if (model.legtAn == typ.apiValue) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                indicatorColor = Mh.farben.primary,
                            )
                        } else {
                            TypPunkt(typ.punkt)
                        }
                    },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = Mh.farben.surface,
                        contentColor = Mh.farben.textStrong,
                    ),
                    modifier = Modifier.fillMaxWidth(0.9f),
                )
            }
        }

        item {
            CompactChip(
                onClick = model::aktualisieren,
                label = { Text("Aktualisieren", fontSize = 12.sp) },
                colors = ChipDefaults.secondaryChipColors(
                    backgroundColor = Mh.farben.surface,
                    contentColor = Mh.farben.text,
                ),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// --- Zustände -------------------------------------------------------------------

@Composable
private fun LadeAnsicht() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(indicatorColor = Mh.farben.primary)
    }
}

@Composable
private fun BestaetigungsAnsicht(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_mh_check),
                contentDescription = null,
                tint = Mh.farben.primary,
                modifier = Modifier.size(40.dp),
            )
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Mh.farben.textStrong,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text("gespeichert", fontSize = 12.sp, color = Mh.farben.textSecondary)
        }
    }
}

@Composable
private fun FehlerAnsicht(meldung: String, onErneut: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            meldung,
            fontSize = 13.sp,
            color = Mh.farben.text,
            textAlign = TextAlign.Center,
        )
        CompactChip(
            onClick = onErneut,
            label = { Text("Erneut versuchen", fontSize = 12.sp) },
            colors = ChipDefaults.primaryChipColors(
                backgroundColor = Mh.farben.primary,
                contentColor = Mh.farben.onPrimary,
            ),
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
