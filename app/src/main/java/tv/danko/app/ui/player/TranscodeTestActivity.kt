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
                if (intent.getStringExtra("mode") == "tracks") {
                    // Volcado headless de track-list mpv (diagnóstico SUB).
                    val url = intent.getStringExtra("url") ?: ""
                    ShareUtils.recLog(this, "SELFTEST-TRACKS: $url")
                    try {
                        val mpv = `is`.xyz.mpv.MPV()
                        mpv.create(this)
                        mpv.addLogObserver(object : `is`.xyz.mpv.MPV.LogObserver {
                            override fun logMessage(prefix: String, level: Int, text: String) {
                                val t = text.trimEnd()
                                if (t.isEmpty() || level > 30) return
                                try {
                                    ShareUtils.recLog(
                                        this@TranscodeTestActivity,
                                        "SELFTEST-MPV [$prefix] $t",
                                    )
                                } catch (_: Exception) {
                                }
                            }
                        })
                        mpv.setOptionString("vo", "null")
                        mpv.setOptionString("ao", "null")
                        mpv.setOptionString("hwdec", "no")
                        mpv.setOptionString("user-agent", "libmpv")
                        mpv.setOptionString("sub-auto", "all")
                        mpv.command("loadfile", url, "replace")
                        for (i in 1..6) {
                            Thread.sleep(3000)
                            try {
                                val dur = try {
                                    mpv.getPropertyDouble("duration")?.toString() ?: "?"
                                } catch (_: Exception) {
                                    "?"
                                }
                                val node = mpv.getPropertyNode("track-list")
                                val arr = node as? `is`.xyz.mpv.MPVNode.ArrayNode
                                val items = arr?.value?.mapNotNull {
                                    val m = (it as? `is`.xyz.mpv.MPVNode.MapNode)?.value
                                        ?: return@mapNotNull null
                                    val t = (m["type"] as? `is`.xyz.mpv.MPVNode.StringNode)?.value
                                    val id = m["id"]?.toString()
                                    val lang = (m["lang"] as? `is`.xyz.mpv.MPVNode.StringNode)?.value
                                    val title = (m["title"] as? `is`.xyz.mpv.MPVNode.StringNode)?.value
                                    val codec = (m["codec"] as? `is`.xyz.mpv.MPVNode.StringNode)?.value
                                    "$t/$id/$lang/$title/$codec"
                                } ?: emptyList()
                                ShareUtils.recLog(this, "SELFTEST-TRACKS t+${i * 3}s dur=$dur: $items")
                                if (items.any { it.startsWith("sub/") }) break
                            } catch (e: Exception) {
                                ShareUtils.recLog(this, "SELFTEST-TRACKS t+${i * 3}s err: $e")
                            }
                        }
                        try {
                            mpv.destroy()
                        } catch (_: Exception) {
                        }
                    } catch (e: Exception) {
                        ShareUtils.recLog(this, "SELFTEST-TRACKS excepción: $e")
                    }
                    ShareUtils.recLog(this, "SELFTEST-TRACKS: fin")
                    return@Thread
                }
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
                        tv.danko.app.data.db.MIGRATION_3_4,
                        tv.danko.app.data.db.MIGRATION_4_5,
                    ).build()
                    try {
                        val lists = tv.danko.app.data.ListsRepository(db.playlists(), tv.danko.app.data.SettingsStore.create(this))
                        val epg = tv.danko.app.data.EpgRepository(db.playlists(), lists)
                        kotlinx.coroutines.runBlocking {
                            val ids = epg.epgStreamIds(list)
                            ShareUtils.recLog(this@TranscodeTestActivity, "SELFTEST-EPG: mapa=${ids?.size}")
                            val group = intent.getStringExtra("group")
                            if (!group.isNullOrEmpty()) {
                                val g = if (group == "Todas") null else group
                                val scope = epg.scanGroup(list, g)
                                ShareUtils.recLog(
                                    this@TranscodeTestActivity,
                                    "SELFTEST-EPG: scan grupo=$group -> ${scope.size} canales",
                                )
                                val rows = epg.guideRows(list, scope.map { it.url })
                                ShareUtils.recLog(
                                    this@TranscodeTestActivity,
                                    "SELFTEST-EPG: filas=${rows.size}",
                                )
                                for (r in rows.take(8)) {
                                    val title = scope.firstOrNull { it.url == r.channelUrl }?.title
                                    ShareUtils.recLog(
                                        this@TranscodeTestActivity,
                                        "SELFTEST-EPG fila: $title -> " +
                                            r.programmes.take(2).joinToString(" | ") { it.title },
                                    )
                                }
                            }
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
