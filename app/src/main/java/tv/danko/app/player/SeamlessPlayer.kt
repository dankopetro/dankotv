package tv.danko.app.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tv.danko.app.ui.player.ShareUtils

/** IDs estables del ABI libmpv (client.h): no cambian entre versiones. */
private const val MPV_EVENT_END_FILE = 7
private const val MPV_EVENT_FILE_LOADED = 8
private const val MPV_FORMAT_FLAG = 3

/** SurfaceView mínimo: el ciclo de vida del surface lo maneja el player. */
class MpvSurfaceView @JvmOverloads constructor(
    context: Context,
    private val player: SeamlessPlayer? = null,
) : SurfaceView(context) {
    init {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                player?.attachSurface(h.surface)
            }

            // mpv cachea el tamaño de ventana del primer surface (14/09:
            // frame viejo estirado, aspect 1.571 en vez de 16:9). El fix
            // oficial (BaseMPVView de mpv-android): avisarle el tamaño por
            // propiedad en cada changed — dispara VOCTRL_EXTERNAL_RESIZE
            // sin re-iniciar nada. NO detach/attach (corrompe el VO en este
            // build) ni setProperty("vo") (no-op con el mismo valor).
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {
                Log.d("SeamlessPlayer", "surfaceChanged ${w}x$h2 fmt=$f")
                player?.setSurfaceSize(w, h2)
            }

            override fun surfaceDestroyed(h: SurfaceHolder) {
                player?.detachSurface()
            }
        })
    }
}

sealed interface PlayerState {
    data object Idle : PlayerState
    data object Loading : PlayerState
    data object Playing : PlayerState
    data class Reloading(val cascade: Int, val inSecs: Long) : PlayerState
    data class Error(val retries: Int, val inSecs: Long) : PlayerState

    /** Stream muerto sin reintentos (401/403/404): recargar la lista (⟳). */
    data class Dead(val reason: String) : PlayerState
}

/**
 * Reproductor seamless sobre libmpv embebido. Port de SeamlessPlayer en
 * dankotv/player.py: UNA instancia mpv reutilizada, live con keep-open +
 * observador eof-reached + backoff en cascada.
 *
 * Los callbacks de libmpv llegan en su hilo de eventos: TODO lo que sigue se
 * reenvía al hilo principal (la lección del cartel de Linux, aplicada acá).
 */
class SeamlessPlayer(
    private val appContext: Context,
    private val cacheSecs: Int = 45,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val tag = "SeamlessPlayer"
    private val mpv = MPV()

    var currentUrl = ""
        private set
    var isLive = true
        private set

    private var armed = true
    private var cascade = 0
    private var lastEofMs = 0L
    private var reloading = false
    private var errRetries = 0
    private var surfaceAttached = false
    private var pendingLoad = false

    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val state: StateFlow<PlayerState> = _state

    /** Hilos IO del player (dump-cache bloqueante, etc). Se cancela en destroy(). */
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val observer = object : MPV.EventObserver {
        override fun eventProperty(name: String) {
            if (name == "eof-reached") post { onEofProp(mpv.getPropertyBoolean(name)) }
        }

        override fun eventProperty(name: String, value: Long) = Unit
        override fun eventProperty(name: String, value: Boolean) {
            if (name == "eof-reached") post { onEofProp(value) }
        }

        override fun eventProperty(name: String, value: String) = Unit
        override fun eventProperty(name: String, value: Double) = Unit
        override fun eventProperty(name: String, value: MPVNode) = Unit

        override fun event(eventId: Int, data: MPVNode) {
            post { onEvent(eventId, data) }
        }
    }

    /**
     * Puente de logs NATIVOS de mpv a logcat (tag "mpv"). Sin esto los
     * errores de VO/hwdec son invisibles. Niveles mpv: 10 fatal, 20 error,
     * 30 warn, 40 info, 50+ v/debug/trace.
     */
    private val logObserver = object : MPV.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            val t = text.trimEnd()
            if (t.isEmpty()) return
            when {
                level <= 20 -> Log.e("mpv", "[$prefix] $t")
                level <= 30 -> Log.w("mpv", "[$prefix] $t")
                level <= 40 -> Log.i("mpv", "[$prefix] $t")
                else -> Log.d("mpv", "[$prefix] $t")
            }
        }
    }

    init {
        mpv.create(appContext)
        installCaCert(appContext) // sin esto falla todo https (verificado en device)
        // Base = _base_options() de Linux menos lo exclusivo de escritorio
        // (wid/force-window/input-vo-keyboard/script-opts).
        // OJO: Linux usa hwdec=no (encoders rotos hacen seeks raros en software),
        // pero en Android TV el decode por software satura UN core al 97% y
        // congela la UI (verificado en device 13/09: control remoto lento).
        // hwdec=auto → mediacodec. Re-probar canal de encoder roto con el usuario.
        mpv.setOptionString("osc", "no")
        // Salida de video por dispositivo (verificado 14-16/09):
        // - TV (UiMode TV): mediacodec_embed + hwdec=mediacodec = overlay
        //   directo del decoder al display, CERO copy y CERO GPU. En el
        //   Realtek 32 bits/GLES2, vo=gpu+copy perdía la mitad de frames
        //   (14-23/s) por composición 100% GPU sin overlays HWC. OJO embed
        //   no tiene OSD/subs mpv (la UI Compose sigue andando: es otra capa).
        // - Teléfono/tablet: vo=gpu + hwdec=auto (copy): fluido (framedrop 0)
        //   y conserva OSD/subs para F6+.
        val isTv = try {
            val um = appContext.getSystemService(Context.UI_MODE_SERVICE)
                as? android.app.UiModeManager
            um?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        } catch (_: Exception) {
            false
        }
        // Chipset-aware: Exynos y MediaTek (Mali GPU) dan video negro con vo=gpu.
        // Snapdragon (Adreno) sí anda con vo=gpu, pero mediacodec_embed es seguro
        // en todos los teléfonos (overlay directo del decoder, sin GPU) y es lo
        // que ya funcionó en el Galaxy A31. Preferimos que se vea el video antes
        // que conservar los subs mpv en teléfonos.
        val isExynos = isExynosDevice()
        if (isTv || isExynos) {
            mpv.setOptionString("vo", "mediacodec_embed")
            mpv.setOptionString("hwdec", "mediacodec")
        } else {
            mpv.setOptionString("vo", "mediacodec_embed")
            mpv.setOptionString("hwdec", "mediacodec")
        }
        mpv.setOptionString("ytdl", "no")
        mpv.setOptionString("title", "Danko TV")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("ao", "audiotrack")
        mpv.setOptionString("loglevel", "info")
        // Geometría: mpv no sigue al surface (fix en MpvSurfaceView) y por
        // defecto YA cuida el aspect, pero se deja explícito.
        mpv.setOptionString("keepaspect", "yes")
        // Tasa real del display (este build no la reporta: dispfps=null).
        // Hoy solo alimenta telemetría (STATS); el sync es audio.
        val dispFps = try {
            val dm = appContext.getSystemService(Context.DISPLAY_SERVICE)
                as? android.hardware.display.DisplayManager
            dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
        } catch (_: Exception) {
            60f
        }
        mpv.setOptionString("override-display-fps", dispFps.toString())
        // audio-sync SIEMPRE (default): con embed+display-resample el
        // movimiento salía APURADO en la TV (16/09, verificado x usuario).
        mpv.setOptionString("video-sync", "audio")
        // Caché 45s: con defaults mpv pierde MÁS (22/s vs 16/s en vo=gpu).
        if (cacheSecs > 0) {
            mpv.setOptionString("demuxer-readahead-secs", cacheSecs.toString())
            mpv.setOptionString("cache-secs", cacheSecs.toString())
        }
        mpv.setOptionString("loop", "no")
        mpv.observeProperty("eof-reached", MPV_FORMAT_FLAG)
        mpv.addObserver(observer)
        mpv.addLogObserver(logObserver)
        mpv.init()
    }

    /** Copia el bundle CA del AAR a cache y se lo pasa a mpv (TLS). */
    private fun installCaCert(appContext: Context) {
        try {
            val out = java.io.File(appContext.cacheDir, "mpv-cacert.pem")
            if (!out.exists()) {
                appContext.assets.open("cacert.pem").use { inp ->
                    out.outputStream().use { inp.copyTo(it) }
                }
            }
            mpv.setOptionString("tls-cert-file", out.absolutePath)
        } catch (e: Exception) {
            Log.w(tag, "cacert install failed: $e")
        }
    }

    /** Detecta SoC Exynos (Samsung). Se usa para diagnóstico en pantalla. */
    private fun isExynosDevice(): Boolean {
        return try {
            val soc = if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.os.Build.SOC_MODEL
            } else {
                ""
            }
            val hw = android.os.Build.HARDWARE.lowercase()
            val board = android.os.Build.BOARD.lowercase()
            soc.contains("exynos", ignoreCase = true) ||
                hw.contains("exynos") ||
                board.contains("exynos")
        } catch (_: Exception) {
            false
        }
    }

    fun attachSurface(surface: Surface) {
        try {
            mpv.attachSurface(surface)
            surfaceAttached = true
            // Si había un play/recarga esperando surface, dispararlo ahora.
            if (pendingLoad && armed && currentUrl.isNotEmpty()) {
                pendingLoad = false
                doLoad()
            }
        } catch (e: Exception) {
            Log.w(tag, "attachSurface failed: $e")
        }
    }

    fun detachSurface() {
        surfaceAttached = false
        try {
            mpv.detachSurface()
        } catch (_: Exception) {
        }
    }

    /**
     * Informa a mpv el tamaño real del surface (rotación/resize). Ver
     * MpvSurfaceView.surfaceChanged: formato "${w}x$h" como el oficial.
     */
    fun setSurfaceSize(w: Int, h: Int) {
        surfaceW = w
        surfaceH = h
        try {
            mpv.setPropertyString("android-surface-size", "${w}x$h")
        } catch (e: Exception) {
            Log.w(tag, "setSurfaceSize failed: $e")
        }
    }

    /** Tamaño del surface (para el zoom fill) + aspect del video actual. */
    var surfaceW = 0
        private set
    var surfaceH = 0
        private set
    private val _videoAspect = MutableStateFlow(16.0 / 9.0)
    val videoAspect: StateFlow<Double> = _videoAspect

    /** Dimensiones reales del video (para parchar headers mp4). */
    var videoW = 0
        private set
    var videoH = 0
        private set

    /** fps nominal del stream (para sintetizar timestamps; default 30). */
    var videoFps = 30.0
        private set

    /** Zoom estilo VLC Fill (preserva aspect, recorta). 0 = ajustar. */
    fun setZoomProp(zoom: Double) {
        try {
            mpv.setPropertyDouble("video-zoom", zoom.coerceIn(0.0, 2.0))
        } catch (e: Exception) {
            Log.w(tag, "setZoomProp failed: $e")
        }
    }

    /** Pan mientras hay zoom (siempre en [-1, 1]). */
    fun setPanProp(x: Double, y: Double) {
        try {
            mpv.setPropertyDouble("video-pan-x", SeamlessEngine.clampPan(x))
            mpv.setPropertyDouble("video-pan-y", SeamlessEngine.clampPan(y))
        } catch (e: Exception) {
            Log.w(tag, "setPanProp failed: $e")
        }
    }

    /** Vuelve a ajustar (zoom 0 + pan centrado). */
    fun resetZoomGeometry() = setPanProp(0.0, 0.0).also { setZoomProp(0.0) }

    fun play(url: String, live: Boolean = true, userAgent: String? = null) {
        currentUrl = url
        isLive = live
        armed = true
        cascade = 0
        _state.value = PlayerState.Loading
        // UA: SOLO si la playlist lo exige explícito (Kodi url-commands, F6).
        // Default = no setear NADA: mpv usa "libmpv x.y" que los paneles aceptan
        // (bug 13/09: UA de descarga colado aquí → 401 en todos los streams).
        // Si un play anterior dejó uno seteado, resetear al default de mpv.
        try {
            if (userAgent != null) mpv.setOptionString("user-agent", userAgent)
            else mpv.setOptionString("user-agent", "libmpv")
        } catch (_: Exception) {
        }
        if (live) {
            mpv.setOptionString("keep-open", "yes")
            mpv.setOptionString("keep-open-pause", "no")
            mpv.setOptionString("loop-playlist", "no")
            mpv.setOptionString("force-seekable", "no")
        } else {
            mpv.setOptionString("keep-open", "no")
            mpv.setOptionString("force-seekable", "yes")
            mpv.setOptionString("loop-playlist", "no")
        }
        Log.i(tag, "Playing ${if (live) "LIVE" else "VOD"}: ${url.take(80)}")
        if (surfaceAttached) {
            doLoad()
        } else {
            // El surface aún no existe: mpv fallaría con "Missing surface pointer".
            // Se carga al llegar surfaceCreated (patrón mpv-android).
            pendingLoad = true
        }
    }

    private fun doLoad() {
        mpv.command("loadfile", currentUrl, "replace")
    }

    fun stop() {        armed = false
        reloading = false
        pendingLoad = false
        main.removeCallbacksAndMessages(null)
        try {
            mpv.command("dump-cache", "0", "no", "") // por si grababa
        } catch (_: Exception) {
        }
        _recording.value = false
        try {
            mpv.setOptionString("keep-open", "no")
        } catch (_: Exception) {
        }
        try {
            mpv.command("stop")
        } catch (e: Exception) {
            Log.w(tag, "stop failed: $e")
        }
        _state.value = PlayerState.Idle
    }

    fun togglePause(): Boolean {
        val p = try {
            !(mpv.getPropertyBoolean("pause") ?: false)
        } catch (_: Exception) {
            true
        }
        try {
            mpv.setPropertyBoolean("pause", p)
        } catch (_: Exception) {
        }
        return p
    }

    /** Pausa explícita sin preguntar estado (MediaSession/botones físicos). */
    fun setPaused(p: Boolean) {
        try {
            if ((mpv.getPropertyBoolean("pause") ?: false) != p) {
                mpv.setPropertyBoolean("pause", p)
            }
        } catch (_: Exception) {
        }
    }

    fun setVolume(v: Int) {
        try {
            mpv.setPropertyDouble("volume", v.coerceIn(0, 100).toDouble())
        } catch (_: Exception) {
        }
    }

    // --- F4-rec: grabación del stream + captura (solo teléfono en UI) ---

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording
    private var recBase = ""
    private var recSeg = 0
    private val recFiles = mutableListOf<String>()

    /** Pared press→stop de la grabación (para recortar el pre-roll). */
    var recSpanMs = 0L
        private set
    private var recStartWallMs = 0L

    /**
     * Empieza a grabar el stream tal cual llega (sin re-encode) a dir/base_p0.mp4.
     * Usa dump-cache CONTINUO (start=tpos actual): stream-record pierde ~97% de
     * paquetes en este build (verificado 16/09). Se graba .mp4 DIRECTO porque
     * el remux .ts→.mp4 con MediaMuxer falla con estos timestamps ("muxer would
     * have stopped already" aun con 3603 samples OK). El comando dump-cache
     * BLOQUEA hasta que se detiene → corre en hilo IO propio.
     * Devuelve la ruta o null si mpv no pudo.
     */
    fun startRecording(dir: java.io.File, base: String): String? {
        try {
            if (!dir.exists()) dir.mkdirs()
            // Limpieza de temporales viejos (una semana).
            try {
                val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                dir.listFiles()?.forEach {
                    if ((it.name.startsWith("rec_") || it.name.startsWith("DankoTV_")) &&
                        it.lastModified() < weekAgo
                    ) {
                        it.delete()
                    }
                }
            } catch (_: Exception) {
            }
            recBase = base
            recSeg = 0
            recFiles.clear()
            recStartWallMs = System.currentTimeMillis()
            recSpanMs = 0L
            val path = java.io.File(dir, "${base}_p0.ts").absolutePath
            val tpos = try {
                mpv.getPropertyDouble("time-pos") ?: 0.0
            } catch (_: Exception) {
                0.0
            }
            val start = maxOf(0.0, tpos).toString()
            ioScope.launch {
                try {
                    mpv.command("dump-cache", start, "no", path)
                } catch (e: Exception) {
                    Log.w(tag, "REC dump failed: $e")
                }
            }
            recFiles.add(path)
            _recording.value = true
            ShareUtils.recLog(appContext, "REC start $path tpos=$start")
            return path
        } catch (e: Exception) {
            Log.w(tag, "REC start failed: $e")
            return null
        }
    }

    /** Detiene la grabación y devuelve los archivos (segmentos si hubo reloads). */
    fun stopRecording(): List<String> {
        if (!_recording.value) return recFiles.toList()
        recSpanMs = System.currentTimeMillis() - recStartWallMs
        try {
            // Filename vacío = detener dump en curso (documentado).
            mpv.command("dump-cache", "0", "no", "")
        } catch (e: Exception) {
            Log.w(tag, "REC stop failed: $e")
        }
        _recording.value = false
        // Esperar flush: el archivo deja de crecer.
        try {
            var last = -1L
            repeat(6) {
                Thread.sleep(500)
                val sz = recFiles.mapNotNull {
                    java.io.File(it).takeIf { f -> f.exists() }?.length()
                }.sum()
                if (sz == last && sz >= 0) return@repeat
                last = sz
            }
        } catch (_: Exception) {
        }
        ShareUtils.recLog(appContext, "REC stop segs=${recFiles.size} spanMs=$recSpanMs")
        Log.i(tag, "REC stop (${recFiles.size} segs)")
        return recFiles.toList()
    }

    /** Nuevo segmento (misma grabación) tras un reload seamless. */
    private fun rollRecording() {
        if (!_recording.value || recBase.isEmpty()) return
        try {
            // Cerrar dump actual y abrir otro en el segmento nuevo.
            try {
                mpv.command("dump-cache", "0", "no", "")
            } catch (_: Exception) {
            }
            recSeg += 1
            val dir = java.io.File(recFiles.last()).parentFile ?: return
            val path = java.io.File(dir, "${recBase}_p$recSeg.ts").absolutePath
            val tpos = try {
                mpv.getPropertyDouble("time-pos") ?: 0.0
            } catch (_: Exception) {
                0.0
            }
            val start = maxOf(0.0, tpos).toString()
            ioScope.launch {
                try {
                    mpv.command("dump-cache", start, "no", path)
                } catch (e: Exception) {
                    Log.w(tag, "REC roll dump failed: $e")
                }
            }
            recFiles.add(path)
            Log.i(tag, "REC roll $path")
        } catch (e: Exception) {
            Log.w(tag, "REC roll failed: $e")
        }
    }

    /** Foto del frame actual a path (jpg). */
    fun screenshot(path: String): Boolean = try {
        mpv.command("screenshot-to-file", path)
        java.io.File(path).exists()
    } catch (e: Exception) {
        Log.w(tag, "screenshot failed: $e")
        false
    }

    fun toggleMute(): Boolean {
        val m = try {
            !(mpv.getPropertyBoolean("mute") ?: false)
        } catch (_: Exception) {
            true
        }
        try {
            mpv.setPropertyBoolean("mute", m)
        } catch (_: Exception) {
        }
        return m
    }

    /** Pistas (id, etiqueta) de un tipo ("audio"/"subtitle"). Vacío si no hay. */
    fun mediaTracks(kind: String): List<Pair<String, String>> {
        // OJO: mpv llama "sub" al tipo subtítulo (no "subtitle") — filtrar
        // por "subtitle" descartaba TODO y el botón SUB nunca salía (bug 21/09).
        val want = if (kind == "subtitle") "sub" else kind
        return try {
            val node = mpv.getPropertyNode("track-list") as? MPVNode.ArrayNode
                ?: return emptyList()
            var n = 0
            val out = node.value.mapNotNull { item ->
                val map = (item as? MPVNode.MapNode)?.value ?: return@mapNotNull null
                val type = (map["type"] as? MPVNode.StringNode)?.value ?: return@mapNotNull null
                if (type != want) return@mapNotNull null
                n += 1
                val id = when (val v = map["id"]) {
                    is MPVNode.IntNode -> v.value.toString()
                    is MPVNode.StringNode -> v.value
                    else -> return@mapNotNull null
                }
                val lang = (map["lang"] as? MPVNode.StringNode)?.value.orEmpty()
                val title = (map["title"] as? MPVNode.StringNode)?.value.orEmpty()
                val label = listOf(lang, title).filter { it.isNotEmpty() }
                    .joinToString(" · ").ifEmpty { "Pista $n" }
                id to label
            }
            android.util.Log.i(tag, "tracks $kind: ${out.size}")
            out
        } catch (e: Exception) {
            android.util.Log.w(tag, "tracks $kind falló: $e")
            emptyList()
        }
    }

    /**
     * Rota la pista actual ("audio"→aid, "subtitle"→sid). Incluye "apagado"
     * para subtítulos. Devuelve la etiqueta nueva (o null si no hay pistas).
     */
    fun cycleTrack(kind: String): String? {
        return try {
            val prop = if (kind == "audio") "aid" else "sid"
            val tracks = mediaTracks(kind).toMutableList()
            if (tracks.isEmpty()) return null
            if (kind != "audio") tracks.add("no" to "Off")
            val cur = try {
                mpv.getPropertyString(prop)
            } catch (_: Exception) {
                null
            }
            val idx = tracks.indexOfFirst { it.first == cur }
            val next = tracks[(idx + 1) % tracks.size]
            mpv.setPropertyString(prop, next.first)
            next.second
        } catch (_: Exception) {
            null
        }
    }

    fun destroy() {
        try {
            ioScope.cancel()
        } catch (_: Exception) {
        }
        try {
            detachSurface()
        } catch (_: Exception) {
        }
        try {
            mpv.removeObserver(observer)
            mpv.destroy()
        } catch (e: Exception) {
            Log.w(tag, "destroy failed: $e")
        }
    }

    // --- seamless engine (corre SIEMPRE en hilo principal) ---

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private fun onEofProp(value: Boolean?) {
        if (value != true) return
        if (!SeamlessEngine.shouldReload(armed, isLive, currentUrl)) return
        val now = clock()
        cascade = SeamlessEngine.nextCascade(cascade, now, lastEofMs)
        lastEofMs = now
        val delay = SeamlessEngine.cascadeDelayS(cascade)
        Log.i(tag, "Stream EOF - seamless reload in ${delay}s (cascade $cascade)")
        _state.value = PlayerState.Reloading(cascade, delay)
        if (!reloading) {
            reloading = true
            main.postDelayed({
                reloading = false
                if (armed && currentUrl.isNotEmpty()) {
                    try {
                        // Grabar seguía: nuevo segmento (el replace corta el anterior).
                        rollRecording()
                        if (surfaceAttached) doLoad() else pendingLoad = true
                    } catch (e: Exception) {
                        Log.w(tag, "seamless reload failed: $e")
                    }
                }
            }, delay * 1000)
        }
    }

    private fun onEvent(eventId: Int, data: MPVNode) {
        Log.d(tag, "event id=$eventId data=$data")
        when (eventId) {
            MPV_EVENT_FILE_LOADED -> {
                errRetries = 0
                cascade = 0
                _state.value = PlayerState.Playing
                updateVideoAspect()
                logStats()
                startStatsTick() // telemetría permanente (barata, clave para diagnóstico remoto)
            }
            MPV_EVENT_END_FILE -> if (isErrorEnd(data)) onError(fileErrorOf(data))
        }
    }

    /** end-file con reason=error. Verificado en device: reason es StringNode,
     *  ej: {reason=StringNode(error), event=StringNode(end-file), ...}. */
    private fun isErrorEnd(data: MPVNode?): Boolean = try {
        val map = (data as? MPVNode.MapNode)?.value ?: return false
        (map["reason"] as? MPVNode.StringNode)?.value == "error"
    } catch (_: Exception) {
        false
    }

    /** file_error del end-file, ej: "loading failed" / "HTTP error 401 ...". */
    private fun fileErrorOf(data: MPVNode?): String = try {
        val map = (data as? MPVNode.MapNode)?.value ?: return ""
        (map["file_error"] as? MPVNode.StringNode)?.value ?: ""
    } catch (_: Exception) {
        ""
    }

    /** Lee el aspect real del video (para el zoom fill). */
    private fun updateVideoAspect() {
        try {
            val vp = (mpv.getPropertyNode("video-params") as? MPVNode.MapNode)?.value
            val a = when (val n = vp?.get("aspect")) {
                is MPVNode.DoubleNode -> n.value
                is MPVNode.StringNode -> n.value.toDoubleOrNull()
                is MPVNode.IntNode -> n.value.toDouble()
                else -> null
            }
            if (a != null && a > 0) _videoAspect.value = a
            val w = (vp?.get("w") as? MPVNode.IntNode)?.value?.toInt() ?: 0
            val h = (vp?.get("h") as? MPVNode.IntNode)?.value?.toInt() ?: 0
            if (w > 0 && h > 0) {
                videoW = w
                videoH = h
            }
            try {
                val fps = mpv.getPropertyDouble("container-fps") ?: 0.0
                if (fps > 0) videoFps = fps
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    // --- diagnóstico de calidad/rendimiento (una línea por canal + tick) ---

    private fun strOpt(name: String): String = try {
        mpv.getPropertyString(name) ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun dblOpt(name: String): String = try {
        "%.3f".format(java.util.Locale.US, mpv.getPropertyDouble(name))
    } catch (_: Exception) {
        "?"
    }

    private fun longOpt(name: String): String = try {
        mpv.getPropertyLong(name).toString()
    } catch (_: Exception) {
        "?"
    }

    /** Foto del pipeline al arrancar cada canal (barato, queda permanente). */
    private fun logStats() {
        try {
            val vp = (mpv.getPropertyNode("video-params") as? MPVNode.MapNode)?.value
            // fps/aspect llegan como DoubleNode, w/h/codec como Int/String.
            fun vpn(k: String): String = when (val n = vp?.get(k)) {
                is MPVNode.StringNode -> n.value
                is MPVNode.IntNode -> n.value.toString()
                is MPVNode.DoubleNode -> n.value.toString()
                else -> "?"
            }
            Log.i(
                tag,
                "STATS hwdec=${strOpt("hwdec-current")} vo=${strOpt("current-vo")} " +
                    "video=${vpn("w")}x${vpn("h")}@${vpn("fps")}fps ${vpn("codec")} " +
                    "vcodec=${strOpt("video-codec")} cfps=${dblOpt("container-fps")} " +
                    "vffps=${dblOpt("estimated-vf-fps")} " +
                    "aspect=${vpn("aspect")} d=${longOpt("dwidth")}x${longOpt("dheight")} " +
                    "keepaspect=${strOpt("keepaspect")} dispfps=${dblOpt("display-fps")} " +
                    "audio=${strOpt("audio-codec")}",
            )
        } catch (e: Exception) {
            Log.w(tag, "STATS failed: $e")
        }
    }

    /** Línea de diagnóstico en pantalla (para informe a distancia del tester). */
    fun diagLine(): String = try {
        val soc = if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.os.Build.SOC_MODEL
        } else {
            android.os.Build.HARDWARE
        }
        "vo=${strOpt("current-vo")} hwdec=${strOpt("hwdec-current")} " +
            "${strOpt("video-codec")} ${longOpt("dwidth")}x${longOpt("dheight")} " +
            "vf=${dblOpt("estimated-vf-fps")} state=${_state.value::class.simpleName} " +
            "soc=$soc"
    } catch (_: Exception) {
        ""
    }

    private var ticking = false

    /** Muestreo cada 5s mientras reproduce (telemetría permanente). */
    private fun startStatsTick() {
        if (ticking) return
        ticking = true
        val tick = object : Runnable {
            override fun run() {
                if (_state.value is PlayerState.Playing) {
                    Log.i(
                        tag,
                        "TICK avspeed=${dblOpt("avspeed")} acorr=${dblOpt("audio-speed-correction")} " +
                            "vcorr=${dblOpt("video-speed-correction")} decdrop=${longOpt("decoder-frame-drop-count")} " +
                            "framedrop=${longOpt("frame-drop-count")} cache=${dblOpt("demuxer-cache-duration")}s " +
                            "mistimed=${longOpt("mistimed-frame-count")} vsyncr=${dblOpt("vsync-ratio")} " +
                            "tpos=${dblOpt("time-pos")} speed=${dblOpt("speed")} " +
                            "asr=${longOpt("audio-samplerate")} aid=${strOpt("aid")} " +
                            "adevice=${strOpt("audio-device")} mute=${strOpt("mute")}",
                    )
                    main.postDelayed(this, 5000)
                } else {
                    ticking = false
                }
            }
        }
        main.postDelayed(tick, 5000)
    }

    private fun onError(fileError: String = "") {
        // Errores que NO sanan solos con reintentos (auth/URL vencida):
        // cortar la cascada de una y avisar. Un 401 martillado 10 veces solo
        // consume conexiones del proveedor (verificado con avefenix 13/09).
        if (SeamlessEngine.isFatalStreamError(fileError)) {
            Log.w(tag, "Fatal stream error ('$fileError') — no reintentar")
            _state.value = PlayerState.Dead(fileError)
            armed = false
            return
        }
        errRetries += 1
        if (!SeamlessEngine.shouldRetryError(armed, isLive, errRetries)) {
            Log.w(tag, "Playback error, giving up after $errRetries retries")
            return
        }
        val delay = SeamlessEngine.errorDelayS(errRetries)
        Log.w(tag, "Playback error, retry $errRetries/${SeamlessEngine.MAX_ERROR_RETRIES} in ${delay}s")
        _state.value = PlayerState.Error(errRetries, delay)
        main.postDelayed({
            if (armed && currentUrl.isNotEmpty()) {
                try {
                    rollRecording()
                    if (surfaceAttached) doLoad() else pendingLoad = true
                } catch (e: Exception) {
                    Log.w(tag, "error retry failed: $e")
                }
            }
        }, delay * 1000)
    }
}
