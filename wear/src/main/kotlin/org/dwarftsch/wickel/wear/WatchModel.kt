package org.dwarftsch.wickel.wear

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Das Dashboard, wie es die Handy-App liefert. */
data class Dashboard(
    val todayTotal: Int,
    val stoffwindelEnabled: Boolean,
    val lastType: String?,
    val lastTime: LocalDateTime?,
    val lastStoffwindel: Boolean,
)

/**
 * Zustand der Uhr-Oberfläche: lädt das Dashboard von der Handy-App und legt
 * Einträge über sie an. Der letzte Stand wird lokal gespiegelt, damit die App
 * auch ohne erreichbares Handy sofort etwas anzeigen kann.
 */
class WatchModel(context: Context) {

    private val phone = PhoneConnection(context)
    private val cache =
        context.applicationContext.getSharedPreferences("wear_cache", Context.MODE_PRIVATE)

    var laedt by mutableStateOf(false)
        private set

    /** Fehlermeldung, wenn das Handy nicht erreichbar war o. Ä. */
    var fehler by mutableStateOf<String?>(null)
        private set

    var dashboard by mutableStateOf<Dashboard?>(null)
        private set

    /** Umschaltfläche: nächster Eintrag ist eine Stoffwindel. */
    var stoffwindelActive by mutableStateOf(false)

    /** Kurzbestätigung nach dem Anlegen („Urin ✓“). */
    var bestaetigung by mutableStateOf<String?>(null)
        private set

    /** apiValue des Typs, der gerade angelegt wird (deaktiviert die Kacheln). */
    var legtAn by mutableStateOf<String?>(null)
        private set

    init {
        // Letzten bekannten Stand sofort zeigen; frische Daten kommen gleich.
        cache.getString(CACHE_KEY, null)?.let { gespeichert ->
            runCatching { JSONObject(gespeichert) }.getOrNull()?.let { json ->
                dashboard = alsDashboard(json)
            }
        }
    }

    fun aktualisieren() {
        if (laedt) return
        laedt = true
        fehler = null
        phone.request(
            action = "getDashboard",
            arguments = null,
            onSuccess = { daten ->
                uebernehmen(daten)
                laedt = false
            },
            onError = { meldung ->
                laedt = false
                // Cache-Stand stehen lassen; nur den Fehler einblenden.
                fehler = meldung
            },
        )
    }

    /** Legt einen Eintrag vom [typApiValue] mit „jetzt“ an. */
    fun anlegen(typApiValue: String, label: String) {
        if (legtAn != null) return
        legtAn = typApiValue
        fehler = null
        val stoffwindel = (dashboard?.stoffwindelEnabled == true) && stoffwindelActive
        phone.request(
            action = "createEntry",
            arguments = JSONObject().put("typ", typApiValue).put("stoffwindel", stoffwindel),
            onSuccess = { daten ->
                uebernehmen(daten)
                legtAn = null
                stoffwindelActive = false
                bestaetigung = label + if (stoffwindel) " · 🧷" else ""
            },
            onError = { meldung ->
                legtAn = null
                fehler = meldung
            },
        )
    }

    fun bestaetigungGesehen() {
        bestaetigung = null
    }

    fun schliessen() = Unit

    private fun uebernehmen(daten: JSONObject) {
        dashboard = alsDashboard(daten)
        cache.edit().putString(CACHE_KEY, daten.toString()).apply()
    }

    private fun alsDashboard(json: JSONObject) = Dashboard(
        todayTotal = json.optInt("today_total", 0),
        stoffwindelEnabled = json.optBoolean("stoffwindel_enabled", false),
        lastType = json.optString("last_type").takeIf { it.isNotEmpty() },
        lastTime = json.optString("last_time").takeIf { it.isNotEmpty() }?.let(::zeitAusText),
        lastStoffwindel = json.optBoolean("last_stoffwindel", false),
    )

    /** Die Handy-App schickt UTC-Zeitstempel mit "Z". */
    private fun zeitAusText(text: String): LocalDateTime? =
        runCatching {
            OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        }.recoverCatching {
            Instant.parse(text).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }.getOrNull()

    private companion object {
        const val CACHE_KEY = "last_dashboard"
    }
}
