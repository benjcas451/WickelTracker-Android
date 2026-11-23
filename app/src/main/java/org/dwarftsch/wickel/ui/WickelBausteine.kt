package org.dwarftsch.wickel.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.dwarftsch.wickel.R
import org.dwarftsch.wickel.WickelType

// ============================================================================
// Bausteine des Wickel-Trackers auf Basis von Minze & Honig 2.0.
// Typ-Zuordnung wie auf der Webseite: Urin = data2 (Honig), Urin + Stuhlgang
// = halb Honig, halb Minze (Balken data1), Nur Stuhlgang = data3 (Flieder).
// Farbe nur als Punkt (10 dp) oder Balken (4 dp), immer mit Text daneben.
// ============================================================================

/** Reihenfolge der Anzeige wie im Web: Urin, Urin + Stuhlgang, Nur Stuhlgang. */
val WickelTypReihenfolge = listOf(WickelType.URIN, WickelType.BEIDES, WickelType.STUHLGANG)

/** Datenfarbe des Typs für Balken. */
@Composable
fun WickelType.datenFarbe(): Color = when (this) {
    WickelType.URIN -> Mh.farben.data2
    WickelType.BEIDES -> Mh.farben.data1
    WickelType.STUHLGANG -> Mh.farben.data3
}

/** Punkt (10 dp) des Typs; „Urin + Stuhlgang“ ist halb Honig, halb Minze. */
@Composable
fun WickelTypPunkt(type: WickelType, modifier: Modifier = Modifier, groesse: Dp = 10.dp) {
    if (type == WickelType.BEIDES) {
        Row(modifier.size(groesse).clip(CircleShape)) {
            Box(Modifier.width(groesse / 2).fillMaxHeight().background(Mh.farben.data2))
            Box(Modifier.width(groesse / 2).fillMaxHeight().background(Mh.farben.data1))
        }
    } else {
        MhPunkt(type.datenFarbe(), modifier, groesse)
    }
}

/**
 * „Eintrag mit einem Tipp“ (`.action-row`): Fläche + Haarlinie, Punkt links,
 * „+“ in primary rechts, 60 dp hoch.
 */
@Composable
fun WickelEintragZeile(
    type: WickelType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val f = Mh.farben
    val form = RoundedCornerShape(Mh.radiusMd)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(form)
            .background(f.surface)
            .border(1.dp, f.border, form)
            .clickable(role = Role.Button, onClickLabel = "${type.label} eintragen", onClick = onClick)
            .padding(horizontal = Mh.abstand4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Mh.abstand3),
    ) {
        WickelTypPunkt(type)
        Text(
            type.label,
            style = MhTypo.body.copy(fontWeight = FontWeight.Medium),
            color = f.textStrong,
            modifier = Modifier.weight(1f),
        )
        MhSymbol(R.drawable.ic_mh_plus, null, farbe = f.primary)
    }
}

/** Schalter in den Rollen-Farben: an = primary, aus = Haarlinie. */
@Composable
fun mhSchalterFarben(): SwitchColors {
    val f = Mh.farben
    return SwitchDefaults.colors(
        checkedThumbColor = f.onPrimary,
        checkedTrackColor = f.primary,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = f.textTertiary,
        uncheckedTrackColor = f.surfaceSubtle,
        uncheckedBorderColor = f.borderStrong,
    )
}

/**
 * Zeile mit Schalter: die ganze Zeile ist antippbar, optional Symbol links
 * und Nebentext unter dem Titel.
 */
@Composable
fun MhSchalterZeile(
    titel: String,
    an: Boolean,
    onWechsel: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    untertitel: String? = null,
    @DrawableRes symbol: Int? = null,
) {
    val f = Mh.farben
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = an, role = Role.Switch, onValueChange = onWechsel)
            .padding(vertical = Mh.abstand2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) {
            MhSymbol(symbol, null, farbe = f.textSecondary)
            Spacer(Modifier.width(Mh.abstand3))
        }
        Column(Modifier.weight(1f)) {
            Text(titel, style = MhTypo.body.copy(fontWeight = FontWeight.Medium), color = f.textStrong)
            if (untertitel != null) Text(untertitel, style = MhTypo.small, color = f.textSecondary)
        }
        Spacer(Modifier.width(Mh.abstand3))
        Switch(checked = an, onCheckedChange = null, colors = mhSchalterFarben())
    }
}
