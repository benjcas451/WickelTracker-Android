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

/** Erstellt die aktuell konfigurierte Datenquelle. */
fun createConfiguredWickelService(context: Context, settings: AppSettings, certSource: CertSource): WickelService =
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
        DataSourceMode.DEMO -> DemoService(context)
    }
