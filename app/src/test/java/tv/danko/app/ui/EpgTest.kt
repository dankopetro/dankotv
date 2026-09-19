package tv.danko.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.danko.app.data.EpgEntry
import tv.danko.app.data.decodeB64
import tv.danko.app.data.pickNowNext
import tv.danko.app.data.streamIdFromUrl
import tv.danko.app.data.xtreamCredsFromM3uUrl

class EpgTest {

    @Test
    fun base64DecodificaTitulosXtream() {
        // "Meganoticias ahora" (verificado contra el proveedor 17/09).
        assertEquals("Meganoticias ahora", decodeB64("TWVnYW5vdGljaWFzIGFob3Jh"))
        assertEquals("", decodeB64(null))
        assertEquals("", decodeB64(""))
        // Si no es base64 (! inválido), devuelve crudo (no revienta).
        assertEquals("Hola!!!", decodeB64("Hola!!!"))
    }

    @Test
    fun streamIdDesdeUrlsXtream() {
        // Xtream API: host/live/user/pass/123.ts
        assertEquals(28244, streamIdFromUrl("http://h/live/u/p/28244.ts"))
        // M3U estilo Xtream: host/user/pass/12345
        assertEquals(58287, streamIdFromUrl("http://superxlatino.com:8880/Naty2/xxx/58287"))
        assertNull(streamIdFromUrl("http://h/stream.m3u8"))
        assertNull(streamIdFromUrl(""))
    }

    @Test
    fun credencialesDesdeM3uGetPhp() {
        val (h, u, p) = xtreamCredsFromM3uUrl(
            "http://superxlatino.com:8880/get.php?username=Naty2&password=tUq3py2tKhhT&type=m3u_plus",
        )!!
        assertEquals("http://superxlatino.com:8880", h)
        assertEquals("Naty2", u)
        assertEquals("tUq3py2tKhhT", p)
        assertNull(xtreamCredsFromM3uUrl("http://h/playlist.m3u"))
    }

    @Test
    fun pickNowNextEligeEnCursoYSiguiente() {
        val now = 1_000_000L
        val a = EpgEntry("A", "", now - 3_600_000, now - 1_000)
        val b = EpgEntry("B", "", now - 1_000, now + 3_600_000)
        val c = EpgEntry("C", "", now + 3_600_000, now + 7_200_000)
        val (n, nx) = pickNowNext(listOf(c, a, b), now)
        assertEquals("B", n?.title)
        assertEquals("C", nx?.title)
    }

    @Test
    fun pickNowNextSinDatosDaNulls() {
        val (n, nx) = pickNowNext(emptyList(), 1_000_000L)
        assertNull(n)
        assertNull(nx)
        // Todo futuro: now null, next el primero.
        val f = EpgEntry("F", "", 5_000_000L, 6_000_000L)
        val (n2, nx2) = pickNowNext(listOf(f), 1_000_000L)
        assertNull(n2)
        assertEquals("F", nx2?.title)
    }

    @Test
    fun pickNowNextToleraSolapesDelProveedor() {
        // El proveedor manda rangos solapados: gana el primero que cubre ahora.
        val now = 1_000_000L
        val a = EpgEntry("A", "", now - 100_000, now + 500_000)
        val b = EpgEntry("B", "", now - 50_000, now + 100_000)
        val (n, _) = pickNowNext(listOf(a, b), now)
        assertEquals("A", n?.title)
    }
}
