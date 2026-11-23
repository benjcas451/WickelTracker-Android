package org.dwarftsch.wickel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dwarftsch.wickel.R
import org.dwarftsch.wickel.data.AppSettings
import org.dwarftsch.wickel.data.CertSource
import org.dwarftsch.wickel.data.DataSourceMode
import org.dwarftsch.wickel.data.meldung
import org.dwarftsch.wickel.data.DemoService
import org.dwarftsch.wickel.data.LocalBackupService

/**
 * Was der Bildschirm über die mTLS-Zertifikate anzeigt. Beides wird gemeinsam
 * im Hintergrund ermittelt, damit die Composition weder Dateien liest noch
 * `persistedUriPermissions` beim System erfragt.
 */
private data class ZertifikatsStand(
    val gefunden: Boolean = false,
    val ordner: String? = null,
)

/** Beschreibung der REST-API (für den Dialog "Aufbau API"). */
private const val API_INFO_TEXT = """
Die App spricht die Wickel-Tracker-API unter <Basis-URL>api.php an. Alle Antworten sind JSON.

Endpunkte:

• POST <Basis-URL>api.php?action=wickeln
  Neuen Eintrag anlegen, Body: {"typ": "urin", "stoffwindel": false}
  Erlaubte Typen: urin, stuhlgang, beides
  Antwort: {"ok": true, "id": 42, "typ": "urin", "zeit": "14:30"}

• GET <Basis-URL>api.php?action=last
  Letzter Eintrag: {"typ": "...", "zeitstempel": "...", "zeit_kurz": "HH:MM"}

• GET <Basis-URL>api.php?action=heute
  Anzahl heute: {"anzahl": 7}

• GET <Basis-URL>api.php?action=stats
  Statistik je Zeitraum (today, week, threeWeeks, month) mit total und
  Prozentanteilen je Typ, plus "last": {"type": "...", "time": ISO8601}

• POST <Basis-URL>api.php?action=undo_last
  Letzten Eintrag löschen: {"ok": true, "removed": 42}

• POST <Basis-URL>api.php?action=undo
  Eintrag nach ID löschen, Body: {"id": 42}

Authentifizierung:
• Header "X-API-Key: <Key>" ist in jedem Fall erforderlich – auch hinter mTLS.
• Im Modus "Server (mTLS-API)" zusätzlich ein Client-Zertifikat
  (client.crt + client.key) auf Transport-Ebene.
• Im Modus "Server (Cloudflare Access)" zusätzlich die Header
  "CF-Access-Client-Id: <ID>" und "CF-Access-Client-Secret: <Secret>".

Fehler kommen als {"error": "..."} mit passendem HTTP-Statuscode.
"""

/** Beschreibung der lokalen SQLite-Datenbank (für den Dialog "Aufbau Datenbank"). */
private const val DB_INFO_TEXT = """
Im Modus "Lokal (SQLite)" speichert die App alle Einträge in der Datenbank wickel_demo.db im app-privaten Speicher. Andere Apps haben keinen Zugriff, es findet keine Synchronisation statt.

Tabelle "entries":

• id
  INTEGER, Primärschlüssel (Auto-Increment)

• type
  TEXT: urin, stuhlgang oder beides

• time
  TEXT, Zeitpunkt als ISO 8601 in UTC gespeichert (dadurch chronologisch sortierbar), Anzeige in lokaler Zeit

• stoffwindel
  INTEGER (0/1): Eintrag war eine Stoffwindel

Die Statistik (heute / 7 Tage / 3 Wochen / 30 Tage) wird lokal aus diesen Einträgen berechnet – mit denselben Prozentwerten wie die Server-API.

Sicherung & Gerätewechsel

Android sichert die App automatisch. Was dabei mitgeht, legt die App bewusst unterschiedlich fest:

• Cloud-Backup (über das Google-Konto)
  Nur die Einträge (SQLite). Die Einstellungen bleiben außen vor, weil dort der API-Key steht – der soll nicht auf fremde Server. Nach einer Wiederherstellung aus der Cloud sind also alle Einträge da, Server-Adresse und API-Key müssen aber neu eingetragen werden.

• Direkter Gerätewechsel (altes Gerät → neues Gerät)
  Zusätzlich die Einstellungen inklusive API-Key. Diese Übertragung läuft Ende-zu-Ende-verschlüsselt unmittelbar zwischen den beiden Geräten.

Den Zertifikats-Ordner für mTLS muss man in beiden Fällen neu auswählen: die Leseberechtigung darauf gilt nur auf dem Gerät, auf dem sie erteilt wurde, und lässt sich technisch nicht mitnehmen.

Davon unabhängig bleibt das manuelle JSON-Backup weiter unten – es nimmt die Einträge mit, egal wohin. Beim Wiederherstellen sind Dateien bis 16 MB zulässig; alles darüber lehnt die App ab, statt beim Einlesen den Arbeitsspeicher zu sprengen.
"""

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    certSource: CertSource,
    onZurueck: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var mode by remember { mutableStateOf(settings.mode) }
    var apiUrl by remember { mutableStateOf(settings.apiBaseUrl) }
    var apiKeyUrl by remember { mutableStateOf(settings.apiKeyBaseUrl) }
    var cloudflareUrl by remember { mutableStateOf(settings.cloudflareBaseUrl) }
    var cfClientId by remember { mutableStateOf(settings.cfAccessClientId) }
    var cfClientSecret by remember { mutableStateOf(settings.cfAccessClientSecret) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var apiKeySichtbar by remember { mutableStateOf(false) }
    // Zertifikats-Status *und* Ordnername zusammen: `CertSource.locationLabel`
    // fragt `persistedUriPermissions` beim System ab (Binder-Aufruf). Das darf
    // nicht bei jeder Recomposition passieren – und der Stoffwindel-Schalter
    // loest genau die aus.
    var zertifikate by remember { mutableStateOf(ZertifikatsStand()) }
    var beschaeftigt by remember { mutableStateOf(false) }
    var infoDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var restoreBestaetigen by remember { mutableStateOf(false) }
    var stoffwindelEnabled by remember { mutableStateOf(settings.stoffwindelEnabled) }

    fun zeige(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    suspend fun pruefeZertifikate(): ZertifikatsStand = withContext(Dispatchers.IO) {
        // Kein runCatching: das schluckt auch die CancellationException, mit der
        // Compose den Effekt beim Verlassen des Bildschirms abbricht. Die muss
        // weiterfliegen, sonst laeuft die Coroutine nach dem Zurueck weiter und
        // schreibt in einen Zustand, den es nicht mehr gibt.
        val gefunden = try {
            certSource.readCredentials()
            true
        } catch (abbruch: CancellationException) {
            throw abbruch
        } catch (fehler: Exception) {
            false
        }
        ZertifikatsStand(gefunden = gefunden, ordner = certSource.locationLabel)
    }

    LaunchedEffect(Unit) { zertifikate = pruefeZertifikate() }

    // Die Contracts einmal merken: `rememberLauncherForActivityResult` nimmt den
    // Contract als Schluessel seines DisposableEffect. Ein bei jeder Recomposition
    // neu erzeugter Contract meldet den Launcher deshalb jedes Mal ab und wieder
    // an – und verliert dabei ein bereits laufendes Ergebnis.
    val ordnerContract = remember { ActivityResultContracts.OpenDocumentTree() }
    val speichernContract = remember { ActivityResultContracts.CreateDocument("application/json") }
    val oeffnenContract = remember { ActivityResultContracts.OpenDocument() }

    // Ordner-Auswahl für die mTLS-Zertifikate (Storage Access Framework).
    val ordnerWahl = rememberLauncherForActivityResult(ordnerContract) { uri ->
        if (uri != null) {
            scope.launch {
                val uebernommen = runCatching { certSource.uebernehmeOrdner(uri) }
                    .onFailure { zeige("Fehler bei der Ordnerauswahl: ${it.meldung()}") }
                    .isSuccess
                zertifikate = pruefeZertifikate()
                if (uebernommen) {
                    zeige(if (zertifikate.gefunden) "Zertifikate gefunden" else "Keine Zertifikate gefunden")
                }
            }
        }
    }

    // Backup-Export: Ziel-Datei über den System-Speichern-Dialog wählen.
    val backupSpeichern = rememberLauncherForActivityResult(speichernContract) { uri ->
        if (uri != null) {
            beschaeftigt = true
            scope.launch {
                runCatching {
                    val json = LocalBackupService.exportJson(DemoService(context).exportRows())
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("Datei ließ sich nicht schreiben.")
                    }
                }.fold(
                    onSuccess = { zeige("Backup gespeichert") },
                    onFailure = { zeige("Backup fehlgeschlagen: ${it.meldung()}") },
                )
                beschaeftigt = false
            }
        }
    }

    // Backup-Restore: Quelldatei wählen, validieren, Bestand ersetzen.
    val backupOeffnen = rememberLauncherForActivityResult(oeffnenContract) { uri ->
        if (uri != null) {
            beschaeftigt = true
            scope.launch {
                runCatching {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            LocalBackupService.leseBegrenzt(it)
                        } ?: error("Datei ließ sich nicht lesen.")
                    }
                    val rows = LocalBackupService.parseUndValidiere(text)
                    DemoService(context).replaceAll(rows)
                    rows.size
                }.fold(
                    onSuccess = { zeige("Backup wiederhergestellt: $it Einträge") },
                    onFailure = { zeige("Wiederherstellung fehlgeschlagen: ${it.meldung()}") },
                )
                beschaeftigt = false
            }
        }
    }

    Scaffold(
        topBar = { MhKopf(titel = "Einstellungen", onZurueck = onZurueck) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Mh.farben.bg,
    ) { innenAbstand ->
        val f = Mh.farben
        Column(
            modifier = Modifier
                .padding(innenAbstand)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Mh.rand)
                .padding(bottom = Mh.abstand8),
        ) {
            MhGruppenLabel("Datenquelle", Modifier.padding(top = 0.dp))

            ModusZeile(
                gewaehlt = mode == DataSourceMode.API,
                titel = "Server (mTLS-API)",
                untertitel = "Client-Zertifikat + API-Key",
            ) { mode = DataSourceMode.API; settings.mode = mode }
            ModusZeile(
                gewaehlt = mode == DataSourceMode.API_KEY,
                titel = "Server (API-Key)",
                untertitel = "API-Key ohne Client-Zertifikat",
            ) { mode = DataSourceMode.API_KEY; settings.mode = mode }
            ModusZeile(
                gewaehlt = mode == DataSourceMode.CLOUDFLARE,
                titel = "Server (Cloudflare Access)",
                untertitel = "Service Token + API-Key",
            ) { mode = DataSourceMode.CLOUDFLARE; settings.mode = mode }
            ModusZeile(
                gewaehlt = mode == DataSourceMode.DEMO,
                titel = "Lokal (SQLite)",
                untertitel = "Einträge bleiben nur auf diesem Gerät",
            ) { mode = DataSourceMode.DEMO; settings.mode = mode }

            // Der API-Key wird in allen Server-Modi mitgesendet – die
            // api.php verlangt ihn in jedem Fall, auch hinter mTLS und
            // hinter Cloudflare Access.
            val apiKeyFeld: @Composable (String?) -> Unit = { hilfe ->
                GeheimFeld(
                    wert = apiKey,
                    titel = "API-Key",
                    hinweis = hilfe,
                    sichtbar = apiKeySichtbar,
                    onSichtbarkeit = { apiKeySichtbar = !apiKeySichtbar },
                    onAenderung = {
                        apiKey = it
                        settings.apiKey = it
                    },
                )
            }

            if (mode == DataSourceMode.API_KEY) {
                MhGruppenLabel("Server (API-Key)")
                UrlFeld(wert = apiKeyUrl, onAenderung = {
                    apiKeyUrl = it
                    settings.apiKeyBaseUrl = it
                })
                Spacer(Modifier.height(Mh.abstand4))
                apiKeyFeld("Erforderlich, die api.php verlangt den Key in jedem Fall.")
            }

            // Cloudflare Access prüft das Service Token am Rand und reicht die
            // Anfrage erst danach an den Server weiter.
            if (mode == DataSourceMode.CLOUDFLARE) {
                MhGruppenLabel("Server (Cloudflare Access)")
                UrlFeld(wert = cloudflareUrl, onAenderung = {
                    cloudflareUrl = it
                    settings.cloudflareBaseUrl = it
                })
                Spacer(Modifier.height(Mh.abstand4))
                var idSichtbar by remember { mutableStateOf(false) }
                GeheimFeld(
                    wert = cfClientId,
                    titel = "Client-ID",
                    hinweis = "Client-ID des Service Tokens, endet üblicherweise auf „.access“.",
                    sichtbar = idSichtbar,
                    onSichtbarkeit = { idSichtbar = !idSichtbar },
                    onAenderung = {
                        cfClientId = it
                        settings.cfAccessClientId = it
                    },
                )
                Spacer(Modifier.height(Mh.abstand4))
                var secretSichtbar by remember { mutableStateOf(false) }
                GeheimFeld(
                    wert = cfClientSecret,
                    titel = "Client-Secret",
                    hinweis = "Beide Teile nötig. Service Tokens laufen ab, " +
                        "standardmäßig nach einem Jahr.",
                    sichtbar = secretSichtbar,
                    onSichtbarkeit = { secretSichtbar = !secretSichtbar },
                    onAenderung = {
                        cfClientSecret = it
                        settings.cfAccessClientSecret = it
                    },
                )
                Spacer(Modifier.height(Mh.abstand4))
                apiKeyFeld("Erforderlich, die api.php verlangt den Key in jedem Fall.")
            }

            if (mode == DataSourceMode.API) {
                MhGruppenLabel("Server (mTLS-API)")
                UrlFeld(wert = apiUrl, onAenderung = {
                    apiUrl = it
                    settings.apiBaseUrl = it
                })
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Mh.abstand4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MhSymbol(
                        if (zertifikate.gefunden) R.drawable.ic_mh_check else R.drawable.ic_mh_alert,
                        null,
                        farbe = if (zertifikate.gefunden) f.successFg else f.danger,
                    )
                    Spacer(Modifier.width(Mh.abstand3))
                    Column {
                        Text(
                            if (zertifikate.gefunden) "Zertifikate gefunden" else "Keine Zertifikate gefunden",
                            style = MhTypo.body.copy(fontWeight = FontWeight.Medium),
                            color = f.textStrong,
                        )
                        Text(
                            zertifikate.ordner ?: "Kein Ordner ausgewählt",
                            style = MhTypo.small,
                            color = f.textSecondary,
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Mh.abstand2),
                    verticalArrangement = Arrangement.spacedBy(Mh.abstand2),
                ) {
                    MhZweitButton(
                        "Zertifikats-Ordner wählen",
                        { ordnerWahl.launch(null) },
                        symbol = R.drawable.ic_mh_folder,
                    )
                    MhTextButton(
                        "Erneut prüfen",
                        {
                            scope.launch {
                                zertifikate = pruefeZertifikate()
                                zeige(
                                    if (zertifikate.gefunden) {
                                        "Zertifikate gefunden"
                                    } else {
                                        "Keine Zertifikate gefunden"
                                    },
                                )
                            }
                        },
                        symbol = R.drawable.ic_mh_refresh,
                        modifier = Modifier.heightIn(min = Mh.touch),
                    )
                }
                Spacer(Modifier.height(Mh.abstand4))
                apiKeyFeld(null)
            }

            if (mode == DataSourceMode.DEMO) {
                MhGruppenLabel("Backup")
                MhZweitButton(
                    if (beschaeftigt) "Bitte warten …" else "Backup speichern",
                    { backupSpeichern.launch(LocalBackupService.dateiname()) },
                    symbol = R.drawable.ic_mh_upload,
                    enabled = !beschaeftigt,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Mh.abstand2))
                MhZweitButton(
                    "Backup wiederherstellen",
                    { restoreBestaetigen = true },
                    symbol = R.drawable.ic_mh_download,
                    enabled = !beschaeftigt,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Im Dateidialog Google Drive als Ziel wählen.",
                    style = MhTypo.small,
                    color = f.textSecondary,
                    modifier = Modifier.padding(top = Mh.abstand2),
                )
            }

            MhGruppenLabel("Optionen")
            MhSchalterZeile(
                titel = "Stoffwindel-Funktion",
                untertitel = "Zeigt beim Eintragen eine Umschaltfläche für Stoffwindeln",
                an = stoffwindelEnabled,
                onWechsel = {
                    stoffwindelEnabled = it
                    settings.stoffwindelEnabled = it
                },
                symbol = R.drawable.ic_mh_diaper,
            )

            MhGruppenLabel("Erklärung")
            MhListenZeile(
                "Aufbau API",
                symbol = R.drawable.ic_mh_cloud,
                pfeil = true,
                onClick = { infoDialog = "Aufbau API" to API_INFO_TEXT.trim() },
            )
            MhTrennlinie()
            MhListenZeile(
                "Aufbau Datenbank",
                symbol = R.drawable.ic_mh_list,
                pfeil = true,
                onClick = { infoDialog = "Aufbau Datenbank" to DB_INFO_TEXT.trim() },
            )
        }
    }

    infoDialog?.let { (titel, text) ->
        InfoDialog(titel = titel, text = text, onSchliessen = { infoDialog = null })
    }

    if (restoreBestaetigen) {
        AlertDialog(
            onDismissRequest = { restoreBestaetigen = false },
            title = { Text("Backup wiederherstellen", style = MhTypo.h2, color = Mh.farben.textStrong) },
            text = {
                Text(
                    "Alle aktuell lokal gespeicherten Einträge werden durch den Inhalt " +
                        "des Backups ersetzt. Das lässt sich nicht rückgängig machen.",
                    style = MhTypo.body,
                    color = Mh.farben.text,
                )
            },
            confirmButton = {
                MhTextButton("Backup auswählen", {
                    restoreBestaetigen = false
                    backupOeffnen.launch(arrayOf("application/json"))
                })
            },
            dismissButton = { MhTextButton("Abbrechen", { restoreBestaetigen = false }) },
        )
    }
}

/** Auswahlzeile mit Optionsfeld: Titel, Nebentext, ganze Zeile tippbar. */
@Composable
private fun ModusZeile(
    gewaehlt: Boolean,
    titel: String,
    untertitel: String,
    onWahl: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = gewaehlt, role = Role.RadioButton, onClick = onWahl)
            .padding(vertical = Mh.abstand2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = gewaehlt,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = Mh.farben.primary,
                unselectedColor = Mh.farben.textTertiary,
            ),
        )
        Spacer(Modifier.width(Mh.abstand3))
        Column {
            Text(titel, style = MhTypo.body.copy(fontWeight = FontWeight.Medium), color = Mh.farben.textStrong)
            Text(untertitel, style = MhTypo.small, color = Mh.farben.textSecondary)
        }
    }
}

/**
 * Verdecktes Eingabefeld mit Auge zum Aufdecken – für den API-Key und die
 * beiden Hälften des Cloudflare Service Tokens.
 */
@Composable
private fun GeheimFeld(
    wert: String,
    titel: String,
    hinweis: String?,
    sichtbar: Boolean,
    onSichtbarkeit: () -> Unit,
    onAenderung: (String) -> Unit,
) {
    MhFeld(titel, hinweis = hinweis) {
        OutlinedTextField(
            value = wert,
            onValueChange = onAenderung,
            singleLine = true,
            textStyle = MhTypo.body,
            colors = mhFeldFarben(),
            shape = MhFeldForm,
            visualTransformation = if (sichtbar) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            trailingIcon = {
                MhSymbolButton(
                    if (sichtbar) R.drawable.ic_mh_eye_off else R.drawable.ic_mh_eye,
                    if (sichtbar) "Verbergen" else "Anzeigen",
                    onSichtbarkeit,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Eingabefeld für eine API-Basis-URL. */
@Composable
private fun UrlFeld(wert: String, onAenderung: (String) -> Unit) {
    MhFeld("API-URL", hinweis = "Basis-URL der API inkl. abschließendem /") {
        OutlinedTextField(
            value = wert,
            onValueChange = onAenderung,
            singleLine = true,
            textStyle = MhTypo.body,
            colors = mhFeldFarben(),
            shape = MhFeldForm,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
