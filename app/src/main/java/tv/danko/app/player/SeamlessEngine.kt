package tv.danko.app.player

/**
 * Matemática pura del motor seamless. Port exacto de _on_eof()/_on_error() en
 * dankotv/player.py — sin dependencias Android para testearse en JVM.
 */
object SeamlessEngine {

    const val CASCADE_WINDOW_MS = 20_000L
    const val MAX_CASCADE_DELAY_S = 8L
    const val MAX_ERROR_DELAY_S = 30L
    const val MAX_ERROR_RETRIES = 10

    /** Linux: cascade+1 si el EOF anterior fue hace <20s, si no 0. */
    fun nextCascade(prevCascade: Int, nowMs: Long, lastEofMs: Long): Int =
        if (nowMs - lastEofMs < CASCADE_WINDOW_MS) prevCascade + 1 else 0

    /** Linux: delay = min(cascade*2, 8)s. Secuencia: 0,2,4,6,8,8… */
    fun cascadeDelayS(cascade: Int): Long =
        minOf(cascade * 2L, MAX_CASCADE_DELAY_S)

    /** Linux: delay = min(2^(retry-1), 30)s con retry ya incrementado (1-based). */
    fun errorDelayS(retry1Based: Int): Long {
        require(retry1Based >= 1)
        val exp = 1L shl (retry1Based - 1).coerceAtMost(30)
        return minOf(exp, MAX_ERROR_DELAY_S)
    }

    /** Guardas de _on_eof(): solo recarga live armado con URL. */
    fun shouldReload(armed: Boolean, isLive: Boolean, url: String): Boolean =
        armed && isLive && url.isNotEmpty()

    /** Guarda de _on_error(): reintenta si armado+live y quedan intentos. */
    fun shouldRetryError(armed: Boolean, isLive: Boolean, retries: Int): Boolean =
        armed && isLive && retries <= MAX_ERROR_RETRIES

    /**
     * Errores de stream que NO sanan con reintentos: HTTP 401/403 (auth/URL
     * vencida — el panel Xtream responde 401 tanto por credenciales caídas
     * como por URLs de stream rotadas), 404/410 (canal eliminado). En esos
     * casos reintentar solo quema conexiones del proveedor (verificado con
     * avefenix 13/09: 10 reintentos inútiles a un 401).
     * El texto llega en file_error del evento end-file, ej:
     * "HTTP error 401 Unauthorized" o "loading failed" (genérico lavf).
     * OJO: "loading failed" solo es fatal si trae código 4xx embebido.
     */
    fun isFatalStreamError(fileError: String): Boolean =
        Regex("""HTTP error 4(01|03|04|10)\b""").containsMatchIn(fileError)

    /**
     * Zoom mpv (prop `video-zoom`, unidades lineales: 0 = 1:1) para LLENAR
     * la pantalla recortando (estilo VLC "Fill", sin deformar). Solo tiene
     * sentido si la pantalla es más ancha que el video.
     */
    fun fillZoomProp(screenAspect: Double, videoAspect: Double): Double {
        if (videoAspect <= 0 || screenAspect <= 0) return 0.0
        val factor = (screenAspect / videoAspect).coerceIn(1.0, 3.0)
        return factor - 1.0
    }

    /** Pan mpv (`video-pan-x/y`) siempre en [-1, 1]. */
    fun clampPan(v: Double): Double = v.coerceIn(-1.0, 1.0)
}
