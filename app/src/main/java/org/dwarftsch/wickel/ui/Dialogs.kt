package org.dwarftsch.wickel.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Bestätigung vor dem Löschen des letzten Eintrags. */
@Composable
fun LoeschDialog(
    titel: String,
    text: String,
    onAbbrechen: () -> Unit,
    onLoeschen: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAbbrechen,
        title = { Text(titel, style = MhTypo.h2, color = Mh.farben.textStrong) },
        text = { Text(text, style = MhTypo.body, color = Mh.farben.text) },
        confirmButton = { MhLoeschenButton("Löschen", onLoeschen) },
        dismissButton = { MhTextButton("Abbrechen", onAbbrechen) },
    )
}

/** Scrollbarer Info-Dialog (Aufbau API / Aufbau Datenbank). */
@Composable
fun InfoDialog(
    titel: String,
    text: String,
    onSchliessen: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onSchliessen,
        title = { Text(titel, style = MhTypo.h2, color = Mh.farben.textStrong) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text, style = MhTypo.small, color = Mh.farben.text)
            }
        },
        confirmButton = { MhTextButton("Schließen", onSchliessen) },
    )
}
