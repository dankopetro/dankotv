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
}
