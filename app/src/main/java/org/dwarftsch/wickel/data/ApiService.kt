package org.dwarftsch.wickel.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.dwarftsch.wickel.LastEntry
import org.dwarftsch.wickel.PeriodStats
import org.dwarftsch.wickel.WickelStats
import org.dwarftsch.wickel.WickelType
import org.dwarftsch.wickel.parseIsoZeit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Spricht die Wickel-Tracker-API an (`<baseUrl>api.php?action=...`).
 *
 * Authentifizierung:
 *  - mTLS-Client-Zertifikat über [certSource] (Transport-Ebene), und/oder
 *  - API-Key über den Header `X-API-Key` ([apiKey]).
 *
 * Die api.php verlangt den API-Key in jedem Fall – auch hinter mTLS – daher
 * wird [apiKey] zusätzlich zu [certSource] gesetzt. Endpunkte und
 * JSON-Felder identisch zur Flutter-App.
 */
class ApiService(
    /** Quelle für client.crt/client.key; null bei reiner API-Key-Auth. */
    private val certSource: CertSource? = null,
    /** Basis-URL inkl. abschließendem Slash, z. B. `https://host/wickel-tracker/`. */
    private val baseUrl: String,
    /** Wird als `X-API-Key`-Header mitgesendet, falls gesetzt. */
    private val apiKey: String? = null,
) : WickelService {

    private var client: OkHttpClient? = null

    private suspend fun httpClient(): OkHttpClient {
        client?.let { return it }
        val builder = OkHttpClient.Builder()
        val source = certSource
        if (source != null) {
            val (cert, key) = source.readCredentials()
            val (factory, trust) = ClientCertificates.socketFactoryMitTrust(cert, key)
            builder.sslSocketFactory(factory, trust)
        }
        return builder.build().also { client = it }
    }

    /**
     * `dispose()` kommt aus dem UI-Thread (Zurück aus den Einstellungen).
     * `evictAll()` schließt dabei offene TLS-Sockets und `shutdown()` wartet
     * auf den Dispatcher — beides blockierende Arbeit, die nicht auf den
     * Main-Thread gehört. Deshalb läuft das Aufräumen im Hintergrund; der
     * Client wird sofort losgelassen, damit niemand ihn weiterbenutzt.
     */
    override fun dispose() {
        val alt = client ?: return
        client = null
        aufraeumScope.launch {
            runCatching {
                alt.dispatcher.executorService.shutdown()
                alt.connectionPool.evictAll()
            }
        }
    }

    private fun apiUrl(action: String): HttpUrl {
        if (baseUrl.isBlank()) {
            throw ApiException(
                "Keine API-URL konfiguriert. Bitte in den Einstellungen die " +
                    "Basis-URL des Servers hinterlegen.",
            )
        }
        val root = "${baseUrl}api.php".toHttpUrlOrNull()
            ?: throw ApiException("Ungültige API-URL: $baseUrl")
        return root.newBuilder().addQueryParameter("action", action).build()
    }

    private suspend fun send(method: String, url: HttpUrl, body: JSONObject? = null): Any? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply {
                    if (!apiKey.isNullOrEmpty()) header("X-API-Key", apiKey)
                }
                .method(method, body?.let { anfrageKoerper(it) } ?: if (method == "POST") ByteArray(0).toRequestBody() else null)
                .build()

            httpClient().newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val ok = response.code in 200..299

                val decoded: Any? = if (text.isEmpty()) {
                    null
                } else {
                    runCatching { JSONObject(text) as Any }
                        .recoverCatching { JSONArray(text) as Any }
                        .getOrElse {
                            // Antwort ist kein JSON (z. B. HTML-Fehlerseite).
                            if (ok) return@use null
                            throw ApiException(
                                "Unerwartete Antwort (kein JSON): ${snippet(text)}",
                                statusCode = response.code,
                            )
                        }
                }

                if (ok) return@use decoded

                val meldung = (decoded as? JSONObject)?.optString("error")?.takeIf { it.isNotEmpty() }
                    ?: text.ifEmpty { "Anfrage fehlgeschlagen" }.let(::snippet)
                throw ApiException(meldung, statusCode = response.code)
            }
        }

    private fun anfrageKoerper(json: JSONObject): RequestBody =
        json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    override suspend fun getStats(): WickelStats {
        val data = send("GET", apiUrl("stats")) as? JSONObject
            ?: throw ApiException("Unerwartete Antwort der API.")
        fun periode(key: String): PeriodStats {
            val json = data.optJSONObject(key) ?: return PeriodStats.LEER
            return PeriodStats(
                total = json.optInt("total", 0),
                urinPct = json.optInt("urin", 0),
                stuhlgangPct = json.optInt("stuhlgang", 0),
                beidesPct = json.optInt("beides", 0),
                stoffwindelPct = json.optInt("stoffwindelPct", 0),
            )
        }
        val lastJson = data.optJSONObject("last")
        val last = if (lastJson?.isNull("type") == false) {
            LastEntry(
                type = WickelType.fromApi(lastJson.optString("type")),
                time = lastJson.optString("time").takeIf { it.isNotEmpty() }
                    ?.let { runCatching { parseIsoZeit(it) }.getOrNull() },
                stoffwindel = lastJson.optBoolean("stoffwindel", false),
            )
        } else {
            LastEntry()
        }
        return WickelStats(
            today = periode("today"),
            week = periode("week"),
            threeWeeks = periode("threeWeeks"),
            month = periode("month"),
            last = last,
        )
    }

    /**
     * [time] wird ignoriert: `POST ?action=wickeln` kennt keinen Zeit-Parameter,
     * die Spalte `time` hat serverseitig `DEFAULT CURRENT_TIMESTAMP`. Ein von
     * der Uhr offline erfasster Eintrag landet in den Server-Modi also mit dem
     * Zeitpunkt der Übertragung, nicht dem der Erfassung.
     */
    override suspend fun addEntry(type: WickelType, stoffwindel: Boolean, time: Instant?) {
        send(
            "POST", apiUrl("wickeln"),
            JSONObject().put("typ", type.apiValue).put("stoffwindel", stoffwindel),
        )
    }

    override suspend fun undoLast(): Boolean {
        return try {
            val data = send("POST", apiUrl("undo_last"))
            (data as? JSONObject)?.optBoolean("ok") == true
        } catch (e: ApiException) {
            if (e.statusCode == 404) false else throw e // kein Eintrag vorhanden
        }
    }

    private companion object {
        /** Hintergrund-Scope zum Freigeben der HTTP-Ressourcen. */
        val aufraeumScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Kürzt eine (Fehler-)Antwort für die Anzeige. */
        fun snippet(s: String): String {
            val clean = s.replace(Regex("\\s+"), " ").trim()
            return if (clean.length > 200) clean.take(200) + "…" else clean
        }
    }
}
