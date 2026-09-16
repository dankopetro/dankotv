package tv.danko.app.ui.player

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.danko.app.player.MpvSurfaceView
import tv.danko.app.player.PlayerState
import tv.danko.app.player.SeamlessPlayer
import tv.danko.app.ui.theme.DankoMuted
import tv.danko.app.ui.theme.DankoOrange
import tv.danko.app.ui.tvFocusBorder

/** Reproductor con controles (espejo de la barra de MainWindow). */
@Composable
fun PlayerScreen(
    url: String,
    title: String,
    isLive: Boolean,
    userAgent: String? = null,
    queue: PlayQueue? = null,
    onClose: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val player = remember { SeamlessPlayer(ctx.applicationContext) }
    DisposableEffect(url) {
        player.play(url, isLive, userAgent)
        onDispose {
            player.stop()
            player.destroy()
        }
    }

    val state by player.state.collectAsState()
    var controls by remember { mutableStateOf(true) }
    var paused by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(100f) }
    var fullscreen by remember { mutableStateOf(false) }
    var fav by remember { mutableStateOf(false) }
    var curTitle by remember { mutableStateOf(title) }
    var curUrl by remember { mutableStateOf(url) }
    val hasQueue = queue != null && !queue.isEmpty
    LaunchedEffect(queue) {
        if (queue != null && queue.current != null) {
            curTitle = queue.current!!.title
            curUrl = queue.current!!.url
            fav = queue.isFavorite(curUrl)
        }
    }

    BackHandler {
        // Como todo reproductor: si los controles están ocultos, BACK los
        // muestra; si ya visibles, sale.
        if (!controls) controls = true else onClose()
    }
    // TV: al mostrar controles, el foco va a pausa para operar con D-pad.
    val pauseFocus = remember { FocusRequester() }
    LaunchedEffect(controls) {
        if (controls) {
            try {
                pauseFocus.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    fun applyFullscreen(fs: Boolean) {
        fullscreen = fs
        controls = true
        activity?.window?.let { w ->
            val c = WindowInsetsControllerCompat(w, w.decorView)
            if (fs) c.hide(WindowInsetsCompat.Type.systemBars())
            else c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Inmersivo al entrar al player (teléfono 14/09): sin esto las barras
    // del sistema achican el surface (2300x954 en vez de 2400x1080) y el
    // video queda "cuadrado" con bandas gruesas. Se restaura al salir.
    // En TV es no-op (no hay barras). El botón ⛶ sigue manual.
    DisposableEffect(Unit) {
        val w = activity?.window
        val c = w?.let { WindowInsetsControllerCompat(it, it.decorView) }
        try {
            c?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c?.hide(WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {
        }
        onDispose {
            try {
                c?.show(WindowInsetsCompat.Type.systemBars())
            } catch (_: Exception) {
            }
        }
    }

    // Auto-hide: timer solo si controles visibles Y reproduciendo. Cuando
    // controls pasa a false el efecto se reinicia pero la condición ya no
    // aplica → no reprograma (bug original); y mostrarlos con un tap/tecla
    // rearma el timer (bug inverso del fix anterior). Pausado = siempre visibles.
    LaunchedEffect(controls, state, paused) {
        if (controls && state is PlayerState.Playing && !paused) {
            delay(5000)
            controls = false
        }
        if (state !is PlayerState.Playing || paused) controls = true
    }

    fun switchTo(ch: tv.danko.app.data.db.ChannelEntity) {
        curUrl = ch.url
        curTitle = ch.title
        paused = false
        player.play(ch.url, true, userAgent)
    }

    fun stepChannel(delta: Int) {
        queue?.step(delta)?.let { switchTo(it) }
    }

    suspend fun toggleFav() {
        if (queue == null) return
        fav = queue.toggleFavorite(curUrl)
    }

    val label = when (val s = state) {
        PlayerState.Idle -> "Detenido"
        PlayerState.Loading -> "Cargando…"
        PlayerState.Playing -> if (isLive) "● EN VIVO" else "Reproduciendo"
        is PlayerState.Reloading -> "Reconectando… (cascada ${s.cascade}, ${s.inSecs}s)"
        is PlayerState.Error -> "Error — reintento ${s.retries} en ${s.inSecs}s"
        is PlayerState.Dead -> "Canal caído (${s.reason}) — recargá la lista (⟳)"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .clickable { controls = !controls }
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && !controls &&
                    ev.key in setOf(
                        Key.DirectionUp, Key.DirectionDown,
                        Key.DirectionLeft, Key.DirectionRight,
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
                    )
                ) {
                    controls = true
                    true
                } else {
                    false
                }
            },
    ) {
        AndroidView(
            factory = { c -> MpvSurfaceView(c, player) },
            modifier = Modifier.fillMaxSize(),
        )
        // La barra SOLO depende de controls (auto-hide real también fuera
        // de fullscreen; antes `|| !fullscreen` la dejaba fija).
        if (controls) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "$curTitle  ·  $label",
                    color = if (state is PlayerState.Playing && isLive) DankoOrange else DankoMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth())
                Column(
                    Modifier.fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hasQueue) {
                            item {
                                Button(
                                    onClick = { stepChannel(-1) },
                                    modifier = Modifier.tvFocusBorder(),
                                ) { Text("⏮") }
                            }
                            item {
                                Button(
                                    onClick = { stepChannel(1) },
                                    modifier = Modifier.tvFocusBorder(),
                                ) { Text("⏭") }
                            }
                        }
                        item {
                            Button(
                                onClick = { paused = player.togglePause() },
                                modifier = Modifier.focusRequester(pauseFocus).tvFocusBorder(),
                            ) { Text(if (paused) "▶" else "⏸") }
                        }
                        item {
                            Button(
                                onClick = { muted = player.toggleMute() },
                                modifier = Modifier.tvFocusBorder(),
                            ) { Text(if (muted) "🔇" else "🔊") }
                        }
                        item {
                            Button(
                                onClick = { applyFullscreen(!fullscreen) },
                                modifier = Modifier.tvFocusBorder(),
                            ) { Text("⛶") }
                        }
                        if (hasQueue) {
                            item {
                                Button(
                                    onClick = { scope.launch { toggleFav() } },
                                    modifier = Modifier.tvFocusBorder(),
                                ) { Text(if (fav) "★" else "☆", color = DankoOrange) }
                            }
                        }
                        item {
                            Button(
                                onClick = {
                                    player.stop()
                                    onClose()
                                },
                                modifier = Modifier.tvFocusBorder(),
                            ) { Text("⏹") }
                        }
                        item {
                            TextButton(onClick = onClose, modifier = Modifier.tvFocusBorder()) { Text("✕ Cerrar") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔊", color = MaterialTheme.colorScheme.onSurface)
                        Slider(
                            value = volume,
                            onValueChange = {
                                volume = it
                                player.setVolume(it.toInt())
                            },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
