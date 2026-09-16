package tv.danko.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamlessEngineTest {

    @Test
    fun cascadaSubeDentroDeVentanaYReseteaFuera() {
        // EOFs con 5s de diferencia: 0,1,2 (ventana 20s)
        var c = SeamlessEngine.nextCascade(0, 100_000L, 0L)
        assertEquals(0, c) // primer EOF (last=0, diff enorme) -> 0
        c = SeamlessEngine.nextCascade(c, 105_000L, 100_000L)
        assertEquals(1, c)
        c = SeamlessEngine.nextCascade(c, 110_000L, 105_000L)
        assertEquals(2, c)
        // EOF 60s después -> reset
        c = SeamlessEngine.nextCascade(c, 170_000L, 110_000L)
        assertEquals(0, c)
        // borde: justo 20s NO suma (< 20 estricto, como Linux)
        c = SeamlessEngine.nextCascade(2, 130_000L, 110_000L)
        assertEquals(0, c)
    }

    @Test
    fun delayCascadaSecuencia() {
        assertEquals(listOf(0L, 2L, 4L, 6L, 8L, 8L, 8L), (0..6).map { SeamlessEngine.cascadeDelayS(it) })
    }

    @Test
    fun backoffExponencialConCap() {
        assertEquals(
            listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L, 30L, 30L),
            (1..10).map { SeamlessEngine.errorDelayS(it) },
        )
    }

    @Test
    fun guardas() {
        assertTrue(SeamlessEngine.shouldReload(true, true, "http://x/1.ts"))
        assertFalse(SeamlessEngine.shouldReload(false, true, "http://x/1.ts"))
        assertFalse(SeamlessEngine.shouldReload(true, false, "http://x/1.ts"))
        assertFalse(SeamlessEngine.shouldReload(true, true, ""))
        assertTrue(SeamlessEngine.shouldRetryError(true, true, 10))
        assertFalse(SeamlessEngine.shouldRetryError(true, true, 11))
        assertFalse(SeamlessEngine.shouldRetryError(false, true, 1))
        assertFalse(SeamlessEngine.shouldRetryError(true, false, 1))
        assertEquals(10, SeamlessEngine.MAX_ERROR_RETRIES)
    }

    @Test
    fun erroresFatalesCortanCascada() {
        // 401/403/404/410 → fatal (no reintentar)
        assertTrue(SeamlessEngine.isFatalStreamError("HTTP error 401 Unauthorized"))
        assertTrue(SeamlessEngine.isFatalStreamError("HTTP error 403 Forbidden"))
        assertTrue(SeamlessEngine.isFatalStreamError("HTTP error 404 Not Found"))
        assertTrue(SeamlessEngine.isFatalStreamError("HTTP error 410 Gone"))
        // errores transitorios → reintentar normal
        assertFalse(SeamlessEngine.isFatalStreamError("loading failed"))
        assertFalse(SeamlessEngine.isFatalStreamError("Connection reset by peer"))
        assertFalse(SeamlessEngine.isFatalStreamError("HTTP error 500 Internal Server Error"))
        assertFalse(SeamlessEngine.isFatalStreamError("HTTP error 503 Service Unavailable"))
        assertFalse(SeamlessEngine.isFatalStreamError(""))
    }

    @Test
    fun zoomFillSoloCuandoPantallaMasAncha() {
        // Teléfono 20:9 (2.222) vs video 16:9 (1.778): zoom 0.25 (recorta 20%).
        assertEquals(0.25, SeamlessEngine.fillZoomProp(2.222, 1.778), 0.01)
        // Misma proporción: sin zoom.
        assertEquals(0.0, SeamlessEngine.fillZoomProp(1.778, 1.778), 0.0)
        // Pantalla más angosta (portrait): sin zoom (no estirar).
        assertEquals(0.0, SeamlessEngine.fillZoomProp(0.45, 1.778), 0.0)
        // Clamp a 3x y entradas inválidas.
        assertEquals(2.0, SeamlessEngine.fillZoomProp(4.0, 1.0), 0.0)
        assertEquals(0.0, SeamlessEngine.fillZoomProp(2.0, 0.0), 0.0)
        assertEquals(0.0, SeamlessEngine.fillZoomProp(0.0, 1.778), 0.0)
        assertEquals(1.0, SeamlessEngine.clampPan(1.0), 0.0)
        assertEquals(-1.0, SeamlessEngine.clampPan(-5.0), 0.0)
        assertEquals(1.0, SeamlessEngine.clampPan(9.0), 0.0)
    }
}
