package tv.danko.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Port de SKINS en dankotv/skins.py (accent, accent2, bg, panel, sel, text, sub). */
data class DankoSkin(
    val name: String,
    val accent: Color,
    val accent2: Color,
    val bg: Color,
    val panel: Color,
    val sel: Color,
    val text: Color,
    val sub: Color,
) {
    fun toScheme(): ColorScheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF0A0A0A),
        secondary = accent2,
        tertiary = accent2,
        background = bg,
        surface = panel,
        surfaceVariant = sel,
        onBackground = text,
        onSurface = text,
        onSurfaceVariant = sub,
        secondaryContainer = sel,
        onSecondaryContainer = accent2,
    )
}

private fun skin(
    name: String,
    accent: Long, accent2: Long, bg: Long, panel: Long, sel: Long, text: Long, sub: Long,
) = DankoSkin(name, Color(accent), Color(accent2), Color(bg), Color(panel), Color(sel), Color(text), Color(sub))

val DANKO_SKINS = listOf(
    skin("Naranja Oscuro", 0xFFFF6A00, 0xFFFF8C32, 0xFF121212, 0xFF1E1E1E, 0xFF2A1A0A, 0xFFEAEAEA, 0xFFB0A090),
    skin("Naranja Neón", 0xFFFF7A00, 0xFFFFB347, 0xFF0F0F0F, 0xFF1A1A1A, 0xFF331A00, 0xFFFFF0E0, 0xFFCCAA80),
    skin("Ámbar Dorado", 0xFFFFAB00, 0xFFFFD740, 0xFF1A160F, 0xFF24200F, 0xFF332A0A, 0xFFFFF8E0, 0xFFC9B080),
    skin("Atardecer", 0xFFFF5722, 0xFFFF9800, 0xFF160F0D, 0xFF241815, 0xFF33150A, 0xFFFFEDE3, 0xFFC09A80),
    skin("Océano", 0xFF00A8FF, 0xFF4DB8FF, 0xFF0F1419, 0xFF1A2430, 0xFF0A1E33, 0xFFE0F0FF, 0xFF80A0B8),
    skin("Azul Nocturno", 0xFF3D5AFE, 0xFF8C9EFF, 0xFF0D1021, 0xFF161A35, 0xFF101640, 0xFFE3E6FF, 0xFF8A90B8),
    skin("Bosque", 0xFF00C853, 0xFF69F0AE, 0xFF0F1A14, 0xFF1A2E22, 0xFF0A3320, 0xFFE0FFE8, 0xFF80B898),
    skin("Violeta Neón", 0xFF7C4DFF, 0xFFB388FF, 0xFF14101E, 0xFF1E1A2E, 0xFF1A0A33, 0xFFF0E6FF, 0xFFA080C0),
    skin("Rosa Neón", 0xFFFF2E93, 0xFFFF7EBC, 0xFF1A0F16, 0xFF2A1622, 0xFF330A20, 0xFFFFE3F1, 0xFFC080A8),
    skin("Rojo Carmesí", 0xFFFF1744, 0xFFFF616F, 0xFF1A1014, 0xFF2E1A1E, 0xFF330A14, 0xFFFFE0E6, 0xFFC08090),
    skin("Grafito", 0xFFB0BEC5, 0xFFECEFF1, 0xFF101010, 0xFF1B1B1B, 0xFF262626, 0xFFF0F0F0, 0xFF909090),
    skin("Cian Eléctrico", 0xFF00E5FF, 0xFF84FFFF, 0xFF0C1416, 0xFF142226, 0xFF0A2A33, 0xFFDFFAFF, 0xFF7FA8B0),
)

const val DEFAULT_SKIN = "Naranja Oscuro"

// Aliases usados por pantallas (esquema por defecto).
val DankoOrange = DANKO_SKINS[0].accent
val DankoBg = DANKO_SKINS[0].bg
val DankoMuted = DANKO_SKINS[0].sub

fun skinByName(name: String): DankoSkin =
    DANKO_SKINS.firstOrNull { it.name == name } ?: DANKO_SKINS[0]

@Composable
fun DankoTVTheme(skinName: String = DEFAULT_SKIN, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = skinByName(skinName).toScheme(), content = content)
}
