package org.dwarftsch.wickel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import org.dwarftsch.wickel.ui.HomeScreen
import org.dwarftsch.wickel.ui.HomeViewModel
import org.dwarftsch.wickel.ui.SettingsScreen
import org.dwarftsch.wickel.ui.WickelTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WickelTheme {
                val viewModel: HomeViewModel = viewModel()
                // rememberSaveable: legt Android die Activity neu an (Drehen,
                // Dark-Mode-Wechsel, Rueckkehr aus dem Datei-Dialog), bleibt
                // man in den Einstellungen statt unbemerkt auf der Startseite
                // zu landen.
                var zeigeEinstellungen by rememberSaveable { mutableStateOf(false) }

                if (zeigeEinstellungen) {
                    // Beim Verlassen der Einstellungen die (womöglich neue)
                    // Datenquelle übernehmen — wie in der Flutter-App.
                    BackHandler {
                        zeigeEinstellungen = false
                        viewModel.datenquelleNeuAufbauen()
                    }
                    SettingsScreen(
                        settings = viewModel.settings,
                        certSource = viewModel.certSource,
                        onZurueck = {
                            zeigeEinstellungen = false
                            viewModel.datenquelleNeuAufbauen()
                        },
                    )
                } else {
                    HomeScreen(
                        viewModel = viewModel,
                        onEinstellungen = { zeigeEinstellungen = true },
                    )
                }
            }
        }
    }
}
