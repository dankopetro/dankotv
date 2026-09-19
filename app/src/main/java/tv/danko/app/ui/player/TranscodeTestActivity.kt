package tv.danko.app.ui.player

import android.app.Activity
import android.os.Bundle
import java.io.File

/**
 * Arnés de self-test (oculto, solo adb): transcoding o EPG sin tocar la UI.
 *   am start -n tv.danko.app/tv.danko.app.ui.player.TranscodeTestActivity \
 *     --es src /ruta/input.ts --es dst /ruta/out.mp4 [--es keepUs ms]
 *   am start -n ...TranscodeTestActivity --es mode epg --es list NOMBRE \
 *     --es urls "url1|url2|url3"
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
                if (intent.getStringExtra("mode") == "epg") {
                    val list = intent.getStringExtra("list") ?: ""
                    val urls = (intent.getStringExtra("urls") ?: "").split('|')
                        .filter { it.isNotEmpty() }
                    ShareUtils.recLog(this, "SELFTEST-EPG: list=$list urls=${urls.size}")
                    val db = androidx.room.Room.databaseBuilder(
                        this, tv.danko.app.data.db.AppDatabase::class.java, "dankotv.db",
                    ).addMigrations(
                        tv.danko.app.data.db.MIGRATION_1_2,
                        tv.danko.app.data.db.MIGRATION_2_3,
                    ).build()
                    try {
                        val lists = tv.danko.app.data.ListsRepository(db.playlists(), tv.danko.app.data.SettingsStore.create(this))
                        val epg = tv.danko.app.data.EpgRepository(db.playlists(), lists)
                        kotlinx.coroutines.runBlocking {
                            val ids = epg.epgStreamIds(list)
                            ShareUtils.recLog(this@TranscodeTestActivity, "SELFTEST-EPG: mapa=${ids?.size}")
                            for (u in urls) {
                                val (now, next) = epg.nowNext(list, u)
                                ShareUtils.recLog(
                                    this@TranscodeTestActivity,
                                    "SELFTEST-EPG: $u -> ahora=[${now?.title}] despues=[${next?.title}]",
                                )
                            }
                            val rows = epg.guideRows(list, urls)
                            ShareUtils.recLog(this@TranscodeTestActivity, "SELFTEST-EPG: filas=${rows.size}")
                        }
                    } finally {
                        try {
                            db.close()
                        } catch (_: Exception) {
                        }
                    }
                    ShareUtils.recLog(this, "SELFTEST-EPG: fin")
                    return@Thread
                }
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
