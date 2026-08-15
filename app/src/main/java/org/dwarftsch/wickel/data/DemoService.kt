package org.dwarftsch.wickel.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dwarftsch.wickel.LastEntry
import org.dwarftsch.wickel.PeriodStats
import org.dwarftsch.wickel.WickelStats
import org.dwarftsch.wickel.WickelType
import org.dwarftsch.wickel.parseIsoZeit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Eine Roh-Zeile der Tabelle `entries` (für Backup-Export/-Restore). */
data class EntryRow(
    val id: Long,
    val type: String,
    val time: String,
    val stoffwindel: Int,
)

/**
 * Lokaler Modus: speichert Einträge in derselben SQLite-Datenbank, die schon
 * die Flutter-App (sqflite) verwendet hat – gleicher Dateiname, gleiches
 * Schema, gleiche Version. Bestehende Daten werden dadurch beim Umstieg auf
 * die native App nahtlos übernommen.
 */
class DemoService(context: Context) : WickelService {

    private val helper = Helper(context.applicationContext)

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE entries(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  type TEXT NOT NULL,
                  time TEXT NOT NULL,
                  stoffwindel INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }

        // Identisch zur sqflite-Migration der Flutter-App (Version 1 -> 2).
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE entries ADD COLUMN stoffwindel INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    override suspend fun getStats(): WickelStats = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        // Nach Zeit sortiert, nicht nach id: Einträge von der Uhr können
        // nachträglich mit älterem Zeitpunkt eintreffen.
        val last = db.query(
            "entries", arrayOf("type", "time", "stoffwindel"),
            null, null, null, null, "time DESC, id DESC", "1",
        ).use { cursor ->
            if (cursor.moveToNext()) {
                LastEntry(
                    type = WickelType.fromApi(cursor.getString(0)),
                    time = parseIsoZeit(cursor.getString(1)),
                    stoffwindel = cursor.getInt(2) == 1,
                )
            } else {
                LastEntry()
            }
        }
        WickelStats(
            today = periode(db, tagesbeginn()),
            week = periode(db, tagesbeginn(tageZurueck = 7)),
            threeWeeks = periode(db, tagesbeginn(tageZurueck = 21)),
            month = periode(db, tagesbeginn(tageZurueck = 30)),
            last = last,
        )
    }

    /** Statistik für alle Einträge ab [seit] (Prozentwerte wie die Server-API). */
    private fun periode(db: SQLiteDatabase, seit: Instant): PeriodStats =
        db.query(
            "entries", arrayOf("type", "stoffwindel"),
            "time >= ?", arrayOf(zuDb(seit)),
            null, null, null,
        ).use { cursor ->
            var total = 0
            var urin = 0
            var stuhl = 0
            var beides = 0
            var sw = 0
            while (cursor.moveToNext()) {
                total++
                if (cursor.getInt(1) == 1) sw++
                when (WickelType.fromApi(cursor.getString(0))) {
                    WickelType.URIN -> urin++
                    WickelType.STUHLGANG -> stuhl++
                    WickelType.BEIDES -> beides++
                }
            }
            fun pct(c: Int) = if (total > 0) Math.round(c * 100f / total) else 0
            PeriodStats(
                total = total,
                urinPct = pct(urin),
                stuhlgangPct = pct(stuhl),
                beidesPct = pct(beides),
                stoffwindelPct = pct(sw),
            )
        }

    override suspend fun addEntry(type: WickelType, stoffwindel: Boolean, time: Instant?) =
        withContext(Dispatchers.IO) {
            val werte = ContentValues().apply {
                put("type", type.apiValue)
                put("time", zuDb(time ?: Instant.now()))
                put("stoffwindel", if (stoffwindel) 1 else 0)
            }
            helper.writableDatabase.insertOrThrow("entries", null, werte)
            Unit
        }

    override suspend fun undoLast(): Boolean = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        // Gleiche Sortierung wie in getStats, damit „rückgängig“ genau den
        // Eintrag entfernt, der als letzter angezeigt wird.
        val letzteId = db.query(
            "entries", arrayOf("id"), null, null, null, null, "time DESC, id DESC", "1",
        ).use { cursor -> if (cursor.moveToNext()) cursor.getLong(0) else null }
            ?: return@withContext false
        db.delete("entries", "id = ?", arrayOf(letzteId.toString())) > 0
    }

    override fun dispose() {
        // Der SQLiteOpenHelper cached die Verbindung prozessweit; bewusst
        // offen lassen (UI, Backup und Wear-Service teilen sich die DB).
    }

    /** Alle Roh-Zeilen der lokalen Tabelle (für den Backup-Export). */
    suspend fun exportRows(): List<EntryRow> = withContext(Dispatchers.IO) {
        helper.readableDatabase.query("entries", null, null, null, null, null, "id").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        EntryRow(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                            time = cursor.getString(cursor.getColumnIndexOrThrow("time")),
                            stoffwindel = cursor.getInt(cursor.getColumnIndexOrThrow("stoffwindel")),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Ersetzt den gesamten lokalen Bestand durch [rows] (Backup-Restore).
     * Löschen und Einfügen laufen in einer Transaktion, damit bei einem Fehler
     * der bisherige Stand erhalten bleibt.
     */
    suspend fun replaceAll(rows: List<EntryRow>) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("entries", null, null)
            for (row in rows) {
                val werte = ContentValues().apply {
                    put("id", row.id)
                    put("type", row.type)
                    put("time", row.time)
                    put("stoffwindel", row.stoffwindel)
                }
                db.insertOrThrow("entries", null, werte)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        /** Muss zum sqflite-Bestand der Flutter-App passen. */
        const val DB_NAME = "wickel_demo.db"
        const val DB_VERSION = 2

        /**
         * Zeitpunkte werden – wie von der Flutter-App – als ISO 8601 in UTC
         * inklusive Sekundenbruchteilen gespeichert, damit die lexikalische
         * Sortierung der Strings der zeitlichen entspricht.
         */
        val DB_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun zuDb(zeit: Instant): String = DB_FORMAT.format(zeit)

        /** Heutiger Tagesbeginn (lokale Zeit), optional um Tage zurückversetzt. */
        fun tagesbeginn(tageZurueck: Long = 0): Instant =
            LocalDate.now().minusDays(tageZurueck).atStartOfDay(ZoneId.systemDefault()).toInstant()
    }
}
