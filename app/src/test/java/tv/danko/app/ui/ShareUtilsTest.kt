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
    fun findSpsPpsExtraeNalsAnnexB() {
        // Stream sintético: basura + SPS (7) + PPS (8) + IDR (5).
        val sps = byteArrayOf(0, 0, 1, 0x67, 0x64, 0x00, 0x1E, 0x7D.toByte())
        val pps = byteArrayOf(0, 0, 1, 0x68, 0xE9.toByte(), 0x7B, 0x22)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33)
        val bin = byteArrayOf(0xFF.toByte(), 0x00, 0x47) + sps + pps + idr
        val f = java.io.File.createTempFile("sps", ".ts")
        try {
            f.writeBytes(bin)
            val (s, p) = ShareUtils.findSpsPps(f)
            // SIN start code, CON header NAL.
            org.junit.Assert.assertArrayEquals(
                byteArrayOf(0x67, 0x64, 0x00, 0x1E, 0x7D.toByte()), s,
            )
            org.junit.Assert.assertArrayEquals(byteArrayOf(0x68, 0xE9.toByte(), 0x7B, 0x22), p)
        } finally {
            f.delete()
        }
    }

    @Test
    fun findSpsPpsSinNalsDaNull() {
        val f = java.io.File.createTempFile("nonals", ".ts")
        try {
            f.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            val (s, p) = ShareUtils.findSpsPps(f)
            org.junit.Assert.assertNull(s)
            org.junit.Assert.assertNull(p)
        } finally {
            f.delete()
        }
    }
}
