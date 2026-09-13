package org.dwarftsch.wickel.data

import android.content.Context
import org.dwarftsch.wickel.WickelStats
import org.dwarftsch.wickel.WickelType
import java.time.Instant

/** Fehler einer API-Anfrage (Statuscode + Meldung). */
class ApiException(message: String, val statusCode: Int? = null) : Exception(message) {
    override fun toString(): String =
        if (statusCode != null) "Fehler $statusCode: $message" else message.orEmpty()
}

/**
 * Gemeinsame Schnittstelle für Wickel-Quellen: die Server-API ([ApiService],
 * mTLS und/oder API-Key) oder die lokale SQLite-Datenbank ([DemoService]).
 */
interface WickelService {
    /** Vollständige Statistik (heute / Woche / 3 Wochen / Monat + letzter Eintrag). */
    suspend fun getStats(): WickelStats

    /**
     * Neuen Wickel-Eintrag anlegen.
     *
     * [time] setzt den Zeitpunkt abweichend von „jetzt“ — gedacht für
     * Einträge, die eine Uhr offline erfasst und erst später überträgt.
     * Die Server-API kennt dafür keinen Parameter und stempelt selbst,
     * dort wirkt [time] daher nicht (siehe [ApiService.addEntry]).
     */
    suspend fun addEntry(type: WickelType, stoffwindel: Boolean = false, time: Instant? = null)

    /**
     * Letzten Eintrag rückgängig machen.
     * Liefert true, wenn etwas entfernt wurde, false wenn es keinen gab.
     */
    suspend fun undoLast(): Boolean

    /** Gibt Ressourcen frei (HTTP-Client bzw. Datenbank-Handle). */
    fun dispose()
}

/**
 * Erstellt die aktuell konfigurierte Datenquelle.
 *
 * [offlineFaehig] legt die Warteschlange darüber, die bei einem
 * Verbindungsabbruch einspringt. Die Oberfläche will das; die Uhr-Strecke
 * bewusst nicht — die Uhr führt eine eigene Outbox.
 */
fun createConfiguredWickelService(
    context: Context,
    settings: AppSettings,
    certSource: CertSource,
    offlineFaehig: Boolean = false,
): WickelService {
    val dienst = createServerOderDemoService(context, settings, certSource)
    val zugang = aktuellerZugang(settings)
    if (!offlineFaehig || zugang == null) return dienst
    return OfflineService(dienst, OfflineSpeicher(context, zugang))
}

/**
 * Kennung des aktuellen Zugangs (Modus + Basis-URL); null im Demo-Modus, der
 * ohnehin lokal arbeitet und keine Warteschlange braucht.
 */
private fun aktuellerZugang(settings: AppSettings): String? = when (settings.mode) {
    DataSourceMode.API -> "api|${settings.apiBaseUrl}"
    DataSourceMode.API_KEY -> "apiKey|${settings.apiKeyBaseUrl}"
    DataSourceMode.CLOUDFLARE -> "cloudflare|${settings.cloudflareBaseUrl}"
    DataSourceMode.DEMO -> null
}

private fun createServerOderDemoService(
    context: Context,
    settings: AppSettings,
    certSource: CertSource,
): WickelService =
    when (settings.mode) {
        // Die api.php verlangt den API-Key in jedem Fall – auch hinter mTLS.
        DataSourceMode.API -> ApiService(
            certSource = certSource,
            baseUrl = settings.apiBaseUrl,
            apiKey = settings.apiKey,
        )
        DataSourceMode.API_KEY -> ApiService(
            baseUrl = settings.apiKeyBaseUrl,
            apiKey = settings.apiKey,
        )
        // Cloudflare Access sichert den Zugang am Rand; der API-Key geht wie
        // in den anderen Server-Modi mit — die api.php verlangt ihn auch dort.
        DataSourceMode.CLOUDFLARE -> ApiService(
            baseUrl = settings.cloudflareBaseUrl,
            apiKey = settings.apiKey,
            cfToken = settings.cfServiceToken(),
        )
        DataSourceMode.DEMO -> DemoService(context)
    }
