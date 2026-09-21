package tv.danko.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareUtilsTest {

    @Test
    fun sanitizeDejaNombresSeguros() {
        assertEquals("MEGA_HD_Chile", ShareUtils.sanitize("MEGA HD (Chile)"))
        assertEquals("canal", ShareUtils.sanitize("   "))
        assertEquals("canal", ShareUtils.sanitize("!!!"))
        assertEquals("A_B", ShareUtils.sanitize("A   B"))
        // Acentos y ñ se conservan (válidos en MediaStore).
        assertEquals("Señal_News", ShareUtils.sanitize("Señal News"))
        // Largo acotado.
        assertTrue(ShareUtils.sanitize("x".repeat(100)).length <= 40)
    }

    @Test
    fun baseNameConTimestamp() {
        val b = ShareUtils.baseName("MEGA HD", 1789579200000L)
        assertTrue(b.startsWith("DankoTV_MEGA_HD_"))
        assertTrue(b.matches(Regex("DankoTV_MEGA_HD_\\d{8}-\\d{6}")))
    }

    @Test
    fun castStreamUrlM3uXtreamAHls() {
        assertEquals(
            "http://h/live/u/p/28244.m3u8",
            castStreamUrl("http://h/u/p/28244", true),
        )
    }

    @Test
    fun castStreamUrlApiLiveAHls() {
        assertEquals(
            "http://h/live/u/p/201.m3u8",
            castStreamUrl("http://h/live/u/p/201.ts", true),
        )
    }

    @Test
    fun castStreamUrlRespetaVodYM3u8() {
        // VOD no se toca (el receptor sí reproduce mkv/mp4).
        assertEquals(
            "http://h/movie/u/p/10.mkv",
            castStreamUrl("http://h/movie/u/p/10.mkv", true),
        )
        // HLS ya existente pasa igual.
        val hls = "https://r/hls/x/playlist.m3u8?tok=1"
        assertEquals(hls, castStreamUrl(hls, true))
        // No-vivo pasa igual.
        val ts = "http://h/u/p/9"
        assertEquals(ts, castStreamUrl(ts, false))
        // Sin id numérico pasa igual.
        val raro = "http://h/stream.m3u8"
        assertEquals(raro, castStreamUrl(raro, true))
    }
}
