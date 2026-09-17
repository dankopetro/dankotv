package tv.danko.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipTranscoderTest {

    @Test
    fun ventanaExactaAlFinal() {
        // 1500 frames video, 2300 audio, quiero últimos 10s a 30fps.
        val q = ClipTranscoder.computeQuotas(1500, 2300, 48000, 10_000_000L, 30.0)
        assertEquals(300L, q.targetV) // 10s * 30
        assertEquals(1200L, q.feedStartV + q.skipOutV) // ventana arranca en 1200
        assertEquals(1500L, q.feedStartV + q.skipOutV + q.targetV) // termina al final
        // Audio proporcional a la misma duración (~10s).
        val aDurUs = q.targetA * 1024L * 1_000_000L / 48000
        assertTrue(aDurUs in 9_500_000L..10_500_000L)
        assertEquals(2300L, q.skipA + q.targetA)
    }

    @Test
    fun keepCeroEsTodo() {
        val q = ClipTranscoder.computeQuotas(500, 800, 48000, 0, 30.0)
        assertEquals(500L, q.targetV)
        assertEquals(0L, q.skipOutV + q.feedStartV)
        assertEquals(800L, q.skipA + q.targetA)
    }

    @Test
    fun keepMayorQueArchivoRecortaAlArchivo() {
        val q = ClipTranscoder.computeQuotas(100, 160, 48000, 60_000_000L, 30.0)
        assertEquals(100L, q.targetV)
        assertEquals(0L, q.feedStartV + q.skipOutV)
    }

    @Test
    fun fps60DaDobleFrames() {
        val q = ClipTranscoder.computeQuotas(1200, 1800, 48000, 10_000_000L, 60.0)
        assertEquals(600L, q.targetV)
    }

    @Test
    fun presupuesto4MB() {
        // 10s a 3Mbps video + 128kbps audio ≈ 3.9MB.
        val bytes = (10L * (ClipTranscoder.VIDEO_BITRATE + ClipTranscoder.AUDIO_BITRATE) / 8)
        assertTrue(bytes <= 4L * 1024 * 1024)
    }
}
