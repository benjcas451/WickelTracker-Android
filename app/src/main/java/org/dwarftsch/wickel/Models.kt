package org.dwarftsch.wickel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.JoinFull
import androidx.compose.material.icons.filled.Icecream
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Art des Wickel-Eintrags. [apiValue] ist exakt der String, den die API
 * erwartet bzw. liefert (urin, stuhlgang, beides). Die Farbzuordnung
 * (Honig/Grau/Flieder) liegt im Theme (ui/Theme.kt).
 */
enum class WickelType(val label: String, val apiValue: String) {
    URIN("Urin", "urin"),
    STUHLGANG("Stuhlgang", "stuhlgang"),
    BEIDES("Beides", "beides");

    val icon: ImageVector
        get() = when (this) {
            URIN -> Icons.Filled.WaterDrop
            STUHLGANG -> Icons.Filled.Icecream
            BEIDES -> Icons.Filled.JoinFull
        }

    companion object {
        fun fromApi(value: String?): WickelType =
            entries.firstOrNull { it.apiValue == value?.lowercase() } ?: URIN
    }
}

/**
 * Statistik eines Zeitraums: Gesamtzahl + Prozentanteil je Typ
 * (so liefert es `GET api.php?action=stats`).
 */
data class PeriodStats(
    val total: Int = 0,
    val urinPct: Int = 0,
    val stuhlgangPct: Int = 0,
    val beidesPct: Int = 0,
    val stoffwindelPct: Int = 0,
) {
    fun pctOf(type: WickelType): Int = when (type) {
        WickelType.URIN -> urinPct
        WickelType.STUHLGANG -> stuhlgangPct
        WickelType.BEIDES -> beidesPct
    }

    companion object {
        val LEER = PeriodStats()
    }
}

/** Letzter Eintrag (Typ + Zeitpunkt) oder „keiner“. */
data class LastEntry(
    val type: WickelType? = null,
    val time: Instant? = null,
    val stoffwindel: Boolean = false,
) {
    val isEmpty: Boolean get() = type == null
}

/**
 * Vollständige Statistik-Antwort (`action=stats`): Zeiträume heute / Woche /
 * 3 Wochen / Monat plus letzter Eintrag.
 */
data class WickelStats(
    val today: PeriodStats = PeriodStats.LEER,
    val week: PeriodStats = PeriodStats.LEER,
    val threeWeeks: PeriodStats = PeriodStats.LEER,
    val month: PeriodStats = PeriodStats.LEER,
    val last: LastEntry = LastEntry(),
)

/**
 * Liest einen ISO-8601-Zeitstempel tolerant: mit Offset (`+02:00`), mit `Z`
 * (auch mit Dart-Mikrosekunden) oder ganz ohne Zeitzone (dann lokale Zeit,
 * wie in Dart).
 */
fun parseIsoZeit(text: String): Instant {
    runCatching { return Instant.parse(text) }
    runCatching { return OffsetDateTime.parse(text).toInstant() }
    return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant()
}
