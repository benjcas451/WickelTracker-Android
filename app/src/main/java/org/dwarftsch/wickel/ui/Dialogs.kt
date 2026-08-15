package org.dwarftsch.wickel.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
        shape = MaterialTheme.shapes.extraLarge,
        onDismissRequest = onAbbrechen,
        title = { Text(titel) },
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = onLoeschen,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text("Löschen") }
        },
        dismissButton = {
            TextButton(
                onClick = onAbbrechen,
                colors = ButtonDefaults.textButtonColors(contentColor = MinzeHonig.farben.gruenText),
            ) { Text("Abbrechen") }
        },
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
        shape = MaterialTheme.shapes.extraLarge,
        onDismissRequest = onSchliessen,
        title = { Text(titel) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSchliessen,
                colors = ButtonDefaults.textButtonColors(contentColor = MinzeHonig.farben.gruenText),
            ) { Text("Schließen") }
        },
    )
}
