# Wickel-Tracker (Android + Wear OS)

Native Android-App zur Erfassung von Wickel-Vorgängen (Urin / Stuhlgang /
Beides, optional Stoffwindel), mit eigenständiger Wear-OS-App. Kotlin,
Jetpack Compose, AGP 9 mit Built-in Kotlin. Portiert von einer Flutter-App —
Bestandsdaten und -einstellungen werden beim Update nahtlos übernommen
(Details unten).

Schwester-Repos: **WickelTracker-XCode** (iOS + watchOS, gleicher
Funktionsumfang, gleiches Design) sowie **StillzeitTracker-** und
**MedikamentenTracker-Android/-XCode** (gleiche Architektur- und
Design-Familie).

---

## Module

| Modul | Was | applicationId |
|---|---|---|
| `:app` | Telefon-App (Compose, Material 3) | `org.dwarftsch.wickel` |
| `:wear` | Wear-OS-App (Compose for Wear OS) | `org.dwarftsch.wickel` (namespace `…wickel.wear`) |

Beide Module tragen **dieselbe applicationId** — Voraussetzung dafür, dass
Play die Uhr-App als Wear-Variante derselben App ausliefert und die
Data-Layer-API Telefon und Uhr einander zuordnet. Deshalb niemals beide
Debug-Varianten wahllos installieren: `:wear:installDebug` würde auf einem
Telefon-Emulator die Telefon-App ersetzen. Immer gezielt per
`adb -s <gerät> install` arbeiten.

## Einrichtung auf einem neuen Gerät

1. Repo klonen, in **Android Studio** öffnen — fertig. Es gibt keine
   externen Abhängigkeiten außer Maven-Artefakten.
2. **JDK:** Der Gradle-Daemon provisioniert sich sein JDK (Version 25)
   selbst über `gradle/gradle-daemon-jvm.properties` (Foojay-Resolver).
   Ein zu neues JDK im PATH (z. B. 26) kann den Launcher brechen; dann
   `JAVA_HOME` auf das JBR von Android Studio setzen
   (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`).
3. **Signing (nur für Release-Builds nötig):** `key.properties` nach dem
   Muster von `key.properties.example` im Repo-Root anlegen. Datei und
   Keystore (`*.jks`) sind gitignored und dürfen **nie** eingecheckt
   werden. Die Apps der Familie teilen sich denselben Upload-Keystore —
   Play prüft den Fingerprint beim Upload und lehnt Debug-signierte
   Bundles ab. Ohne `key.properties` signieren Release-Builds automatisch
   mit dem Debug-Key (lokal baubar, aber nicht Play-tauglich).

## Bauen & Testen

```bash
# Debug-APKs
./gradlew :app:assembleDebug :wear:assembleDebug

# Release (signiert, falls key.properties vorhanden)
./gradlew :app:assembleRelease :wear:assembleRelease

# Play-Bundles; buildNumber steuert den versionCode (siehe Versionierung)
./gradlew :app:bundleRelease :wear:bundleRelease -PbuildNumber=123

# Wear-App gezielt auf dem Uhr-Emulator installieren und starten
adb -s <wear-emulator> install -r wear/build/outputs/apk/debug/wear-debug.apk
adb -s <wear-emulator> shell am start \
  -n org.dwarftsch.wickel/org.dwarftsch.wickel.wear.MainActivity
```

Die Wear-App verlangt keine gekoppelte Uhr zum Starten — ohne erreichbares
Telefon zeigt sie den zuletzt gespiegelten Stand bzw. „Handy nicht
erreichbar“ mit Reconnect-Button.

## Versionierung

- `versionName`: manuell in `app/` und `wear/build.gradle.kts`.
  **Konvention:** Major/Minor (1.x.x, x.1.x) sind über alle Plattformen
  (Android **und** iOS) identisch; die Patch-Stelle darf pro Plattform
  divergieren.
- `versionCode`: `-PbuildNumber=<n>` (lokaler Fallback im Buildfile).
  Die CI übergibt `100 + github.run_number`; die Uhr addiert fest `+1000`.
  Play verlangt strikt steigende Codes **pro Formfaktor-Track**; die
  Flutter-App nutzte `run_number` direkt (zuletzt ≤ 10), der 100er-Versatz
  liegt sicher darüber.

## CI / Releases (`.github/workflows/build-aab.yml`)

Manuell per *workflow_dispatch*. Ein Lauf:

1. baut signierte **APKs** (Telefon + Wear) und hängt sie an ein
   GitHub-Release (`v<version>-<run_number>`) — direkt installierbar,
2. baut zusätzlich **App Bundles** und lädt sie in die Play-Tracks
   (`alpha` bzw. `wear:alpha`) — nur wenn der Schalter `play_upload`
   (Default: an) gesetzt ist.

**Play-Besonderheiten (bei Stillzeit hart erarbeitet):**
- Wear OS braucht einen **eigenen Formfaktor-Track**, der in der Console
  einmalig aktiviert werden muss. Telefon- und Wear-Bundle werden in
  **getrennten Schritten** (= getrennten Play-Edits) hochgeladen.
- Track-Namen sind **case-sensitiv** — schlägt der Wear-Upload mit
  „track not found“ fehl, listet die Fehlermeldung die verfügbaren Namen.

Benötigte **Repository-Secrets** (nur Namen, Werte niemals dokumentieren;
identisch zu den Schwester-Repos): `PLAY_KEYSTORE_BASE64`,
`PLAY_KEYSTORE_PASSWORD`, `PLAY_KEY_ALIAS`, `PLAY_KEY_PASSWORD`,
`PLAY_SERVICE_ACCOUNT_JSON`.

## Herkunft & Datenmigration (Flutter → nativ)

Die App ersetzt eine Flutter-App unter derselben applicationId
(`org.dwarftsch.wickel`). Beim Update bleiben alle Nutzerdaten erhalten:

- **SQLite:** identische Datei `wickel_demo.db` (Standard-Datenbankpfad),
  identisches Schema, `user_version 2` inkl. Upgrade-Pfad von v1
  (`stoffwindel`-Spalte). Zeitstempel als ISO 8601 UTC (lexikalisch
  sortierbar); der Parser toleriert auch die Mikrosekunden-Präzision
  alter Dart-Einträge. `last`/`undo` sortieren nach `time`, nicht `id` —
  Uhr-Einträge können nachträglich mit älterem Zeitpunkt eintreffen.
- **Einstellungen:** einmalige Migration aus `FlutterSharedPreferences`
  (Keys mit Präfix `flutter.`), inklusive der **Bool**-Einstellung
  `stoffwindel_enabled` — siehe `AppSettings`.
- **SAF-Berechtigung** des Zertifikats-Ordners überlebt das Update.

## Sicherung & Gerätewechsel (Auto-Backup / D2D)

`app/src/main/res/xml/data_extraction_rules.xml` (API 31+) und
`backup_rules.xml` (bis API 30) sind bewusst als **Whitelist** gepflegt —
sobald ein `<include>` gesetzt ist, wandert ausschließlich das Aufgeführte
mit. Vorher standen dort die unveränderten Android-Studio-Vorlagen, also
komplett auskommentiert und ohne `<device-transfer>`; damit war ungeregelt,
was in ein Cloud-Backup geht.

| | Cloud-Backup | Geräte-Transfer (D2D) |
|---|---|---|
| Einträge (`wickel_demo.db`) | ✅ | ✅ |
| `wickel_settings.xml` (Server-URL, **API-Key**) | ❌ | ✅ |

Begründung: Das Cloud-Backup liegt bei Google, der Geräte-Transfer läuft
Ende-zu-Ende-verschlüsselt direkt zwischen zwei Geräten. Der API-Key steht
im Klartext in den Prefs und hat auf fremden Servern nichts zu suchen —
nach einer Cloud-Wiederherstellung sind die Einträge da, Server-Adresse und
Key müssen aber neu eingetragen werden.

Eingebunden wird jeweils der **ganze** Datenbank-Ordner (`domain="database"
path="."`), nicht nur die `.db`-Datei: SQLite läuft im WAL-Modus, ohne
`-wal` käme ein veralteter Stand zurück.

`cert_folder_uri` (SAF-Ordner der mTLS-Zertifikate) ist in beiden Fällen
wertlos — die persistierte Leseberechtigung gilt nur auf dem Gerät, das sie
erteilt hat. `CertSource` prüft `persistedUriPermissions` und fragt den
Ordner sauber neu ab.

Beim Ändern der Regeln daran denken: **jedes neue `<include>` erweitert die
Whitelist, jede neue Prefs-Datei fehlt sonst stillschweigend.**

Die In-App-Erklärung dazu steht in `DB_INFO_TEXT` (Einstellungen → „Aufbau
Datenbank“) und muss mitgezogen werden.

## Architektur (`:app`)

```
Models.kt                    WickelType/PeriodStats(%)/LastEntry/WickelStats, ISO-Parser
data/WickelService.kt        Interface der Datenquellen + Factory
data/DemoService.kt          lokale SQLite (sqflite-kompatibel, v2)
data/ApiService.kt           REST-Client (OkHttp; api.php-Actions + mTLS)
data/ClientCertificates.kt   PEM (crt/key) -> SSLSocketFactory, inkl. PKCS#1->#8
data/CertSource.kt           SAF-Ordner mit client.crt/client.key
data/AppSettings.kt          Prefs + Flutter-Migration (inkl. Bool)
data/LocalBackupService.kt   JSON-Backup, Format kompatibel zu iOS/Flutter (Import max. 16 MB)
wear/WearRequestService.kt   Data-Layer-RPC-Endpunkt für die Uhr
ui/…                         Compose-UI (Theme, Home, Settings, Dialoge)
```

**Datenquellen (vom Nutzer wählbar):** Server per mTLS-Client-Zertifikat,
Server per API-Key oder lokale SQLite ohne Sync. Die `api.php` verlangt
den **API-Key in jedem Fall** — auch hinter mTLS.

## Watch-Protokoll (Data-Layer-API)

Die Uhr sendet `MessageClient.sendRequest` an den Pfad `/wickel/request`;
`WearRequestService` antwortet. JSON, UTF-8, gleiche Hülle wie bei den
Schwester-Apps:

```
Anfrage:  {"action": "...", "arguments": { ... }}
Antwort:  {"ok": true, "data": { ... }}  bzw.  {"ok": false, "error": "..."}
```

Aktionen: `getDashboard` (`today_total`, `stoffwindel_enabled`,
`last_type`/`last_time`/`last_stoffwindel`), `createEntry`
(`{"typ": "urin", "stoffwindel": false}`), `undoLast`. Die Uhr hat
**keine eigene Datenquelle** — alles läuft über die auf dem Telefon
gewählte Quelle. Sie spiegelt das letzte Dashboard lokal und bietet die
drei Typen als Ein-Tipp-Kacheln an (+ Stoffwindel-Umschalter, wenn die
Funktion am Telefon aktiviert ist). Das Telefon meldet die Capability
`wickel_phone_app` (res/values/wear.xml).

## REST-API & Datenmodell

Basis-URL konfiguriert der Nutzer in den Einstellungen; alle Endpunkte
liegen unter `<Basis-URL>api.php`. Alle Antworten JSON.

| Endpunkt | Zweck |
|---|---|
| `POST api.php?action=wickeln` | Eintrag anlegen: `{"typ": "urin", "stoffwindel": false}` (Typen: urin, stuhlgang, beides; Server stempelt die Zeit selbst) |
| `GET api.php?action=stats` | Statistik je Zeitraum (today, week, threeWeeks, month) mit total + Prozentanteilen je Typ, plus `last` |
| `GET api.php?action=last` | letzter Eintrag |
| `GET api.php?action=heute` | Anzahl heute |
| `POST api.php?action=undo_last` | letzten Eintrag löschen (404 = keiner vorhanden) |
| `POST api.php?action=undo` | Eintrag nach ID löschen: `{"id": 42}` |

Lokale Tabelle: `entries(id, type, time, stoffwindel)`.

## Design-System „Minze & Honig“ (v1.0)

Quelle der Wahrheit im Code: `app/…/ui/Theme.kt` (Telefon) und die
Farbsektion in `wear/…/WickelWearApp.kt` (Uhr). Kernregeln wie in den
Schwester-Repos (Weiß dominiert, Skalen 50–900, Pastell 300 nie als Text
auf Weiß, Nunito, Radien 8/12/16/24/Pill, Dark-Grund `#1F2221`).

**Wickel-Typen (Chip-Muster, plattformübergreifend identisch):**
Urin = Honig, Stuhlgang = Grau, Beides = Flieder; die
**Stoffwindel-Funktion trägt Minze** (Fläche 300 + Text 900; zarte
100er-Flächen bzw. Dark-Äquivalente für Avatare und Balkengrund).

## Sicherheit / was nie ins Repo darf

`key.properties`, `*.jks`, Play-Service-Account-JSON, API-Keys,
Server-URLs von Nutzern. Die `.gitignore` deckt das ab — bei neuen
Secrets zuerst dort eintragen, dann anlegen.
