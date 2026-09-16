package tv.danko.app.ui

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp

/**
 * Borde al foco (D-pad/Android TV), con el color del skin activo (paridad
 * con Linux donde el foco usa el accent del skin). En teléfono no cambia
 * nada visible; en TV es lo que permite navegar.
 */
@Composable
fun Modifier.tvFocusBorder(): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(3.dp, color) else Modifier)
}
