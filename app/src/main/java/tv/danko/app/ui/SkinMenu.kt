package tv.danko.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.danko.app.ui.theme.DANKO_SKINS

/** Menú ⋮ con los 12 temas (paridad Ver→Tema en Linux). Marca el actual con ✓. */
@Composable
fun SkinMenuButton(current: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.tvFocusBorder()) {
            Text("⋮", color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DANKO_SKINS.forEach { s ->
                DropdownMenuItem(
                    text = { Text(if (s.name == current) "✓ ${s.name}" else s.name) },
                    onClick = {
                        onSelect(s.name)
                        open = false
                    },
                )
            }
        }
    }
}
