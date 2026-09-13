package org.dwarftsch.wickel.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dwarftsch.wickel.LastEntry
import org.dwarftsch.wickel.PeriodStats
import org.dwarftsch.wickel.WickelStats
import org.dwarftsch.wickel.WickelType
import org.dwarftsch.wickel.parseIsoZeit
import org.json.JSONArray
import org.json.JSONObject

/** Ein offline erfasster Eintrag, der noch zum Server muss. */
data class Warteeintrag(
    val type: WickelType,
    val stoffwindel: Boolean,
    /**
     * Zeitpunkt der Erfassung, nicht des Hochladens – sonst bekäme der Eintrag
     * beim Nachholen die falsche Uhrzeit.
     */
    val time: Instant,
)

/** Der Offline-Zustand, den die Oberfläche anzeigt. */
data class OfflineZustand(
    /** Grund der letzten gescheiterten Verbindung; null heisst „online“. */
    val grund: String? = null,
    /** Anzahl der Einträge, die noch auf Übertragung warten. */
    val ausstehend: Int = 0,
)

/**
 * Legt Warteschlange und Lesestand je Zugang im app-privaten Verzeichnis ab.
 *
 * Der Schlüssel ist Modus plus Basis-URL: Wer zwischen zwei Servern wechselt,
 * bekommt nicht den Stand des anderen zu sehen und lädt auch keine
 * Warteschlange dorthin hoch, wo sie nicht hingehört.
 */
class OfflineSpeicher(context: Context, zugang: String) {

    private val schluessel = zugang.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    private val ordner = File(context.applicationContext.filesDir, "offline").apply { mkdirs() }

    private val warteschlangeDatei get() = File(ordner, "warteschlange_$schluessel.json")
    private val statsDatei get() = File(ordner, "stats_$schluessel.json")

    fun ladeWarteschlange(): List<Warteeintrag> {
        val json = lies(warteschlangeDatei) ?: return emptyList()
        val liste = json.optJSONArray("eintraege") ?: return emptyList()
        return (0 until liste.length()).mapNotNull { i ->
            val o = liste.optJSONObject(i) ?: return@mapNotNull null
            // fromApi fällt auf URIN zurück; ein fehlender Typ wäre also
            // stillschweigend falsch – deshalb hier ausdrücklich prüfen.
            if (!o.has("type")) return@mapNotNull null
            Warteeintrag(
                type = WickelType.fromApi(o.optString("type")),
                stoffwindel = o.optBoolean("stoffwindel"),
                time = parseIsoZeit(o.optString("time")),
            )
        }
    }

    fun speichere(eintraege: List<Warteeintrag>) {
        val liste = JSONArray()
        for (e in eintraege) {
            liste.put(
                JSONObject()
                    .put("type", e.type.apiValue)
                    .put("stoffwindel", e.stoffwindel)
                    .put("time", e.time.toString()),
            )
        }
        schreibe(warteschlangeDatei, JSONObject().put("eintraege", liste))
    }

    fun ladeStats(): WickelStats? {
        val json = lies(statsDatei) ?: return null
        // fromApi fällt auf URIN zurück – ohne die has()-Prüfung zeigte ein
        // leerer Stand fälschlich „zuletzt: Urin“.
        val letzterTyp = if (json.has("last_type")) {
            WickelType.fromApi(json.optString("last_type"))
        } else {
            null
        }
        val letzteZeit = json.optString("last_time").takeIf { it.isNotEmpty() }
        return WickelStats(
            today = periode(json.optJSONObject("today")),
            week = periode(json.optJSONObject("week")),
            threeWeeks = periode(json.optJSONObject("three_weeks")),
            month = periode(json.optJSONObject("month")),
            last = LastEntry(
                type = letzterTyp,
                time = letzteZeit?.let { parseIsoZeit(it) },
                stoffwindel = json.optBoolean("last_stoffwindel"),
            ),
        )
    }

    fun speichere(stats: WickelStats) {
        schreibe(
            statsDatei,
            JSONObject()
                .put("today", periodeAlsJson(stats.today))
                .put("week", periodeAlsJson(stats.week))
                .put("three_weeks", periodeAlsJson(stats.threeWeeks))
                .put("month", periodeAlsJson(stats.month))
                .apply {
                    stats.last.type?.let { put("last_type", it.apiValue) }
                    stats.last.time?.let { put("last_time", it.toString()) }
                    put("last_stoffwindel", stats.last.stoffwindel)
                },
        )
    }

    private fun periode(json: JSONObject?): PeriodStats {
        if (json == null) return PeriodStats.LEER
        return PeriodStats(
            total = json.optInt("total"),
            urinPct = json.optInt("urin_pct"),
            stuhlgangPct = json.optInt("stuhlgang_pct"),
            beidesPct = json.optInt("beides_pct"),
            stoffwindelPct = json.optInt("stoffwindel_pct"),
        )
    }

    private fun periodeAlsJson(werte: PeriodStats): JSONObject = JSONObject()
        .put("total", werte.total)
        .put("urin_pct", werte.urinPct)
        .put("stuhlgang_pct", werte.stuhlgangPct)
        .put("beides_pct", werte.beidesPct)
        .put("stoffwindel_pct", werte.stoffwindelPct)

    private fun lies(datei: File): JSONObject? =
        runCatching { JSONObject(datei.readText()) }.getOrNull()

    /**
     * Erst in eine Nebendatei, dann umbenennen: ein Absturz mitten im
     * Schreiben hinterlässt sonst eine halbe Datei, und die Warteschlange wäre
     * verloren.
     */
    private fun schreibe(datei: File, json: JSONObject) {
        runCatching {
            val temp = File(datei.parentFile, "${datei.name}.tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(datei)) {
                datei.writeText(json.toString())
                temp.delete()
            }
        }
    }
}

/**
 * Legt sich über die Server-Quelle und hält die App bei einem
 * Verbindungsabbruch benutzbar.
 *
 * Lesen: Bei jedem Netzwerkfehler wird der zuletzt erfolgreiche Stand
 * gezeigt — dabei ist gleich, ob die Anfrage ankam, denn ein Lesevorgang
 * verändert nichts.
 *
 * Schreiben: In die Warteschlange darf ein Eintrag **nur**, wenn er den Server
 * nachweislich nie erreicht hat ([Netzfehler.NIE_GESENDET]). Bei einer
 * Zeitüberschreitung oder einem Abbruch mitten in der Übertragung könnte der
 * Server ihn bereits angelegt haben; ein zweiter Versuch legte dann einen
 * zweiten an.
 *
 * Die Uhr benutzt diesen Umweg bewusst nicht: sie führt eine eigene Outbox und
 * würde denselben Eintrag sonst zweimal einreihen.
 */
class OfflineService(
    private val innen: WickelService,
    private val speicher: OfflineSpeicher,
) : WickelService {

    private val sperre = Mutex()
    private val warteschlange = speicher.ladeWarteschlange().toMutableList()

    private val zustandFlow = MutableStateFlow(OfflineZustand(ausstehend = warteschlange.size))

    /** Der Zustand für die Oberfläche. */
    val zustand: StateFlow<OfflineZustand> = zustandFlow

    override fun dispose() = innen.dispose()

    override suspend fun getStats(): WickelStats = try {
        val vomServer = innen.getStats()
        speicher.speichere(vomServer)
        melde(null)
        sperre.withLock { anwenden(vomServer, warteschlange) }
    } catch (fehler: Throwable) {
        val stand = speicher.ladeStats()
        if (Netzfehler.aus(fehler) == null || stand == null) throw fehler
        melde(fehler.meldung())
        sperre.withLock { anwenden(stand, warteschlange) }
    }

    override suspend fun addEntry(type: WickelType, stoffwindel: Boolean, time: Instant?) {
        val zeit = time ?: Instant.now()
        // Reihenfolge wahren: Steht schon etwas an, gehört auch das Neue
        // hinten dran, statt es am Stau vorbeizuschicken.
        if (!istLeer()) {
            reiheEin(Warteeintrag(type, stoffwindel, zeit))
            return
        }
        try {
            innen.addEntry(type, stoffwindel, time)
            melde(null)
        } catch (fehler: Throwable) {
            if (Netzfehler.aus(fehler) != Netzfehler.NIE_GESENDET) throw fehler
            melde(fehler.meldung())
            reiheEin(Warteeintrag(type, stoffwindel, zeit))
        }
    }

    override suspend fun undoLast(): Boolean {
        // Wartet noch etwas, ist das der zuletzt erfasste Eintrag – den nimmt
        // die App direkt zurück, ohne den Server zu behelligen.
        val zurueckgenommen = sperre.withLock {
            if (warteschlange.isEmpty()) {
                false
            } else {
                warteschlange.removeAt(warteschlange.lastIndex)
                true
            }
        }
        if (zurueckgenommen) {
            sichern()
            return true
        }
        // Sonst muss der Server ran. Offline lässt sich das **nicht**
        // vormerken: Die API kennt für undoLast keine ID, beim Nachholen
        // träfe es womöglich einen Eintrag, den jemand anders inzwischen
        // angelegt hat.
        return innen.undoLast()
    }

    /**
     * Arbeitet die Warteschlange von vorn ab.
     *
     * Bricht beim ersten Verbindungsfehler ab — der Rest bleibt in der
     * Reihenfolge stehen. Weist der Server einen Eintrag inhaltlich zurück,
     * fliegt er raus und wird gemeldet; sonst blockierte er die Warteschlange
     * für immer.
     *
     * Liefert die Meldungen zu verworfenen Einträgen.
     */
    suspend fun nachholen(): List<String> {
        val verworfen = mutableListOf<String>()
        while (true) {
            val naechster = sperre.withLock { warteschlange.firstOrNull() } ?: break
            try {
                innen.addEntry(naechster.type, naechster.stoffwindel, naechster.time)
                entferneErsten()
            } catch (fehler: Throwable) {
                if (Netzfehler.aus(fehler) != null) {
                    melde(fehler.meldung())
                    return verworfen
                }
                entferneErsten()
                verworfen.add(fehler.meldung())
            }
        }
        melde(null)
        return verworfen
    }

    // ── Innere Hilfen ───────────────────────────────────────────────────────

    private suspend fun istLeer(): Boolean = sperre.withLock { warteschlange.isEmpty() }

    private suspend fun reiheEin(eintrag: Warteeintrag) {
        sperre.withLock { warteschlange.add(eintrag) }
        sichern()
    }

    private suspend fun entferneErsten() {
        sperre.withLock { if (warteschlange.isNotEmpty()) warteschlange.removeAt(0) }
        sichern()
    }

    private suspend fun sichern() {
        val stand = sperre.withLock { warteschlange.toList() }
        speicher.speichere(stand)
        zustandFlow.value = zustandFlow.value.copy(ausstehend = stand.size)
    }

    private fun melde(grund: String?) {
        if (zustandFlow.value.grund != grund) {
            zustandFlow.value = zustandFlow.value.copy(grund = grund)
        }
    }

    /**
     * Rechnet die wartenden Einträge in die Statistik ein.
     *
     * Die Prozentanteile bleiben, wie der Server sie gemeldet hat: Sie aus den
     * wartenden Einträgen neu zu berechnen ginge nur mit den Rohdaten, die die
     * API nicht liefert. Gesamtzahlen und der letzte Eintrag stimmen dagegen —
     * und genau die stehen in der App im Vordergrund.
     */
    private fun anwenden(stats: WickelStats, wartend: List<Warteeintrag>): WickelStats {
        if (wartend.isEmpty()) return stats
        val jetzt = Instant.now()
        val heute = jetzt.atZone(ZoneId.systemDefault()).toLocalDate()
        var werte = stats
        for (eintrag in wartend) {
            if (eintrag.time.atZone(ZoneId.systemDefault()).toLocalDate() == heute) {
                werte = werte.copy(today = werte.today.copy(total = werte.today.total + 1))
            }
            if (eintrag.time > jetzt.minus(Duration.ofDays(7))) {
                werte = werte.copy(week = werte.week.copy(total = werte.week.total + 1))
            }
            if (eintrag.time > jetzt.minus(Duration.ofDays(21))) {
                werte = werte.copy(
                    threeWeeks = werte.threeWeeks.copy(total = werte.threeWeeks.total + 1),
                )
            }
            if (eintrag.time > jetzt.minus(Duration.ofDays(30))) {
                werte = werte.copy(month = werte.month.copy(total = werte.month.total + 1))
            }
        }
        // Der jüngste wartende Eintrag ist der letzte – sofern er nicht älter
        // ist als der, den der Server kennt.
        val neuester = wartend.maxByOrNull { it.time }
        val bisher = werte.last.time
        if (neuester != null && (bisher == null || neuester.time > bisher)) {
            werte = werte.copy(
                last = LastEntry(neuester.type, neuester.time, neuester.stoffwindel),
            )
        }
        return werte
    }
}

/** Lesbare Meldung einer Exception (ApiException liefert den Statuscode mit). */
fun Throwable.meldung(): String = when (this) {
    is ApiException -> toString()
    else -> message?.takeIf { it.isNotBlank() } ?: toString()
}

/**
 * Meldet, sobald wieder ein Netzwerk da ist — damit die Warteschlange nicht
 * erst beim nächsten Antippen abgearbeitet wird.
 */
class Verbindungswache(context: Context) {

    private val wiederVerbundenFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Feuert bei jedem Wechsel von „kein Netz“ zu „Netz da“. */
    val wiederVerbunden: SharedFlow<Unit> = wiederVerbundenFlow

    private val manager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var warOffline = false

    private val rueckruf = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (warOffline) wiederVerbundenFlow.tryEmit(Unit)
            warOffline = false
        }

        override fun onLost(network: Network) {
            warOffline = true
        }
    }

    fun starten() {
        runCatching {
            manager?.registerNetworkCallback(NetworkRequest.Builder().build(), rueckruf)
        }
    }

    fun beenden() {
        runCatching { manager?.unregisterNetworkCallback(rueckruf) }
    }
}
