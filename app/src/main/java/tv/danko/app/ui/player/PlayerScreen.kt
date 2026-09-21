package tv.danko.app.ui.player

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import tv.danko.app.data.EpgRepository
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
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
    epg: EpgRepository? = null,
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
    // Pistas audio/subs (F6): etiquetas actuales, se ciclan al tocar.
    var audioLabel by remember { mutableStateOf<String?>(null) }
    var subLabel by remember { mutableStateOf<String?>(null) }
    var showAud by remember { mutableStateOf(false) }
    var showSub by remember { mutableStateOf(false) }
    // Cast (F8): diálogo propio + rutas + sesión activa.
    var showCast by remember { mutableStateOf(false) }
    var castRoutes by remember {
        mutableStateOf<List<androidx.mediarouter.media.MediaRouter.RouteInfo>>(emptyList())
    }
    var castActive by remember { mutableStateOf(false) }
    fun refreshCastRoutes() {
        try {
            val router = androidx.mediarouter.media.MediaRouter.getInstance(ctx)
            val cat = com.google.android.gms.cast.CastMediaControlIntent
                .categoryForCast("CC1AD845")
            castRoutes = router.routes.filter {
                try {
                    it.isEnabled && it.supportsControlCategory(cat)
                } catch (_: Exception) {
                    false
                }
            }
        } catch (_: Exception) {
            castRoutes = emptyList()
        }
    }
    // El descubrimiento mDNS es ASÍNCRONO: una foto al abrir no alcanza
    // (bug 21/09: "no hay nada en la red" con la TV anunciando). Callback
    // vivo mientras el diálogo está abierto.
    val castCallback = remember {
        object : androidx.mediarouter.media.MediaRouter.Callback() {
            private fun sync() {
                refreshCastRoutes()
            }
            override fun onRouteAdded(
                router: androidx.mediarouter.media.MediaRouter,
                route: androidx.mediarouter.media.MediaRouter.RouteInfo,
            ) = sync()
            override fun onRouteRemoved(
                router: androidx.mediarouter.media.MediaRouter,
                route: androidx.mediarouter.media.MediaRouter.RouteInfo,
            ) = sync()
            override fun onRouteChanged(
                router: androidx.mediarouter.media.MediaRouter,
                route: androidx.mediarouter.media.MediaRouter.RouteInfo,
            ) = sync()
        }
    }
    DisposableEffect(showCast) {
        var router: androidx.mediarouter.media.MediaRouter? = null
        if (showCast) {
            try {
                router = androidx.mediarouter.media.MediaRouter.getInstance(ctx)
                val selector = androidx.mediarouter.media.MediaRouteSelector.Builder()
                    .addControlCategory(
                        com.google.android.gms.cast.CastMediaControlIntent
                            .categoryForCast("CC1AD845"),
                    )
                    .addControlCategory(
                        androidx.mediarouter.media.MediaControlIntent.CATEGORY_LIVE_VIDEO,
                    )
                    .build()
                router?.addCallback(
                    selector, castCallback,
                    androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY,
                )
                refreshCastRoutes()
            } catch (_: Exception) {
            }
        }
        onDispose {
            try {
                router?.removeCallback(castCallback)
            } catch (_: Exception) {
            }
        }
    }
    // Sleep timer (F7): apaga al vencer. Se cancela solo al salir.
    var sleepUntil by remember { mutableStateOf(0L) }
    var showSleep by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is PlayerState.Playing) {
            // Las pistas (sobre todo subtítulos en mkv por red) pueden llegar
            // DESPUÉS del file-loaded: reintentar diferido (bug 19/09: el
            // botón SUB no aparecía en pelis que sí traen subtítulos).
            repeat(3) { attempt ->
                try {
                    if (player.mediaTracks("audio").size > 1) showAud = true
                    if (player.mediaTracks("subtitle").isNotEmpty()) showSub = true
                    if (showAud && showSub) return@LaunchedEffect
                } catch (_: Exception) {
                }
                if (attempt < 2) kotlinx.coroutines.delay(4000)
            }
        }
    }
    var volume by remember { mutableStateOf(100f) }
    var fullscreen by remember { mutableStateOf(false) }
    var fav by remember { mutableStateOf(false) }
    // EPG ahora/siguiente (F5): se carga por canal, con caché Room 6h.
    var epgNow by remember { mutableStateOf<String?>(null) }
    var epgNext by remember { mutableStateOf<String?>(null) }
    var curTitle by remember { mutableStateOf(title) }
    var curUrl by remember { mutableStateOf(url) }
    LaunchedEffect(curUrl) {
        epgNow = null
        epgNext = null
        try {
            val repo = epg ?: return@LaunchedEffect
            val list = queue?.listName?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
            val (now, next) = repo.nowNext(list, curUrl)
            epgNow = now?.title?.takeIf { it.isNotEmpty() }
            epgNext = next?.title?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
        }
    }
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
            // Feedback INSTANTÁNEO: el transcode tarda segundos y sin esto
            // parece muerto (pedido del usuario 16/09 — "otro golazo").
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    android.widget.Toast.makeText(
                        ctx, "Procesando video…", android.widget.Toast.LENGTH_LONG,
                    ).show()
                } catch (_: Exception) {
                }
            }
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

    LaunchedEffect(sleepUntil) {
        if (sleepUntil > 0) {
            val wait = sleepUntil - android.os.SystemClock.uptimeMillis()
            if (wait > 0) kotlinx.coroutines.delay(wait)
            closePlayer()
        }
    }

    // MediaSession F7 (TV + teléfono): control remoto, auriculares BT y
    // centro multimedia mandan play/pausa/stop. Sin dependencias: plataforma.
    val mediaSession = remember {
        try {
            android.media.session.MediaSession(ctx, "DankoTV").apply {
                setFlags(
                    android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
                )
                setCallback(object : android.media.session.MediaSession.Callback() {
                    override fun onPlay() {
                        player.setPaused(false)
                        scope.launch { paused = false }
                    }
                    override fun onPause() {
                        player.setPaused(true)
                        scope.launch { paused = true }
                    }
                    override fun onStop() {
                        scope.launch { closePlayer() }
                    }
                    override fun onMediaButtonEvent(intent: android.content.Intent): Boolean {
                        val ev = intent.getParcelableExtra(
                            android.content.Intent.EXTRA_KEY_EVENT,
                        ) as? android.view.KeyEvent
                        when (ev?.keyCode) {
                            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                val p = player.togglePause()
                                scope.launch { paused = p }
                                return true
                            }
                            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                player.setPaused(false)
                                scope.launch { paused = false }
                                return true
                            }
                            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                            android.view.KeyEvent.KEYCODE_MEDIA_STOP,
                            -> {
                                player.setPaused(true)
                                scope.launch { paused = true }
                                return true
                            }
                        }
                        return super.onMediaButtonEvent(intent)
                    }
                })
                isActive = true
            }
        } catch (_: Exception) {
            null
        }
    }
    // Estado + título publicados a la sesión (pausa/controles del sistema).
    LaunchedEffect(state, paused, curTitle) {
        val ms = mediaSession ?: return@LaunchedEffect
        try {
            ms.setMetadata(
                android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, curTitle)
                    .build(),
            )
            val st = if (state is PlayerState.Playing && !paused) {
                android.media.session.PlaybackState.STATE_PLAYING
            } else {
                android.media.session.PlaybackState.STATE_PAUSED
            }
            ms.setPlaybackState(
                android.media.session.PlaybackState.Builder()
                    .setState(st, android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .setActions(
                        android.media.session.PlaybackState.ACTION_PLAY or
                            android.media.session.PlaybackState.ACTION_PAUSE or
                            android.media.session.PlaybackState.ACTION_STOP,
                    )
                    .build(),
            )
        } catch (_: Exception) {
        }
    }
    // Cast (F8, solo teléfono): al conectar pasa lo actual al TV y pausa
    // local; al soltar, retoma local. Tipo MIME por extensión.
    if (!isTv) {
        DisposableEffect(curUrl, curTitle, isLive) {
            var manager: com.google.android.gms.cast.framework.SessionManager? = null
            val listener =
                object : com.google.android.gms.cast.framework.SessionManagerListener<com.google.android.gms.cast.framework.Session> {
                    override fun onSessionStarted(
                        session: com.google.android.gms.cast.framework.Session,
                        sessionId: String,
                    ) {
                        val cast = session as? com.google.android.gms.cast.framework.CastSession
                            ?: return
                        try {
                            if (state !is PlayerState.Playing) return
                            player.setPaused(true)
                            scope.launch { paused = true }
                            scope.launch { castActive = true }
                            val lower = curUrl.lowercase()
                            val mime = when {
                                lower.contains(".m3u8") -> "application/x-mpegurl"
                                lower.endsWith(".mp4") -> "video/mp4"
                                lower.endsWith(".mkv") -> "video/x-matroska"
                                lower.endsWith(".ts") -> "video/mp2t"
                                isLive -> "video/mp2t"
                                else -> "video/mp4"
                            }
                            val meta = com.google.android.gms.cast.MediaMetadata(
                                if (isLive) {
                                    com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_TV_SHOW
                                } else {
                                    com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE
                                },
                            )
                            meta.putString(
                                com.google.android.gms.cast.MediaMetadata.KEY_TITLE, curTitle,
                            )
                            val info = com.google.android.gms.cast.MediaInfo.Builder(curUrl)
                                .setStreamType(
                                    if (isLive) {
                                        com.google.android.gms.cast.MediaInfo.STREAM_TYPE_LIVE
                                    } else {
                                        com.google.android.gms.cast.MediaInfo.STREAM_TYPE_BUFFERED
                                    },
                                )
                                .setContentType(mime)
                                .setMetadata(meta)
                                .build()
                            cast.remoteMediaClient?.load(info, true, 0)
                        } catch (_: Exception) {
                        }
                    }
                    override fun onSessionEnded(
                        session: com.google.android.gms.cast.framework.Session,
                        error: Int,
                    ) {
                        try {
                            player.setPaused(false)
                            scope.launch { paused = false }
                            scope.launch { castActive = false }
                        } catch (_: Exception) {
                        }
                    }
                    override fun onSessionStarting(session: com.google.android.gms.cast.framework.Session) {}
                    override fun onSessionStartFailed(session: com.google.android.gms.cast.framework.Session, error: Int) {}
                    override fun onSessionEnding(session: com.google.android.gms.cast.framework.Session) {}
                    override fun onSessionResuming(session: com.google.android.gms.cast.framework.Session, sessionId: String) {}
                    override fun onSessionResumed(session: com.google.android.gms.cast.framework.Session, wasSuspended: Boolean) {}
                    override fun onSessionResumeFailed(session: com.google.android.gms.cast.framework.Session, error: Int) {}
                    override fun onSessionSuspended(session: com.google.android.gms.cast.framework.Session, reason: Int) {}
                }
            try {
                manager = com.google.android.gms.cast.framework.CastContext
                    .getSharedInstance(ctx).sessionManager
                manager?.addSessionManagerListener(listener)
            } catch (_: Exception) {
                manager = null
            }
            onDispose {
                try {
                    manager?.removeSessionManagerListener(listener)
                } catch (_: Exception) {
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaSession?.isActive = false
            } catch (_: Exception) {
            }
            try {
                mediaSession?.release()
            } catch (_: Exception) {
            }
        }
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
                // EPG ahora/siguiente (TV y teléfono): solo si hay datos.
                if (epgNow != null || epgNext != null) {
                    Text(
                        buildString {
                            epgNow?.let { append("Ahora: $it") }
                            if (epgNow != null && epgNext != null) append("  ·  ")
                            epgNext?.let { append("Después: $it") }
                        },
                        color = DankoMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                Column(
                    Modifier.fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(if (isLandscape && !isTv) 4.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Botonera compacta en horizontal del teléfono (los
                    // botones M3 default son altos y tapaban el video).
                    val compactCtl = isLandscape && !isTv
                    val ctlMod =
                        if (compactCtl) Modifier.height(32.dp) else Modifier
                    val ctlPad =
                        if (compactCtl) {
                            PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        } else {
                            ButtonDefaults.ContentPadding
                        }
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(
                            if (compactCtl) 4.dp else 8.dp,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hasQueue) {
                            item {
                                Button(
                                    onClick = { stepChannel(-1) },
                                    modifier = Modifier.tvFocusBorder().then(ctlMod),
                                    contentPadding = ctlPad,
                                ) { PrevIcon() }
                            }
                            item {
                                Button(
                                    onClick = { stepChannel(1) },
                                    modifier = Modifier.tvFocusBorder().then(ctlMod),
                                    contentPadding = ctlPad,
                                ) { NextIcon() }
                            }
                        }
                        item {
                            Button(
                                onClick = { paused = player.togglePause() },
                                modifier = Modifier.focusRequester(pauseFocus).tvFocusBorder().then(ctlMod),
                                contentPadding = ctlPad,
                            ) { if (paused) PlayIcon() else PauseIcon() }
                        }
                        item {
                            Button(
                                onClick = { muted = player.toggleMute() },
                                modifier = Modifier.tvFocusBorder().then(ctlMod),
                                contentPadding = ctlPad,
                            ) { if (muted) MutedIcon() else MuteIcon() }
                        }
                        // Selector de pistas (texto: sin glifos). Solo si hay
                        // más de una pista o subtítulos disponibles.
                        if (showAud) {
                        item {
                            Button(
                                onClick = {
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        audioLabel = player.cycleTrack("audio") ?: audioLabel
                                    }
                                },
                                modifier = Modifier.tvFocusBorder().then(ctlMod),
                                contentPadding = ctlPad,
                            ) { Text("AUD" + (audioLabel?.let { ":$it" } ?: "")) }
                        }
                        }
                        if (showSub) {
                        item {
                            Button(
                                onClick = {
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        subLabel = player.cycleTrack("subtitle") ?: subLabel
                                    }
                                },
                                modifier = Modifier.tvFocusBorder().then(ctlMod),
                                contentPadding = ctlPad,
                            ) { Text("SUB" + (subLabel?.let { ":$it" } ?: "")) }
                        }
                        }
                        item {
                            Button(
                                onClick = { applyFullscreen(!fullscreen) },
                                modifier = Modifier.tvFocusBorder().then(ctlMod),
                                contentPadding = ctlPad,
                            ) { FullscreenIcon() }
                        }
                        // Sleep timer (texto, TV y teléfono).
                        item {
                            Button(
                                onClick = { showSleep = true },
                                modifier = Modifier.tvFocusBorder().then(ctlMod),
                                contentPadding = ctlPad,
                            ) { Text(if (sleepUntil > 0) "Sleep ON" else "Sleep") }
                        }
                        // Zoom llenar/ajustar (SOLO landscape no-TV): recorta
                        // arriba/abajo sin deformar, estilo VLC. En TV no va
                        // (surface 16:9 + embed ignora zoom).
                        if (zoomable) {
                            item {
                                Button(
                                    onClick = { toggleFill() },
                                    modifier = Modifier.tvFocusBorder().then(ctlMod),
                                    contentPadding = ctlPad,
                                ) { if (zoomFactor > 1f) ZoomOutIcon() else ZoomInIcon() }
                            }
                        }
                        // F4-rec (SOLO teléfono): grabar + foto. En TV no aparecen
                        // (el usuario solo comparte desde el teléfono).
                        if (!isTv) {
                            item {
                                Button(
                                    onClick = { toggleRec() },
                                    modifier = Modifier.tvFocusBorder().then(ctlMod),
                                    contentPadding = ctlPad,
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
                                    modifier = Modifier.tvFocusBorder().then(ctlMod),
                                    contentPadding = ctlPad,
                                ) { PhotoIcon() }
                            }
                            // Cast solo-teléfono (F8, caso invitado): botón dibujado
                            // + diálogo propio con la API de rutas. El
                            // MediaRouteButton del sistema REVIENTA al crearse
                            // con nuestro tema (background translucent → crash
                            // al rotar, verificado 21/09). Nada de UI mediarouter.
                            item {
                                Button(
                                    onClick = {
                                        refreshCastRoutes()
                                        showCast = true
                                    },
                                    modifier = Modifier.tvFocusBorder().then(ctlMod),
                                    contentPadding = ctlPad,
                                ) { CastIcon() }
                            }
                        }
                        if (hasQueue) {
                            item {
                                Button(
                                    onClick = { scope.launch { toggleFav() } },
                                    modifier = Modifier.tvFocusBorder().then(ctlMod),
                                    contentPadding = ctlPad,
                                ) {
                                    // Estrella dibujada compartida (ver StarIcon:
                                    // glifos ★/☆ no renderizan en algunos
                                    // teléfonos + naranja-sobre-naranja).
                                    tv.danko.app.ui.StarIcon(fav)
                                }
                            }
                        }
                        item {
                            Button(
                                onClick = { closePlayer() },
                                modifier = Modifier.tvFocusBorder().then(ctlMod),
                                contentPadding = ctlPad,
                            ) { StopIcon() }
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
        // Diálogo Cast propio (sin UI mediarouter: crashea con el tema).
        if (showCast) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showCast = false },
                title = { Text("Enviar a TV", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (castActive) {
                            TextButton(
                                onClick = {
                                    try {
                                        com.google.android.gms.cast.framework.CastContext
                                            .getSharedInstance(ctx).sessionManager
                                            .endCurrentSession(true)
                                    } catch (_: Exception) {
                                    }
                                    showCast = false
                                },
                                modifier = Modifier.fillMaxWidth().tvFocusBorder(),
                            ) { Text("Desconectar", color = Color.White) }
                        }
                        if (castRoutes.isEmpty()) {
                            Text(
                                "Sin TVs en la red (mismo Wi-Fi).",
                                color = Color.White, fontSize = 13.sp,
                            )
                        }
                        castRoutes.forEach { route ->
                            TextButton(
                                onClick = {
                                    try {
                                        androidx.mediarouter.media.MediaRouter
                                            .getInstance(ctx).selectRoute(route)
                                    } catch (_: Exception) {
                                    }
                                    showCast = false
                                },
                                modifier = Modifier.fillMaxWidth().tvFocusBorder(),
                            ) {
                                Text(
                                    (route.name ?: "TV").toString(), color = Color.White,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
            )
        }
        // Diálogo del sleep timer (TV con D-pad: botones con foco).
        if (showSleep) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSleep = false },
                title = { Text("Apagar en…", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0, 15, 30, 60, 90).forEach { min ->
                            TextButton(
                                onClick = {
                                    sleepUntil = if (min == 0) {
                                        0L
                                    } else {
                                        android.os.SystemClock.uptimeMillis() + min * 60_000L
                                    }
                                    showSleep = false
                                },
                                modifier = Modifier.fillMaxWidth().tvFocusBorder(),
                            ) {
                                Text(
                                    if (min == 0) "Desactivado" else "$min minutos",
                                    color = Color.White,
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
            )
        }
    }
}

/**
 * Iconos DIBUJADOS de los controles (Canvas): los glifos/emoji dependen de
 * la fuente del teléfono (el ⏺ salía blanco, el ★/☆ ni aparecía) y el color
 * forzado puede mimetizarse con el botón (estrella naranja sobre naranja).
 * Dibujados con el color de contenido del botón: se ven en todos lados.
 */
@Composable
private fun PrevIcon(tint: Color = LocalContentColor.current) {
    Canvas(Modifier.size(20.dp)) {
        val h = size.height
        val w = size.width
        val barW = 3.dp.toPx()
        drawRect(tint, topLeft = Offset.Zero, size = Size(barW, h))
        val x0 = barW + 1.dp.toPx()
        val path = Path().apply {
            moveTo(x0, 0f)
            lineTo(x0, h)
            lineTo(x0 + (w - x0) / 2f, h / 2f)
            close()
            moveTo(x0 + (w - x0) / 2f, 0f)
            lineTo(x0 + (w - x0) / 2f, h)
            lineTo(w, h / 2f)
            close()
        }
        drawPath(path, tint)
    }
}

@Composable
private fun NextIcon(tint: Color = LocalContentColor.current) {
    Canvas(Modifier.size(20.dp)) {
        val h = size.height
        val w = size.width
        val barW = 3.dp.toPx()
        drawRect(tint, topLeft = Offset(w - barW, 0f), size = Size(barW, h))
        val x1 = w - barW - 1.dp.toPx()
        val path = Path().apply {
            moveTo(x1, 0f)
            lineTo(x1, h)
            lineTo(x1 - (x1) / 2f, h / 2f)
            close()
            moveTo(x1 - x1 / 2f, 0f)
            lineTo(x1 - x1 / 2f, h)
            lineTo(0f, h / 2f)
            close()
        }
        drawPath(path, tint)
    }
}

@Composable
private fun PlayIcon(tint: Color = LocalContentColor.current) {
    Canvas(Modifier.size(20.dp)) {
        val path = Path().apply {
            moveTo(2.dp.toPx(), 0f)
            lineTo(2.dp.toPx(), size.height)
            lineTo(size.width - 2.dp.toPx(), size.height / 2f)
            close()
        }
        drawPath(path, tint)
    }
}

@Composable
private fun PauseIcon(tint: Color = LocalContentColor.current) {
    Canvas(Modifier.size(20.dp)) {
        val bw = 5.dp.toPx()
        drawRect(tint, topLeft = Offset(2.dp.toPx(), 0f), size = Size(bw, size.height))
        drawRect(tint, topLeft = Offset(size.width - 2.dp.toPx() - bw, 0f), size = Size(bw, size.height))
    }
}

@Composable
private fun StopIcon(tint: Color = LocalContentColor.current) {
    Canvas(Modifier.size(20.dp)) {
        val m = 2.dp.toPx()
        drawRect(tint, topLeft = Offset(m, m), size = Size(size.width - 2 * m, size.height - 2 * m))
    }
}

private fun speakerPath(w: Float, h: Float): Path = Path().apply {
    moveTo(0f, h * 0.4f)
    lineTo(w * 0.3f, h * 0.4f)
    lineTo(w * 0.6f, h * 0.1f)
    lineTo(w * 0.6f, h * 0.9f)
    lineTo(w * 0.3f, h * 0.6f)
    lineTo(0f, h * 0.6f)
    close()
}

@Composable
private fun MuteIcon(tint: Color = LocalContentColor.current) {
    Canvas(Modifier.size(20.dp)) {
        drawPath(speakerPath(size.width, size.height), tint)
        val s = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        drawArc(tint, -45f, 90f, false, Offset(size.width * 0.6f, size.height * 0.15f), Size(size.width * 0.35f, size.height * 0.7f), style = s)
        drawArc(tint, -45f, 90f, false, Offset(size.width * 0.75f, 0f), Size(size.width * 0.35f, size.height), style = s)
    }
}

@Composable
private fun MutedIcon(tint: Color = LocalContentColor.current) {
    Canvas(Modifier.size(20.dp)) {
        drawPath(speakerPath(size.width, size.height), tint)
        val s = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        val x0 = size.width * 0.68f
        val x1 = size.width - 1.dp.toPx()
        drawLine(tint, Offset(x0, size.height * 0.35f), Offset(x1, size.height * 0.65f), strokeWidth = s.width, cap = s.cap)
        drawLine(tint, Offset(x0, size.height * 0.65f), Offset(x1, size.height * 0.35f), strokeWidth = s.width, cap = s.cap)
    }
}

@Composable
private fun FullscreenIcon(tint: Color = LocalContentColor.current) {
    Canvas(Modifier.size(20.dp)) {
        val w = 5.dp.toPx()
        val l = 7.dp.toPx()
        val m = 1.dp.toPx()
        val H = size.height
        val W = size.width
        val s = Stroke(width = w, cap = StrokeCap.Round)
        // 4 esquinas en L.
        drawLine(tint, Offset(m, m), Offset(m + l, m), strokeWidth = s.width, cap = s.cap)
        drawLine(tint, Offset(W - m - l, m), Offset(W - m, m), strokeWidth = s.width, cap = s.cap)
        drawLine(tint, Offset(W - m, m), Offset(W - m, m + l), strokeWidth = s.width, cap = s.cap)
        drawLine(tint, Offset(m, H - m - l), Offset(m, H - m), strokeWidth = s.width, cap = s.cap)
        drawLine(tint, Offset(m, H - m), Offset(m + l, H - m), strokeWidth = s.width, cap = s.cap)
        drawLine(tint, Offset(W - m - l, H - m), Offset(W - m, H - m), strokeWidth = s.width, cap = s.cap)
        drawLine(tint, Offset(W - m, H - m), Offset(W - m, H - m - l), strokeWidth = s.width, cap = s.cap)
    }
}

private fun arrowHead(path: Path, tipX: Float, tipY: Float, dx: Float, dy: Float, s: Float) {
    // Cabeza de flecha simple (dos trazos) en la punta (tipX, tipY).
    val a = kotlin.math.atan2(dy, dx)
    val a1 = a + Math.PI * 0.85f
    val a2 = a - Math.PI * 0.85f
    path.moveTo(tipX, tipY)
    path.lineTo((tipX + s * kotlin.math.cos(a1)).toFloat(), (tipY + s * kotlin.math.sin(a1)).toFloat())
    path.moveTo(tipX, tipY)
    path.lineTo((tipX + s * kotlin.math.cos(a2)).toFloat(), (tipY + s * kotlin.math.sin(a2)).toFloat())
}

@Composable
private fun ZoomInIcon(tint: Color = LocalContentColor.current) {
    // Expandir: 4 flechas hacia afuera.
    Canvas(Modifier.size(20.dp)) {
        val m = 4.dp.toPx()
        val c = 8.dp.toPx()
        val W = size.width
        val H = size.height
        val s = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        val pts = arrayOf(
            floatArrayOf(c, c, m, m),
            floatArrayOf(W - c, c, W - m, m),
            floatArrayOf(c, H - c, m, H - m),
            floatArrayOf(W - c, H - c, W - m, H - m),
        )
        for (p in pts) {
            drawLine(tint, Offset(p[0], p[1]), Offset(p[2], p[3]), strokeWidth = s.width, cap = s.cap)
            val hp = Path()
            arrowHead(hp, p[2], p[3], p[2] - p[0], p[3] - p[1], 4.dp.toPx())
            drawPath(hp, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = s.width, cap = s.cap))
        }
    }
}

@Composable
private fun ZoomOutIcon(tint: Color = LocalContentColor.current) {
    // Comprimir: 4 flechas hacia adentro.
    Canvas(Modifier.size(20.dp)) {
        val m = 4.dp.toPx()
        val c = 8.dp.toPx()
        val W = size.width
        val H = size.height
        val s = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        val pts = arrayOf(
            floatArrayOf(m, m, c, c),
            floatArrayOf(W - m, m, W - c, c),
            floatArrayOf(m, H - m, c, H - c),
            floatArrayOf(W - m, H - m, W - c, H - c),
        )
        for (p in pts) {
            drawLine(tint, Offset(p[0], p[1]), Offset(p[2], p[3]), strokeWidth = s.width, cap = s.cap)
            val hp = Path()
            arrowHead(hp, p[2], p[3], p[2] - p[0], p[3] - p[1], 4.dp.toPx())
            drawPath(hp, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = s.width, cap = s.cap))
        }
    }
}

@Composable
private fun PhotoIcon(tint: Color = LocalContentColor.current) {
    Canvas(Modifier.size(20.dp)) {
        val W = size.width
        val H = size.height
        val s = Stroke(width = 2.dp.toPx())
        // Cuerpo.
        drawRect(tint, Offset(1.dp.toPx(), H * 0.28f), Size(W - 2.dp.toPx(), H * 0.62f), style = s)
        // Visor.
        drawRect(tint, Offset(W * 0.32f, H * 0.12f), Size(W * 0.2f, H * 0.16f), style = s)
        // Lente.
        drawCircle(tint, radius = H * 0.2f, center = Offset(W / 2f, H * 0.59f), style = s)
        drawCircle(tint, radius = H * 0.07f, center = Offset(W / 2f, H * 0.59f))
    }
}

/**
 * Icono Cast dibujado (pantalla + ondas): el MediaRouteButton del sistema
 * revienta con nuestro tema (crash al rotar 21/09) y los glifos no son
 * confiables entre teléfonos.
 */
@Composable
private fun CastIcon(tint: Color = LocalContentColor.current) {
    Canvas(Modifier.size(20.dp)) {
        val W = size.width
        val H = size.height
        val s = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        // Pantalla.
        drawRect(
            tint, Offset(1.dp.toPx(), 1.dp.toPx()),
            Size(W - 2.dp.toPx(), H - 7.dp.toPx()), style = s,
        )
        // Ondas abajo-izquierda + punto.
        val cx = 5.dp.toPx()
        val cy = H - 4.dp.toPx()
        drawArc(tint, -45f, 90f, false, Offset(cx - 5.dp.toPx(), cy - 5.dp.toPx()), Size(10.dp.toPx(), 10.dp.toPx()), style = s)
        drawArc(tint, -45f, 90f, false, Offset(cx - 8.dp.toPx(), cy - 8.dp.toPx()), Size(16.dp.toPx(), 16.dp.toPx()), style = s)
        drawCircle(tint, radius = 1.6.dp.toPx(), center = Offset(cx, cy))
    }
}
