package tv.danko.app.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.danko.app.data.db.ListEntity
import tv.danko.app.data.model.PlaylistSpec
import tv.danko.app.ui.tvFocusBorder

/** Pantalla "Mis listas" (espejo de HomeWindow). */
@Composable
fun ListsScreen(
    vm: ListsViewModel,
    skin: String,
    onSkin: (String) -> Unit,
    onOpen: (String) -> Unit,
    onVod: (String) -> Unit = {},
    onSeries: (String) -> Unit = {},
    onAdd: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    var showDelete by remember { mutableStateOf<String?>(null) }
    var showEdit by remember { mutableStateOf<tv.danko.app.data.db.ListEntity?>(null) }
    // TV: foco inicial en la acción principal (en teléfono no afecta al tacto).
    val entryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { entryFocus.requestFocus() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Danko TV", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Tus listas IPTV — elegí una para ver sus canales",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                )
            }
            tv.danko.app.ui.SkinMenuButton(current = skin, onSelect = onSkin)
        }
        if (ui.loading && ui.loadingBig) {
            // Alta por intent adb (9.21): tarjeta grande imposible de no ver
            // desde el sillón + aclara que en listas grandes tarda minutos.
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "⏳ Cargando '${ui.loadingName}'…",
                        fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    )
                    Text(
                        "Descargando e importando canales. En listas grandes tarda varios minutos: no apagues ni toques nada.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        } else if (ui.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
            Text("Descargando '${ui.loadingName}'…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ui.error?.let { err ->
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(err, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.clearError() }) { Text("Cerrar") }
                }
            }
        }
        if (ui.lists.isEmpty() && !ui.loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Sin listas — agregá una con ＋", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.lists, key = { it.name }) { e ->
                    ListRow(
                        e, onOpen = { onOpen(e.name) },
                        onVod = { onVod(e.name) }, onSeries = { onSeries(e.name) },
                        onEdit = { showEdit = e },
                        onDelete = { showDelete = e.name },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onAdd,
                modifier = Modifier.weight(1f).focusRequester(entryFocus).tvFocusBorder(),
            ) { Text("＋ Nueva lista") }
        }
    }
    showDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { showDelete = null },
            title = { Text("Eliminar") },
            text = { Text("¿Eliminar '$name'?") },
            confirmButton = {
                TextButton(onClick = { vm.delete(name); showDelete = null }) { Text("Sí") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = null }) { Text("No") }
            },
        )
    }
    // Editor F7: mismo diálogo precargado; al guardar recarga el contenido.
    showEdit?.let { e ->
        NewListDialog(
            initial = e,
            onDismiss = { showEdit = null },
            onSave = { spec ->
                vm.saveEdit(e.name, spec) { showEdit = null }
            },
            onTest = { spec, cb -> vm.testSpec(spec, cb) },
        )
    }
}

@Composable
private fun ListRow(
    e: ListEntity,
    onOpen: () -> Unit,
    onVod: () -> Unit,
    onSeries: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("📺  ${e.name}", fontWeight = FontWeight.Bold)
                    Text(
                        "${e.type.uppercase()} · ${e.channelCount} canales",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                    )
                }
                TextButton(onClick = onDelete) { Text("🗑") }
            }
            // F6 VOD/series por lista + editor (texto: renderiza en todos
            // lados). Sin clickable anidado: en TV el foco salta de tarjeta
            // en tarjeta y los botones quedan inalcanzables (bug 21/09).
            // "Abrir" es el botón primario (también touch).
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onOpen) { Text("Abrir") }
                TextButton(onClick = onVod) { Text("Películas") }
                TextButton(onClick = onSeries) { Text("Series") }
                TextButton(onClick = onEdit) { Text("Editar") }
            }
        }
    }
}

/** Diálogo "Nueva lista" (espejo de NewListDialog): nombre + M3U/Xtream + probar. */
@Composable
fun NewListDialog(
    initial: tv.danko.app.data.db.ListEntity? = null,
    onDismiss: () -> Unit,
    onSave: (PlaylistSpec) -> Unit,
    onTest: (PlaylistSpec, (Boolean, String) -> Unit) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var tab by remember(initial) {
        mutableIntStateOf(if (initial?.type == "xtream") 1 else 0)
    }
    var url by remember(initial) { mutableStateOf(initial?.url ?: "") }
    var host by remember(initial) { mutableStateOf(initial?.host ?: "") }
    var user by remember(initial) { mutableStateOf(initial?.user ?: "") }
    var pass by remember(initial) { mutableStateOf(initial?.pass ?: "") }
    var status by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    // Foco inicial en nombre (TV: sin toque no hay otra forma de empezar).
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            nameFocus.requestFocus()
        } catch (_: Exception) {
        }
    }

    fun spec(): Pair<PlaylistSpec?, String> {
        if (name.isBlank()) return null to "Ponle un nombre a la lista."
        return if (tab == 0) {
            if (url.isBlank()) null to "Pega la URL M3U."
            else PlaylistSpec.M3u(name.trim(), url.trim()) to ""
        } else {
            if (host.isBlank() || user.isBlank() || pass.isBlank()) null to "Completa servidor, usuario y clave."
            else PlaylistSpec.Xtream(name.trim(), host.trim(), user.trim(), pass) to ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial != null) "Editar lista" else "Nueva lista") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; err = "" }, label = { Text("Nombre (ej: Mi proveedor)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.focusRequester(nameFocus))
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("M3U (URL)") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Xtream") })
                }
                if (tab == 0) {
                    OutlinedTextField(url, { url = it; err = "" }, label = { Text("URL de la lista M3U") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
                } else {
                    OutlinedTextField(host, { host = it; err = "" }, label = { Text("Servidor (http://proveedor:8080)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }))
                    OutlinedTextField(user, { user = it; err = "" }, label = { Text("Usuario") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }))
                    OutlinedTextField(pass, { pass = it; err = "" }, label = { Text("Clave") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
                }
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
                if (status.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (testing) CircularProgressIndicator(Modifier.padding(4.dp))
                        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(
                    onClick = {
                        val (s, e) = spec()
                        if (e.isNotEmpty()) {
                            err = e
                            return@OutlinedButton
                        }
                        testing = true
                        status = "Probando…"
                        onTest(s!!) { ok, msg ->
                            testing = false
                            status = msg
                        }
                    },
                ) { Text("Probar conexión") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (s, e) = spec()
                if (e.isNotEmpty()) {
                    err = e
                    return@TextButton
                }
                onSave(s!!)
            }) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
