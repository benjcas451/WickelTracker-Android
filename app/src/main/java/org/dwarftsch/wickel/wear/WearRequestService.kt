package org.dwarftsch.wickel.wear

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.dwarftsch.wickel.data.WickelService
import org.dwarftsch.wickel.WickelType
import org.dwarftsch.wickel.data.AppSettings
import org.dwarftsch.wickel.data.CertSource
import org.dwarftsch.wickel.data.createConfiguredWickelService
import org.dwarftsch.wickel.parseIsoZeit
import org.json.JSONObject
import java.time.format.DateTimeFormatter

/**
 * Nimmt RPC-Anfragen der Wear-OS-App entgegen (`MessageClient.sendRequest`)
 * und führt sie direkt gegen die konfigurierte Datenquelle aus. Die Uhr hat
 * bewusst keine eigene Datenquelle — Lesen und Schreiben laufen immer über
 * die Handy-App (wie bei der Apple-Watch-Variante).
 *
 * Protokoll (JSON, UTF-8, gleiche Hülle wie bei Stillzeit/Medikamente):
 *   Anfrage: {"action": "...", "arguments": { ... }}
 *   Antwort: {"ok": true, "data": { ... }} bzw. {"ok": false, "error": "..."}
 *
 * Aktionen:
 *   getDashboard -> {today_total, stoffwindel_enabled, last_type?, last_time?, last_stoffwindel}
 *   createEntry {typ, stoffwindel?, time?} -> Dashboard nach dem Anlegen
 *   undoLast -> Dashboard nach dem Löschen (+ removed: true/false)
 */
class WearRequestService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequest(nodeId: String, path: String, data: ByteArray): Task<ByteArray>? {
        if (path != REQUEST_PATH) return null

        val antwort = TaskCompletionSource<ByteArray>()
        val anfrage = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
        val action = anfrage?.optString("action").orEmpty()
        if (action.isEmpty()) {
            antwort.setResult(fehler("Ungültige Anfrage der Uhr."))
            return antwort.task
        }
        val argumente = anfrage?.optJSONObject("arguments") ?: JSONObject()

        scope.launch {
            val ergebnis = runCatching { fuehreAus(action, argumente) }
            antwort.setResult(
                ergebnis.fold(
                    onSuccess = ::erfolg,
                    onFailure = { fehler(it.message ?: "Unbekannter Fehler.") },
                ),
            )
        }
        return antwort.task
    }

    private suspend fun fuehreAus(action: String, argumente: JSONObject): JSONObject {
        val settings = AppSettings(this)
        return when (action) {
            "getDashboard" -> mitService(settings) { service -> dashboard(service, settings) }

            "createEntry" -> mitService(settings) { service ->
                service.addEntry(
                    type = WickelType.fromApi(argumente.optString("typ")),
                    stoffwindel = argumente.optBoolean("stoffwindel", false),
                    time = argumente.optString("time").takeIf { it.isNotEmpty() }
                        ?.let { runCatching { parseIsoZeit(it) }.getOrNull() },
                )
                WatchChangeBus.melden()
                dashboard(service, settings)
            }

            "undoLast" -> mitService(settings) { service ->
                val entfernt = service.undoLast()
                WatchChangeBus.melden()
                dashboard(service, settings).put("removed", entfernt)
            }

            else -> throw IllegalArgumentException("Unbekannte Watch-Anfrage: $action")
        }
    }

    private suspend fun <T> mitService(settings: AppSettings, aktion: suspend (WickelService) -> T): T {
        val certSource = CertSource(this, settings)
        val service = createConfiguredWickelService(this, settings, certSource)
        return try {
            aktion(service)
        } finally {
            service.dispose()
        }
    }

    private suspend fun dashboard(service: WickelService, settings: AppSettings): JSONObject {
        val stats = service.getStats()
        return JSONObject().apply {
            put("today_total", stats.today.total)
            put("stoffwindel_enabled", settings.stoffwindelEnabled)
            val last = stats.last
            if (!last.isEmpty) {
                put("last_type", last.type?.apiValue)
                // java.time auf der Uhr erwartet eine explizite Zeitzone; UTC mit "Z".
                last.time?.let { put("last_time", DateTimeFormatter.ISO_INSTANT.format(it)) }
                put("last_stoffwindel", last.stoffwindel)
            }
        }
    }

    private fun erfolg(daten: JSONObject): ByteArray =
        JSONObject()
            .put("ok", true)
            .put("data", daten)
            .toString()
            .toByteArray(Charsets.UTF_8)

    private fun fehler(meldung: String): ByteArray =
        JSONObject()
            .put("ok", false)
            .put("error", meldung)
            .toString()
            .toByteArray(Charsets.UTF_8)

    private companion object {
        /** Muss zu `PhoneConnection.REQUEST_PATH` im :wear-Modul passen. */
        const val REQUEST_PATH = "/wickel/request"
    }
}
