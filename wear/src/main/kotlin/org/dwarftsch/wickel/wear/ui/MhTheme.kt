package org.dwarftsch.wickel.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Typography
import org.dwarftsch.wickel.wear.R

// ============================================================================
// Minze & Honig 2.0 – Theme-Schicht der Uhr-App.
// Quelle: DesignGuide/tokens/design-tokens.json (semantic.dark). Die Uhr ist
// immer dunkel; Komponenten benutzen nur diese Rollen, nie Skalenwerte.
// Gegenüber dem Handy (ui/MhTheme.kt im :app-Modul) ohne Material 3: Wear
// OS bringt eigene Komponenten mit, die über [MhWearTheme] die Rollen erhalten.
// ============================================================================

/** Rollen-Tokens des Dunkelmodus, Namen wie in design-tokens.json. */
@Immutable
data class MhFarben(
    val bg: Color,
    val surface: Color,
    val surfaceSubtle: Color,
    val border: Color,
    val text: Color,
    val textStrong: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val primary: Color,
    val onPrimary: Color,
    val danger: Color,
    val onDanger: Color,
    val selectedBg: Color,
    val onSelected: Color,
    val data1: Color,
    val data2: Color,
    val data3: Color,
)

val MhDunkel = MhFarben(
    bg = Color(0xFF1F2221),
    surface = Color(0xFF262A28),
    surfaceSubtle = Color(0xFF2B302D),
    border = Color(0xFF343A36),
    text = Color(0xFFE6EAE7),
    textStrong = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFA9B0AB),
    textTertiary = Color(0xFF8A938D),
    primary = Color(0xFF82BD9B),
    onPrimary = Color(0xFF1F2221),
    danger = Color(0xFFF0B6B1),
    onDanger = Color(0xFF1F2221),
    selectedBg = Color(0xFF2C4F3B),
    onSelected = Color(0xFFC8E8D4),
    data1 = Color(0xFFA8D5BA),
    data2 = Color(0xFFF7E8A4),
    data3 = Color(0xFFCDB4DB),
)

/** Figtree, nur die Schnitte 400/500/600 (keine Kursive). */
val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
)

/** Zugriff auf die Rollen: `Mh.farben.primary` … */
object Mh {
    val farben: MhFarben = MhDunkel
}

private val MhWearFarben = Colors(
    primary = MhDunkel.primary,
    primaryVariant = MhDunkel.primary,
    secondary = MhDunkel.data1,
    secondaryVariant = MhDunkel.data1,
    background = MhDunkel.bg,
    surface = MhDunkel.surface,
    error = MhDunkel.danger,
    onPrimary = MhDunkel.onPrimary,
    onSecondary = MhDunkel.bg,
    onBackground = MhDunkel.text,
    onSurface = MhDunkel.text,
    onSurfaceVariant = MhDunkel.textSecondary,
    onError = MhDunkel.onDanger,
)

// Wear setzt Buttons standardmäßig fett (700); 2.0 kennt nur bis 600.
private val MhWearTypo = Typography(
    defaultFontFamily = Figtree,
    button = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp, letterSpacing = 0.38.sp),
)

/** Theme der Uhr-App: Wear-Material mit den 2.0-Rollen (dunkel) und Figtree. */
@Composable
fun MhWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = MhWearFarben, typography = MhWearTypo, content = content)
}
