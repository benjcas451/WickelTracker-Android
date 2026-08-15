package org.dwarftsch.wickel.data

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Portables JSON-Backup der lokalen Datenbank. Format identisch zur
 * Flutter-App (format=1, app=wickel), damit alte Backups weiterhin
 * wiederhergestellt werden können und umgekehrt.
 */
object LocalBackupService {

    private const val APP = "wickel"
    private const val FORMAT = 1

    /** Vorschlags-Dateiname für den Speichern-Dialog. */
    fun dateiname(): String =
        "wickel_backup_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))}.json"

    /** Serialisiert alle Zeilen als hübsch formatiertes Backup-JSON. */
    fun exportJson(rows: List<EntryRow>): String {
        val eintraege = rows.map { row ->
            linkedMapOf<String, Any?>(
                "id" to row.id,
                "type" to row.type,
                "time" to row.time,
                "stoffwindel" to row.stoffwindel,
            )
        }
        val payload = linkedMapOf<String, Any?>(
            "format" to FORMAT,
            "app" to APP,
            "exported_at" to LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            "entries" to eintraege,
        )
        return JSONObject(payload as Map<*, *>).toString(2)
    }

    /**
     * Prüft ein Backup und liefert die Zeilen passend zum Tabellenschema.
     * Wirft [IllegalArgumentException] mit sprechender Meldung bei Problemen.
     */
    fun parseUndValidiere(text: String): List<EntryRow> {
        val decoded = runCatching { JSONObject(text) }.getOrElse {
            throw IllegalArgumentException("Die Datei ist kein gültiges JSON.")
        }
        if (decoded.optInt("format", -1) != FORMAT || decoded.optString("app") != APP) {
            throw IllegalArgumentException(
                "Nicht unterstütztes Backup-Format (falsche App oder Version).",
            )
        }
        val rawEntries = decoded.optJSONArray("entries")
            ?: throw IllegalArgumentException("Eintragsliste fehlt im Backup.")

        val result = mutableListOf<EntryRow>()
        val ids = mutableSetOf<Long>()
        for (index in 0 until rawEntries.length()) {
            val raw = rawEntries.optJSONObject(index)
                ?: throw IllegalArgumentException("Ungültiger Eintrag im Backup.")

            val id = raw.optLong("id", -1)
            if (id <= 0 || !ids.add(id)) {
                throw IllegalArgumentException("Ungültige oder doppelte Eintrags-ID.")
            }
            val type = raw.optString("type", "")
            if (org.dwarftsch.wickel.WickelType.entries.none { it.apiValue == type.lowercase() }) {
                throw IllegalArgumentException("Eintrag $id hat einen ungültigen Typ.")
            }
            val time = raw.optString("time", "")
            if (time.isEmpty() ||
                runCatching { org.dwarftsch.wickel.parseIsoZeit(time) }.isFailure
            ) {
                throw IllegalArgumentException("Eintrag $id hat einen ungültigen Zeitpunkt.")
            }
            val stoffwindel = raw.optInt("stoffwindel", -1)
            if (stoffwindel != 0 && stoffwindel != 1) {
                throw IllegalArgumentException("Eintrag $id hat einen ungültigen Stoffwindel-Wert.")
            }
            result.add(EntryRow(id, type, time, stoffwindel))
        }
        return result
    }
}
