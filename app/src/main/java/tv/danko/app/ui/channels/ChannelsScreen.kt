package tv.danko.app.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.danko.app.data.db.ChannelEntity
import tv.danko.app.ui.SkinMenuButton
import tv.danko.app.ui.tvFocusBorder

/** Lista de canales con buscador, filtro por grupo y favoritos (mainview sin video). */
@Composable
fun ChannelsScreen(
    listName: String,
    vm: ChannelsViewModel,
    skin: String,
    onSkin: (String) -> Unit,
    onPlay: (ChannelEntity, List<ChannelEntity>) -> Unit,
    onGuide: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    // TV: foco inicial UNA sola vez en el primer canal. OJO: no re-pedir foco
    // cuando la lista cambia — cada letra del buscador refiltra y el refocus
    // robaba el teclado (bug real: "las letras van a cualquier lado").
    var initialFocusDone by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(ui.channels.isNotEmpty()) {
        if (!initialFocusDone && ui.channels.isNotEmpty()) {
            initialFocusDone = true
            try {
                firstFocus.requestFocus()
            } catch (_: Exception) {
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Listas") }
            Text(
                listName, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
            )
            if (ui.loading) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp))
            }
            val ctx = LocalContext.current
            TextButton(
                onClick = {
                    vm.reload { ok ->
                        if (!ok) {
                            try {
                                android.widget.Toast.makeText(
                                    ctx, "No se pudo recargar (revisá internet)",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            } catch (_: Exception) {
                            }
                        }
                    }
                },
                enabled = !ui.loading,
                modifier = Modifier.tvFocusBorder(),
            ) { Text("⟳") }
            TextButton(
                onClick = { onGuide(ui.group) },
                modifier = Modifier.tvFocusBorder(),
            ) { Text("Guía") }
            SkinMenuButton(current = skin, onSelect = onSkin)
        }
        OutlinedTextField(
            value = ui.query,
            onValueChange = { vm.setQuery(it) },
            label = { Text("🔎 Buscar canal…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            items(ui.groups) { g ->
                FilterChip(
                    selected = ui.group == g,
                    onClick = { vm.setGroup(g) },
                    label = { Text(g, maxLines = 1) },
                    // Seleccionado BIEN visible en TV (el default con este
                    // tema casi no se distingue — bug 21/09).
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
        Text(
            "${ui.total} canales",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // key por PK: con 344k canales hay títulos/grupos duplicados reales
            // (group/title crasheaba con "key was already used").
                items(ui.channels, key = { it.id }) { ch ->
                    ChannelRow(
                        ch, fav = ch.url in ui.favs, onPlay = { onPlay(ch, ui.channels) }, onFav = { vm.toggleFav(ch.url) },
                        onHide = { vm.toggleHide(ch.url, !ch.hidden) },
                        hideLabel = if (ch.hidden) "Mostrar" else "Ocultar",
                        modifier = if (ch == ui.channels.firstOrNull()) {
                        Modifier.focusRequester(firstFocus)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    ch: ChannelEntity,
    fav: Boolean,
    onPlay: () -> Unit,
    onFav: () -> Unit,
    onHide: () -> Unit,
    hideLabel: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().tvFocusBorder()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Logo del canal (F7): Coil con caché; sin logo no ocupa lugar.
            if (ch.logo.isNotEmpty()) {
                coil.compose.AsyncImage(
                    model = ch.logo,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.shapes.small,
                        ),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    ch.title,
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                )
                Text(ch.group, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            // Sin clickable anidado (igual que ListRow, bug TV 21/09): "Ver"
            // es el botón primario para D-pad y touch.
            TextButton(onClick = onPlay) { Text("Ver") }
            // Estrella dibujada (el glifo ★/☆ no renderiza en algunos
            // teléfonos — mismo caso que el botón fav del player).
            IconButton(onClick = onFav) {
                tv.danko.app.ui.StarIcon(fav, tint = MaterialTheme.colorScheme.primary)
            }
            // Editor F7: ocultar/mostrar (texto: sin glifos).
            TextButton(onClick = onHide) { Text(hideLabel) }
        }
    }
}
