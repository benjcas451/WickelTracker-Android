package org.dwarftsch.wickel.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.dwarftsch.wickel.WickelStats
import org.dwarftsch.wickel.WickelType
import org.dwarftsch.wickel.data.AppSettings
import org.dwarftsch.wickel.data.CertSource
import org.dwarftsch.wickel.data.OfflineService
import org.dwarftsch.wickel.data.Verbindungswache
import org.dwarftsch.wickel.data.WickelService
import org.dwarftsch.wickel.data.createConfiguredWickelService
import org.dwarftsch.wickel.data.meldung
import org.dwarftsch.wickel.wear.WatchChangeBus

data class HomeUiState(
    val laedt: Boolean = true,
    val fehler: String? = null,
    val stats: WickelStats? = null,
    /** Stoffwindel-Funktion in den Einstellungen aktiviert? */
    val stoffwindelEnabled: Boolean = false,
    /** Umschaltfläche: nächster Eintrag ist eine Stoffwindel. */
    val stoffwindelActive: Boolean = false,
    /** Grund der abgebrochenen Verbindung; null heisst „online“. */
    val offlineGrund: String? = null,
    /** Anzahl der Einträge, die noch auf Übertragung warten. */
    val ausstehend: Int = 0,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    val settings = AppSettings(application)
    val certSource = CertSource(application, settings)

    private val state = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = state

    private val meldungenFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Snackbar-Meldungen (Fehler etc.). */
    val meldungen: SharedFlow<String> = meldungenFlow

    /** Aktive Datenquelle: API (mTLS/API-Key/Cloudflare) oder lokale SQLite-DB. */
    private var service: WickelService? = null

    /** Beobachtet den Zustand der aktuellen Offline-Hülle; null im Demo. */
    private var zustandBeobachter: Job? = null

    private val wache = Verbindungswache(application)

    init {
        // Schreibzugriffe der Uhr lösen ein Neuladen aus.
        viewModelScope.launch {
            WatchChangeBus.aenderungen.drop(1).collect { aktualisieren() }
        }
        // Sobald wieder ein Netz da ist, die Warteschlange abarbeiten – ohne
        // dass der Nutzer etwas antippen muss.
        wache.starten()
        viewModelScope.launch {
            wache.wiederVerbunden.collect { aktualisieren() }
        }
        datenquelleNeuAufbauen()
    }

    override fun onCleared() {
        wache.beenden()
        service?.dispose()
        super.onCleared()
    }

    /**
     * Baut die Datenquelle anhand der Einstellung neu auf (z. B. nach dem
     * Verlassen der Einstellungen) und lädt anschließend neu.
     */
    fun datenquelleNeuAufbauen() {
        service?.dispose()
        zustandBeobachter?.cancel()
        val neu = createConfiguredWickelService(
            getApplication(), settings, certSource, offlineFaehig = true,
        )
        service = neu
        // Der Offline-Hinweis des alten Zugangs darf nicht über dem neuen
        // stehen bleiben.
        state.value = state.value.copy(
            stoffwindelEnabled = settings.stoffwindelEnabled,
            offlineGrund = null,
            ausstehend = 0,
        )
        if (neu is OfflineService) {
            zustandBeobachter = viewModelScope.launch {
                neu.zustand.collect { zustand ->
                    state.value = state.value.copy(
                        offlineGrund = zustand.grund,
                        ausstehend = zustand.ausstehend,
                    )
                }
            }
        }
        aktualisieren()
    }

    fun aktualisieren() {
        val aktiverService = service ?: return
        state.value = state.value.copy(laedt = true, fehler = null)
        viewModelScope.launch {
            // Erst das Liegengebliebene loswerden, dann laden: sonst zeigte die
            // Statistik einen Serverstand ohne die eigenen Einträge.
            warteschlangeAbarbeiten(aktiverService)
            runCatching { aktiverService.getStats() }.fold(
                onSuccess = { stats ->
                    state.value = state.value.copy(laedt = false, stats = stats)
                },
                onFailure = { fehler ->
                    state.value = state.value.copy(laedt = false, fehler = fehler.meldung())
                },
            )
        }
    }

    fun setzeStoffwindelActive(aktiv: Boolean) {
        state.value = state.value.copy(stoffwindelActive = aktiv)
    }

    fun anlegen(type: WickelType) {
        val aktiverService = service ?: return
        val sw = state.value.stoffwindelEnabled && state.value.stoffwindelActive
        viewModelScope.launch {
            runCatching { aktiverService.addEntry(type, stoffwindel = sw) }.fold(
                onSuccess = {
                    meldungenFlow.tryEmit("${type.label} gespeichert${if (sw) " · 🧷 Stoffwindel" else ""}")
                    aktualisieren()
                },
                onFailure = { meldungenFlow.tryEmit("Fehler: ${it.meldung()}") },
            )
        }
    }

    fun letztenRueckgaengig() {
        val aktiverService = service ?: return
        viewModelScope.launch {
            runCatching { aktiverService.undoLast() }.fold(
                onSuccess = { entfernt ->
                    meldungenFlow.tryEmit(if (entfernt) "Letzter Eintrag gelöscht" else "Kein Eintrag vorhanden")
                    aktualisieren()
                },
                onFailure = { meldungenFlow.tryEmit("Fehler: ${it.meldung()}") },
            )
        }
    }

    /**
     * Schickt die offenen Einträge zum Server. Verworfene (vom Server
     * inhaltlich zurückgewiesene) meldet sie einmal gesammelt.
     */
    private suspend fun warteschlangeAbarbeiten(dienst: WickelService) {
        if (dienst !is OfflineService) return
        val verworfen = dienst.nachholen()
        if (verworfen.isEmpty()) return
        meldungenFlow.tryEmit(
            if (verworfen.size == 1) {
                "Ein wartender Eintrag wurde vom Server abgelehnt: ${verworfen.first()}"
            } else {
                "${verworfen.size} wartende Einträge wurden vom Server abgelehnt."
            },
        )
    }
}
