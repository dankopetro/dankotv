package tv.danko.app.ui.player

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.danko.app.player.MpvSurfaceView
import tv.danko.app.player.PlayerState
import tv.danko.app.player.SeamlessEngine
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
    // Zoom estilo VLC (SOLO landscape no-TV, ver abajo): factor 1 = ajustar,
    // >1 = llenar recortando. El pan va en unidades mpv [-1, 1].
    var zoomFactor by remember { mutableStateOf(1f) }
    var panX by remember { mutableStateOf(0.0) }
    var panY by remember { mutableStateOf(0.0) }
    val videoAspect by player.videoAspect.collectAsState()
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTv = remember {
        try {
            val um = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            um?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        } catch (_: Exception) {
            false
        }
    }
    // En TV el surface ya es 16:9 (nada que llenar) y embed ignora zoom/pan.
    // En portrait se ve bien (pedido del usuario): zoom solo en landscape.
    val zoomable = isLandscape && !isTv

    fun applyZoom(factor: Float, x: Double = panX, y: Double = panY) {
        zoomFactor = factor.coerceIn(1f, 3f)
        panX = SeamlessEngine.clampPan(x)
        panY = SeamlessEngine.clampPan(y)
        player.setZoomProp((zoomFactor - 1).toDouble())
        player.setPanProp(panX, panY)
    }

    fun resetZoom() {
        zoomFactor = 1f
        panX = 0.0
        panY = 0.0
        player.resetZoomGeometry()
    }

    fun toggleFill() {
        if (zoomFactor > 1f) {
            resetZoom()
        } else {
            val sa = if (player.surfaceH > 0) {
                player.surfaceW.toDouble() / player.surfaceH
            } else {
                16.0 / 9.0
            }
            val fill = SeamlessEngine.fillZoomProp(sa, videoAspect).toFloat()
            applyZoom(1f + fill)
        }
    }

    // Al cambiar de canal o de orientación, volver a ajustar (predecible).
    LaunchedEffect(curUrl, isLandscape) {
        resetZoom()
    }
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

    // Al volver de background el surface se destruyó y el VO puede quedar
    // negro (el audio sigue). Recarga determinista del canal actual
    // (en live reengancha el borde en ~2s). Solo tras haber ido a fondo.
    val lifecycleOwner = LocalLifecycleOwner.current
    var wentBackground by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                wentBackground = true
            } else if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME && wentBackground) {
                wentBackground = false
                val s = player.state.value
                if (s is PlayerState.Playing || s is PlayerState.Reloading) {
                    player.play(curUrl, isLive, userAgent)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Inmersivo al entrar al player (teléfono 14/09): sin esto las barras
    // del sistema achican el surface (2300x954 en vez de 2400x1080) y el
    // video queda "cuadrado" con bandas gruesas. Se restaura al salir.
    // En TV es no-op (no hay barras). El botón ⛶ sigue manual.
    // + Dibujo bajo el cutout (16/09, pedido del usuario): en landscape la
    // franja de la cámara quedaba sin llenar. SHORT_EDGES extiende la
    // ventana a los bordes cortos (donde está la cámara en landscape).
    // La cámara tapa unos px de video (aceptado por el usuario).
    // + decorFitsSystemWindows(false): sin esto las VISTAS siguen insetadas
    // (la franja grisácea era el windowBackground #141414, no video).
    DisposableEffect(Unit) {
        val w = activity?.window
        val c = w?.let { WindowInsetsControllerCompat(it, it.decorView) }
        var prevCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        try {
            c?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c?.hide(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT >= 28) {
                w?.let {
                    prevCutoutMode = it.attributes.layoutInDisplayCutoutMode
                    it.attributes = it.attributes.apply {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
            }
            // Solo el player lo cambia en la app → el default (true) vale.
            w?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        } catch (_: Exception) {
        }
        onDispose {
            try {
                c?.show(WindowInsetsCompat.Type.systemBars())
                if (Build.VERSION.SDK_INT >= 28) {
                    w?.let {
                        it.attributes = it.attributes.apply {
                            layoutInDisplayCutoutMode = prevCutoutMode
                        }
                    }
                }
                w?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
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

    // --- F4-rec (solo teléfono): grabar + foto + compartir ---
    val recording by player.recording.collectAsState()
    var recSecs by remember { mutableStateOf(0) }
    LaunchedEffect(recording) {
        if (recording) {
            val t0 = System.currentTimeMillis()
            while (true) {
                kotlinx.coroutines.delay(1000)
                recSecs = ((System.currentTimeMillis() - t0) / 1000).toInt()
            }
        } else {
            recSecs = 0
        }
    }

    fun fmtRec(s: Int) = "%02d:%02d".format(s / 60, s % 60)

    /** Detiene, remuxea a .mp4 válido, guarda en galería y abre compartir. */
    fun stopRecAndShare() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val uris: List<android.net.Uri> = try {
            val files = player.stopRecording()
            ShareUtils.recLog(ctx, "REC flow: ${files.size} segmentos")
            files.mapNotNull { f ->
                try {
                    val src = java.io.File(f)
                    ShareUtils.recLog(ctx, "REC flow: seg ${src.name} size=${src.length()}")
                    if (!src.exists() || src.length() == 0L) {
                        src.delete()
                        ShareUtils.recLog(ctx, "REC flow: segmento vacío, descartado")
                        return@mapNotNull null
                    }
                    // .ts del dump → .mp4 transcodificado (H264+AAC, timestamps
                    // limpios del encoder, duración exacta por conteo de frames).
                    // Trim al span real (un solo segmento): el dump trae
                    // ~45s previos al REC que confunden (59s por 10s).
                    val spanUs = if (files.size == 1) {
                        player.recSpanMs * 1000
                    } else {
                        0L
                    }
                    val mp4 = java.io.File(src.parent, src.nameWithoutExtension + ".mp4")
                    val ok = ClipTranscoder.transcode(src, mp4, spanUs, player.videoFps) {
                        ShareUtils.recLog(ctx, it)
                    }
                    ShareUtils.recLog(ctx, "REC flow: transcode ok=$ok mp4=${mp4.length()}")
                    src.delete()
                    if (!ok || !ShareUtils.hasAvTracks(mp4)) {
                        ShareUtils.recLog(ctx, "REC flow: transcode inválido, se descarta")
                        try {
                            mp4.delete()
                        } catch (_: Exception) {
                        }
                        return@mapNotNull null
                    }
                    val uri = ShareUtils.saveToGallery(ctx, mp4, mp4.name, "video")
                    ShareUtils.recLog(ctx, "REC flow: gallery uri=$uri")
                    mp4.delete()
                    uri
                } catch (e: Exception) {
                    ShareUtils.recLog(ctx, "REC flow: excepción $e")
                    null
                }
            }
            } catch (e: Exception) {
                // Nunca morir en silencio: cualquier fallo inesperado avisa.
                ShareUtils.recLog(ctx, "REC flow: fallo inesperado $e")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    try {
                        android.widget.Toast.makeText(
                            ctx, "Falló la grabación ($e)", android.widget.Toast.LENGTH_LONG,
                        ).show()
                    } catch (_: Exception) {
                    }
                }
                return@launch
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    if (uris.isEmpty()) {
                        android.widget.Toast.makeText(
                            ctx, "Falló la grabación (sin datos)", android.widget.Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        ShareUtils.shareUris(ctx, uris, video = true)
                    }
                } catch (e: Exception) {
                    ShareUtils.recLog(ctx, "REC flow: share falló $e")
                }
            }
        }
    }

    fun toggleRec() {
        if (player.recording.value) {
            stopRecAndShare()
            return
        }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dir = java.io.File(ctx.cacheDir, "DankoTV")
                val base = ShareUtils.baseName(curTitle)
                player.startRecording(dir, base)
            } catch (_: Exception) {
            }
        }
    }

    /** Foto del frame + compartir. */
    fun snapAndShare() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var uri: android.net.Uri? = null
            try {
                val dir = java.io.File(ctx.cacheDir, "DankoTV")
                if (!dir.exists()) dir.mkdirs()
                val base = ShareUtils.baseName(curTitle)
                val tmp = java.io.File(dir, "$base.jpg")
                if (player.screenshot(tmp.absolutePath) && tmp.length() > 0) {
                    uri = ShareUtils.saveToGallery(ctx, tmp, "$base.jpg", "image")
                    tmp.delete()
                }
            } catch (_: Exception) {
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                uri?.let { ShareUtils.shareUris(ctx, listOf(it), video = false) }
            }
        }
    }

    /** Cerrar el player (si grababa, primero guarda+comparte). */
    fun closePlayer() {
        if (player.recording.value) stopRecAndShare()
        player.stop()
        onClose()
    }

    val label = when (val s = state) {
        PlayerState.Idle -> "Detenido"
        PlayerState.Loading -> "Cargando…"
        PlayerState.Playing -> if (isLive) "● EN VIVO" else "Reproduciendo"
        is PlayerState.Reloading -> "Reconectando… (cascada ${s.cascade}, ${s.inSecs}s)"
        is PlayerState.Error -> "Error — reintento ${s.retries} en ${s.inSecs}s"
        is PlayerState.Dead -> "Canal caído (${s.reason}) — recargá la lista (⟳)"
    }

    // Pellizco para zoom (VLC): convive con el tap (multi-touch no es tap).
    // Arrastrar pantalla completa = un pantallazo de pan al zoom actual.
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        if (!zoomable) return@rememberTransformableState
        val nz = (zoomFactor * zoomChange).coerceIn(1f, 3f)
        val sw = (player.surfaceW * nz).takeIf { it > 0 } ?: 1f
        val sh = (player.surfaceH * nz).takeIf { it > 0 } ?: 1f
        applyZoom(
            nz,
            panX + panChange.x / sw * 2,
            panY + panChange.y / sh * 2,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .then(if (zoomable) Modifier.transformable(transformState) else Modifier)
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
                    (if (recording) "● REC ${fmtRec(recSecs)} · " else "") +
                        "$curTitle  ·  $label",
                    color = if (state is PlayerState.Playing && isLive) DankoOrange else DankoMuted,
                    fontSize = 13.sp,
                    // En landscape el título esquiva la cámara (cutout lateral).
                    modifier = Modifier.padding(12.dp)
                        .then(
                            if (isLandscape) {
                                Modifier.windowInsetsPadding(WindowInsets.displayCutout)
                            } else {
                                Modifier
                            },
                        ),
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
                        // Zoom llenar/ajustar (SOLO landscape no-TV): recorta
                        // arriba/abajo sin deformar, estilo VLC. En TV no va
                        // (surface 16:9 + embed ignora zoom).
                        if (zoomable) {
                            item {
                                Button(
                                    onClick = { toggleFill() },
                                    modifier = Modifier.tvFocusBorder(),
                                ) { Text(if (zoomFactor > 1f) "⊡" else "⤢") }
                            }
                        }
                        // F4-rec (SOLO teléfono): grabar + foto. En TV no aparecen
                        // (el usuario solo comparte desde el teléfono).
                        if (!isTv) {
                            item {
                                Button(
                                    onClick = { toggleRec() },
                                    modifier = Modifier.tvFocusBorder(),
                                ) {
                                    // Punto/cuadrado DIBUJADOS en rojo puro: el glifo
                                    // ⏺ (U+23FA) lo dibuja la fuente emoji en blanco
                                    // e IGNORA el color del Text (16/09).
                                    Box(
                                        Modifier.size(18.dp).background(
                                            Color.Red,
                                            if (recording) RectangleShape else CircleShape,
                                        ),
                                    )
                                }
                            }
                            item {
                                Button(
                                    onClick = { snapAndShare() },
                                    modifier = Modifier.tvFocusBorder(),
                                ) { Text("📷") }
                            }
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
                                onClick = { closePlayer() },
                                modifier = Modifier.tvFocusBorder(),
                            ) { Text("⏹") }
                        }
                        item {
                            TextButton(onClick = { closePlayer() }, modifier = Modifier.tvFocusBorder()) { Text("✕ Cerrar") }
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
