#!/bin/bash
# Richtet eine frische Cloud-Umgebung so ein, dass ./gradlew ohne weitere
# Handgriffe baut, lintet und testet:
#
#   1. JDK 25 - gradle/gradle-daemon-jvm.properties verlangt genau das. Ohne
#      lokal installiertes JDK 25 versucht Gradle, sich eines ueber
#      api.foojay.io zu ziehen; das ist in der Standard-Umgebung geblockt und
#      der Build bricht ab, bevor er anfaengt.
#   2. Android SDK - cmdline-tools, platform-tools sowie Plattform und
#      Build-Tools passend zum compileSdk aus app/build.gradle.kts.
#   3. local.properties mit sdk.dir (steht in .gitignore).
#   4. Gradle-Cache vorwaermen, damit der erste echte Build nicht erst
#      hunderte Abhaengigkeiten laedt.
#
# Idempotent: bereits erledigte Schritte werden uebersprungen.
set -euo pipefail

# Lokal (Laptop, Android Studio) nichts anfassen - dort ist die Umgebung
# schon eingerichtet. Nur in Claude Code on the web laufen.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

PROJEKT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"

melde() { echo "[session-start] $*"; }

# Fuer apt/sdkmanager root besorgen, falls wir es nicht schon sind.
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo"; fi

# --- 1. JDK 25 fuer den Gradle-Daemon -------------------------------------
java_home_25() {
  local kandidat
  for kandidat in /usr/lib/jvm/java-25-openjdk-* /usr/lib/jvm/*-25-* /usr/lib/jvm/jdk-25*; do
    [ -x "$kandidat/bin/javac" ] && { echo "$kandidat"; return 0; }
  done
  return 1
}

if ! JAVA_25="$(java_home_25)"; then
  melde "JDK 25 wird installiert (gradle-daemon-jvm.properties verlangt es)…"
  $SUDO apt-get update -qq || true
  $SUDO apt-get install -y -qq openjdk-25-jdk-headless
  JAVA_25="$(java_home_25)"
fi
melde "JDK 25: $JAVA_25"

# Gradle findet JDKs unter /usr/lib/jvm von selbst; der explizite Pfad macht
# es unabhaengig davon, wo apt das JDK ablegt.
mkdir -p "$HOME/.gradle"
if ! grep -qs "^org.gradle.java.installations.paths=" "$HOME/.gradle/gradle.properties"; then
  echo "org.gradle.java.installations.paths=$JAVA_25" >> "$HOME/.gradle/gradle.properties"
fi

# --- 2. Android SDK -------------------------------------------------------
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  melde "Android cmdline-tools werden geladen…"
  mkdir -p "$SDK_ROOT/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"
  unzip -q -o "$tmp/cmdline-tools.zip" -d "$tmp"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rm -rf "$tmp"
fi

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export JAVA_HOME="${JAVA_HOME:-$JAVA_25}"

# compileSdk aus dem Build-Skript lesen, damit die Paketauswahl nicht
# auseinanderlaeuft, wenn die App auf eine neue API-Ebene zieht.
COMPILE_SDK="$(grep -oP 'version\s*=\s*release\(\K[0-9]+' "$PROJEKT/app/build.gradle.kts" | head -1)"
COMPILE_SDK="${COMPILE_SDK:-37}"
melde "compileSdk laut app/build.gradle.kts: $COMPILE_SDK"

yes 2>/dev/null | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true

# Ab API 37 heissen die Pakete "android-37.0"; davor "android-36". Beides
# probieren, damit der Hook einen Versionssprung ueberlebt.
installiere_plattform() {
  "$SDKMANAGER" "platforms;android-${COMPILE_SDK}.0" "build-tools;${COMPILE_SDK}.0.0" > /dev/null 2>&1 ||
    "$SDKMANAGER" "platforms;android-${COMPILE_SDK}" "build-tools;${COMPILE_SDK}.0.0" > /dev/null 2>&1
}

if [ ! -d "$SDK_ROOT/platforms/android-${COMPILE_SDK}.0" ] && [ ! -d "$SDK_ROOT/platforms/android-${COMPILE_SDK}" ]; then
  melde "Android-Plattform $COMPILE_SDK und Build-Tools werden geladen…"
  installiere_plattform
fi
if [ ! -x "$SDK_ROOT/platform-tools/adb" ]; then
  melde "platform-tools werden geladen…"
  "$SDKMANAGER" "platform-tools" > /dev/null 2>&1 || true
fi

# --- 3. local.properties (steht in .gitignore) ----------------------------
if ! grep -qs "^sdk.dir=" "$PROJEKT/local.properties" 2>/dev/null; then
  echo "sdk.dir=$SDK_ROOT" >> "$PROJEKT/local.properties"
fi

# --- 4. Umgebung fuer die Sitzung festhalten ------------------------------
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export ANDROID_HOME=\"$SDK_ROOT\""
    echo "export ANDROID_SDK_ROOT=\"$SDK_ROOT\""
    echo "export JAVA_HOME=\"$JAVA_25\""
  } >> "$CLAUDE_ENV_FILE"
fi

# --- 5. Gradle-Cache vorwaermen -------------------------------------------
# Nicht kritisch: schlaegt das fehl, soll die Sitzung trotzdem starten - SDK
# und JDK stehen dann bereits, der erste Build laedt eben selbst nach.
#
# Die Unit-Tests laufen bewusst mit: Robolectric laedt seinen android-all-Jar
# erst beim Testlauf nach, und Lint zieht eigene Artefakte.
#
# Ein Kaltstart zieht einige hundert Artefakte. Antwortet Maven Central dabei
# mit HTTP 429 (Ratelimit), schaltet Gradle das Repository fuer den restlichen
# Build ab - ein einzelner Fehlschlag reisst also den ganzen Lauf mit. Dagegen:
#   * Gradle wiederholt einzelne Anfragen selbst (Properties unten),
#   * die Ziele laufen getrennt, damit ein Fehlschlag nicht das Erreichte
#     der anderen verwirft,
#   * und zwischen den Versuchen liegen Minuten, nicht Sekunden - ein
#     Ratelimit laeuft nicht in 30 Sekunden ab.
# Bereits Geladenes bleibt im Cache, jeder Versuch kommt also weiter.
gradle_property() {
  grep -qs "^$1=" "$HOME/.gradle/gradle.properties" || echo "$1=$2" >> "$HOME/.gradle/gradle.properties"
}
gradle_property "org.gradle.internal.repository.max.tentatives" "10"
gradle_property "org.gradle.internal.repository.initial.backoff" "1000"

warm_ziel() {
  local name="$1"
  shift
  local wartezeiten=(60 180 300)
  local versuch
  for versuch in 0 1 2; do
    if (cd "$PROJEKT" && ./gradlew --console=plain --quiet "$@"); then
      melde "  $name: fertig"
      return 0
    fi
    if [ "$versuch" -lt 2 ]; then
      melde "  $name: Versuch $((versuch + 1)) fehlgeschlagen (meist ein Ratelimit beim Herunterladen) - neuer Versuch in ${wartezeiten[$versuch]}s…"
      sleep "${wartezeiten[$versuch]}"
    fi
  done
  melde "  $name: WARNUNG - nach 3 Versuchen nicht durchgelaufen"
  return 1
}

melde "Gradle-Cache wird vorgewaermt (Abhaengigkeiten, Compose-BOM, Robolectric, Lint)…"
offen=0
warm_ziel "Build (app + wear)" :app:assembleDebug :wear:assembleDebug || offen=$((offen + 1))
warm_ziel "Unit-Tests"         :app:testDebugUnitTest                 || offen=$((offen + 1))
warm_ziel "Lint"               :app:lintDebug                         || offen=$((offen + 1))

if [ "$offen" -eq 0 ]; then
  melde "Vorwaermen fertig - ./gradlew baut, testet und lintet ohne weitere Downloads."
else
  melde "WARNUNG: $offen von 3 Vorwaerm-Schritten offen. SDK und JDK stehen, der erste Build laedt den Rest selbst nach."
fi
