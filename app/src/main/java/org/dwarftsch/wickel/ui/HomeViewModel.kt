package org.dwarftsch.wickel.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import org.dwarftsch.wickel.data.WickelService
import org.dwarftsch.wickel.data.createConfiguredWickelService
import org.dwarftsch.wickel.wear.WatchChangeBus

data class HomeUiState(
    val laedt: Boolean = true,
    val fehler: String? = null,
    val stats: WickelStats? = null,
    /** Stoffwindel-Funktion in den Einstellungen aktiviert? */
    val stoffwindelEnabled: Boolean = false,
    /** Umschaltfläche: nächster Eintrag ist eine Stoffwindel. */
    val stoffwindelActive: Boolean = false,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    val settings = AppSettings(application)
    val certSource = CertSource(application, settings)

    private val state = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = state

    private val meldungenFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Snackbar-Meldungen (Fehler etc.). */
    val meldungen: SharedFlow<String> = meldungenFlow

    /** Aktive Datenquelle: API (mTLS/API-Key) oder lokale SQLite-DB. */
    private var service: WickelService? = null

    init {
        // Schreibzugriffe der Uhr lösen ein Neuladen aus.
        viewModelScope.launch {
            WatchChangeBus.aenderungen.drop(1).collect { aktualisieren() }
        }
        datenquelleNeuAufbauen()
    }

    override fun onCleared() {
        service?.dispose()
        super.onCleared()
    }

    /**
     * Baut die Datenquelle anhand der Einstellung neu auf (z. B. nach dem
     * Verlassen der Einstellungen) und lädt anschließend neu.
     */
    fun datenquelleNeuAufbauen() {
        service?.dispose()
        service = createConfiguredWickelService(getApplication(), settings, certSource)
        state.value = state.value.copy(stoffwindelEnabled = settings.stoffwindelEnabled)
        aktualisieren()
    }

    fun aktualisieren() {
        val aktiverService = service ?: return
        state.value = state.value.copy(laedt = true, fehler = null)
        viewModelScope.launch {
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
}

/** Lesbare Meldung einer Exception (ApiException liefert Statuscode mit). */
internal fun Throwable.meldung(): String = when (this) {
    is org.dwarftsch.wickel.data.ApiException -> toString()
    else -> message ?: toString()
}
