package tv.danko.app.ui.player

import android.app.Activity
import android.os.Bundle
import java.io.File

/**
 * Arnés de self-test (oculto, no exportado): transcoding sin tocar la UI.
 * Se dispara por adb sin interacción:
 *   am start -n tv.danko.app/tv.danko.app.ui.player.TranscodeTestActivity \
 *     --es src /ruta/input.ts --es dst /ruta/out.mp4 [--es keepUs ms]
 * Escribe el resultado en cacheDir/DankoTV/rec.log y termina.
 */
class TranscodeTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = intent.getStringExtra("src") ?: ""
        val dst = intent.getStringExtra("dst") ?: ""
        val keepUs = try {
            intent.getStringExtra("keepUs")?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        }
        Thread({
            try {
                ShareUtils.recLog(
                    this,
                    "SELFTEST: src=$src dst=$dst keepUs=$keepUs",
                )
                val ok = ClipTranscoder.transcode(
                    File(src), File(dst), keepUs, 30.0,
                ) { ShareUtils.recLog(this, "SELFTEST $it") }
                ShareUtils.recLog(
                    this,
                    "SELFTEST: ok=$ok out=${try {
                        File(dst).length()
                    } catch (_: Exception) {
                        -1
                    }}",
                )
            } catch (e: Exception) {
                try {
                    ShareUtils.recLog(this, "SELFTEST excepción: $e")
                } catch (_: Exception) {
                }
            } finally {
                try {
                    finish()
                } catch (_: Exception) {
                }
            }
        }, "selftest").start()
    }
}
