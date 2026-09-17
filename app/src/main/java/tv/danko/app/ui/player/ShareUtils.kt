package tv.danko.app.ui.player

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F4-rec (solo teléfono): guardar en galería + compartir a WhatsApp/IG.
 * Sin permisos nuevos en Android moderno (MediaStore, archivos propios).
 * Sin ffmpeg: .ts tal cual sale de mpv, o .mp4 si el mux directo funciona.
 */
object ShareUtils {

    /** "MEGA HD (Chile)" → "MEGA_HD_Chile" (seguro para archivos y MediaStore). */
    fun sanitize(name: String): String {
        val s = name.trim().replace(Regex("[^a-zA-Z0-9áéíóúñüÁÉÍÓÚÑÜ-]+"), "_")
            .replace(Regex("_+"), "_").trim('_')
        return s.ifEmpty { "canal" }.take(40)
    }

    /** "DankoTV_MEGA_HD_20260916-123456". */
    fun baseName(title: String, whenMs: Long = System.currentTimeMillis()): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(whenMs))
        return "DankoTV_${sanitize(title)}_$ts"
    }

    /**
     * Log persistente del flujo REC (cacheDir/DankoTV/rec.log, máx 100KB).
     * El logcat rota en minutos y los tests del usuario llegan tarde;
     * este archivo se trae por adb cuando haga falta. Espeja a logcat.
     */
    fun recLog(ctx: Context, msg: String) {
        try {
            android.util.Log.i("DankoTV", msg)
            val dir = File(ctx.cacheDir, "DankoTV")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "rec.log")
            if (f.exists() && f.length() > 100 * 1024) f.delete()
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            f.appendText("$ts $msg\n")
        } catch (_: Exception) {
        }
    }

    /**
     * ¿El archivo tiene tracks A/V legibles? (validación barata post-dump).
     */
    fun hasAvTracks(f: File): Boolean {
        var ex: android.media.MediaExtractor? = null
        try {
            ex = android.media.MediaExtractor()
            ex.setDataSource(f.absolutePath)
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i)
                    .getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) return true
            }
            return false
        } catch (_: Exception) {
            return false
        } finally {
            try {
                ex?.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Remux .ts → .mp4 SIN re-encode (copia H264+AAC tal cual). WhatsApp/IG
     * no aceptan .ts; con esto no hace falta ffmpeg (imposible en la TV de
     * 32 bits y pesado en todos lados). Devuelve true si el .mp4 quedó válido.
     *
     * NOTA 16/09: en la práctica el MediaMuxer nativo rechaza estos streams
     * (timestamps del proveedor) con "muxer would have stopped already" aun
     * con CSD inyectado y 3603 samples escritos OK. Por eso se graba .mp4
     * DIRECTO desde mpv y esto queda como fallback documentado.
     *
     * Robustez para cortes mid-stream (sin SPS/PPS al inicio): si el formato
     * del extractor viene sin csd-0/csd-1, se buscan los NAL SPS/PPS Annex-B
     * en el archivo y se inyectan; y se arranca a muxear desde el primer
     * keyframe de video.
     */
    fun remuxTsToMp4(
        src: File,
        dst: File,
        trimToLastUs: Long = 0,
        videoFps: Double = 30.0,
        log: (String) -> Unit = {},
    ): Boolean {
        // Intento 1: completo. Si el stop() falla (timestamps del proveedor),
        // intento 2: solo video (diagnostica + entrega algo útil igual).
        try {
            if (muxTracks(src, dst, withAudio = true, trimToLastUs, videoFps, log)) return true
        } catch (e: Exception) {
            log("remux full falló: $e")
        }
        try {
            dst.delete()
        } catch (_: Exception) {
        }
        log("remux: reintento solo-video")
        return try {
            muxTracks(src, dst, withAudio = false, trimToLastUs, videoFps, log)
        } catch (e: Exception) {
            log("remux video-only falló: $e")
            try {
                dst.delete()
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun muxTracks(
        src: File,
        dst: File,
        withAudio: Boolean,
        trimToLastUs: Long = 0,
        videoFps: Double = 30.0,
        log: (String) -> Unit = {},
    ): Boolean {
        var extractor: android.media.MediaExtractor? = null
        var muxer: android.media.MediaMuxer? = null
        try {
            if (!src.exists() || src.length() == 0L) return false
            extractor = android.media.MediaExtractor()
            extractor.setDataSource(src.absolutePath)
            data class Track(val srcIdx: Int, val format: android.media.MediaFormat, val isVideo: Boolean)
            val tracks = mutableListOf<Track>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                val isVideo = mime.startsWith("video/")
                if (isVideo || (withAudio && mime.startsWith("audio/"))) {
                    tracks.add(Track(i, format, isVideo))
                }
            }
            log("remux: ${tracks.size} tracks en ${src.name}")
            if (tracks.isEmpty()) {
                log("remux: sin tracks A/V en ${src.name}")
                return false
            }
            tracks.filter { !it.isVideo }.forEach {
                log("remux: audio mime=${it.format.getString(android.media.MediaFormat.KEY_MIME)} " +
                        "csd=${it.format.containsKey("csd-0")}",
                )
            }
            val video = tracks.firstOrNull { it.isVideo }
            // CSD: el del extractor viene malformado con estos streams (el
            // muxer lo acepta en addTrack pero revienta en stop()). Se
            // reconstruye SIEMPRE desde SPS/PPS escaneados (testeado en JVM).
            if (video != null) {
                val (sps, pps) = findSpsPps(src)
                log(
                    "remux: video mime=${video.format.getString(android.media.MediaFormat.KEY_MIME)} " +
                        "csdOrig=${video.format.containsKey("csd-0")} " +
                        "SPS=${sps?.size} PPS=${pps?.size}",
                )
                if (sps != null) video.format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps))
                if (pps != null) video.format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps))
            }
            // Trim del pre-roll (el dump trae ~45s previos al REC) POR CONTEO:
            // los PTS son basura (seekTo no se mueve, en silencio), así que
            // se cuenta (sin decode) y se avanza por cantidad. Inmune a PTS.
            val fpsTrim = videoFps.coerceIn(1.0, 120.0)
            var skipV = 0L
            var skipA = 0L
            if (trimToLastUs > 0) {
                var cnt: android.media.MediaExtractor? = null
                try {
                    cnt = android.media.MediaExtractor()
                    cnt.setDataSource(src.absolutePath)
                    for (t in tracks) cnt.selectTrack(t.srcIdx)
                    var vc = 0L
                    var ac = 0L
                    while (true) {
                        val idx = cnt.sampleTrackIndex
                        if (idx < 0) break
                        if (tracks.firstOrNull { it.srcIdx == idx }?.isVideo == true) vc++ else ac++
                        cnt.advance()
                    }
                    val fileUs = if (vc > 0) vc * 1_000_000L / fpsTrim.toLong() else 0L
                    val skipUs = fileUs - trimToLastUs
                    log("remux: conteo v=$vc a=$ac (~${fileUs / 1_000_000}s), recorte ${(skipUs / 1_000_000)}s")
                    if (skipUs > 1_000_000 && vc > 0) {
                        skipV = skipUs * fpsTrim.toLong() / 1_000_000L
                        skipA = if (ac > 0) skipV * ac / vc else 0L
                        log("remux: salta $skipV video + $skipA audio")
                    }
                } catch (e: Exception) {
                    log("remux: conteo falló ($e), va completo")
                    skipV = 0
                    skipA = 0
                } finally {
                    try {
                        cnt?.release()
                    } catch (_: Exception) {
                    }
                }
            }
            muxer = android.media.MediaMuxer(
                dst.absolutePath,
                android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            val trackMap = HashMap<Int, Int>()
            for (t in tracks) {
                extractor.selectTrack(t.srcIdx)
                trackMap[t.srcIdx] = muxer.addTrack(t.format)
            }
            // Saltar pre-roll por conteo (cuotas por track; vencen casi a la
            // vez porque van proporcionales; el resto sub-frame es nada).
            val wantTrim = skipV > 0 || skipA > 0
            if (wantTrim) {
                log("remux: salta $skipV video + $skipA audio")
                var sv = skipV
                var sa = skipA
                while (sv > 0 || sa > 0) {
                    val idx = extractor.sampleTrackIndex
                    if (idx < 0) break
                    if (tracks.firstOrNull { it.srcIdx == idx }?.isVideo == true) {
                        if (sv > 0) {
                            sv--
                            extractor.advance()
                        } else {
                            break
                        }
                    } else {
                        if (sa > 0) {
                            sa--
                            extractor.advance()
                        } else {
                            break
                        }
                    }
                }
            }
            // Arrancar en keyframe de video SOLO si no hubo trim (con trim se
            // arranca exacto y se aceptan artefactos iniciales breves; sin
            // trim se busca arranque limpio). Audio alineado abajo en ambos.
            var vStartPts = 0L
            run {
                if (!wantTrim) {
                    var skips = 0
                    while (skips < 2000) {
                        val idx = extractor.sampleTrackIndex
                        if (idx < 0) break
                        val isVid = tracks.firstOrNull { it.srcIdx == idx }?.isVideo ?: false
                        if (!isVid) break // audio inicial OK
                        if (extractor.sampleFlags and android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) break
                        extractor.advance()
                        skips += 1
                    }
                }
                // El primer video a escribir define el cero de video.
                var guard = 0
                while (guard < 2000) {
                    val idx = extractor.sampleTrackIndex
                    if (idx < 0) break
                    val isVid = tracks.firstOrNull { it.srcIdx == idx }?.isVideo ?: false
                    if (isVid) {
                        vStartPts = extractor.sampleTime
                        break
                    }
                    extractor.advance()
                    guard += 1
                }
                // Alinear audio: avanzar hasta su pts >= inicio de video.
                guard = 0
                while (guard < 2000) {
                    val idx = extractor.sampleTrackIndex
                    if (idx < 0) break
                    val isVid = tracks.firstOrNull { it.srcIdx == idx }?.isVideo ?: false
                    if (!isVid && extractor.sampleTime >= vStartPts) break
                    if (isVid) break // video ya alineado
                    extractor.advance()
                    guard += 1
                }
            }
            muxer.start()
            // 4MB: los IDR 1080p superan 512KB; con buffer chico readSampleData
            // falla, el loop aborta y queda solo audio (16/09).
            val buf = java.nio.ByteBuffer.allocate(4 * 1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            var samples = 0
            var first = ""
            // Timestamps REALES del extractor (extractor.sampleTime): video
            // en orden de llegada (el extractor entrega en decode order).
            // Fluidez natural: los frames se muestran en su orden temporal real.
            // La duración total variará ligeramente al milisegundo, pero el video
            // será fluido sin frames para adelante/atrás.
            val fps = videoFps.coerceIn(1.0, 120.0)
            val aRate = try {
                tracks.firstOrNull { !it.isVideo }
                    ?.format?.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE) ?: 48000
            } catch (_: Exception) {
                48000
            }.coerceIn(8000, 192000)

            var vCount = 0L
            var aCount = 0L
            val videoIdx = tracks.firstOrNull { it.isVideo }?.srcIdx ?: -1

            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                info.offset = 0
                info.size = extractor.readSampleData(buf, 0)
                if (info.size < 0) break
                val isVid = track == videoIdx
                info.flags = extractor.sampleFlags

                if (isVid) {
                    info.presentationTimeUs = extractor.sampleTime
                    vCount += 1
                } else {
                    info.presentationTimeUs = aCount * 1024L * 1_000_000L / aRate
                    aCount += 1
                }
                muxer.writeSampleData(trackMap[track] ?: break, buf, info)
                extractor.advance()
            }
            // Sin reorder, escritura directa en orden de llegada
            log("remux: pre-stop samples=$vCount first=[track=$videoIdx size=${info.size} flags=${info.flags}]")
            muxer.stop()
            log("remux: tracks=${trackMap.size} samples=$vCount out=${dst.length()}us")
            return dst.exists() && dst.length() > 0
        } catch (e: Exception) {
            log("remux falló: $e")
            try {
                dst.delete()
            } catch (_: Exception) {
            }
            return false
        } finally {
            try {
                extractor?.release()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Reordena presentación por PTS crudos. El extractor entrega en DECODE
     * order (con B-frames ≠ presentation order); sellar secuencial directo
     * rompe la cadencia (duración exacta pero movimiento desparejo/lento).
     * Buffer de lookahead K: junta, ordena por pts crudos (estable), suelta
     * el menor. Solo video (el audio siempre viene ordenado). Testeable JVM.
     */
    class PtsReorder(private val depth: Int = 16) {
        data class Item(
            val seq: Long,
            val pts: Long,
            val bytes: ByteArray,
            val size: Int,
            val flags: Int,
        )

        private val buf = ArrayDeque<Item>()

        /** Mete un sample; devuelve el menor listo (o null si junta). */
        fun push(seq: Long, pts: Long, bytes: ByteArray, size: Int, flags: Int): Item? {
            buf.addLast(Item(seq, pts, bytes, size, flags))
            if (buf.size <= depth) return null
            val m = buf.minWithOrNull(compareBy({ it.pts }, { it.seq }))!!
            buf.remove(m)
            return m
        }

        /** Vacía ordenado por pts (estable). */
        fun drain(): List<Item> {
            val r = buf.sortedWith(compareBy({ it.pts }, { it.seq }))
            buf.clear()
            return r
        }
    }
    /** Busca NAL SPS (7) y PPS (8) Annex-B. Sin start code (para csd). */
    fun findSpsPps(src: File): Pair<ByteArray?, ByteArray?> {
        try {
            val d = src.readBytes()
            fun nal(type: Int): ByteArray? {
                var i = 0
                while (i < d.size - 4) {
                    val is3 = d[i] == 0.toByte() && d[i + 1] == 0.toByte() && d[i + 2] == 1.toByte()
                    val is4 = !is3 && i < d.size - 5 && d[i] == 0.toByte() &&
                        d[i + 1] == 0.toByte() && d[i + 2] == 0.toByte() && d[i + 3] == 1.toByte()
                    if (is3 || is4) {
                        val hdrAt = if (is3) i + 3 else i + 4
                        if ((d[hdrAt].toInt() and 0x1F) == type) {
                            var j = hdrAt + 1
                            while (j < d.size - 4) {
                                if (d[j] == 0.toByte() && d[j + 1] == 0.toByte() &&
                                    (d[j + 2] == 1.toByte() ||
                                        (d[j + 2] == 0.toByte() && d[j + 3] == 1.toByte()))
                                ) {
                                    break
                                }
                                j += 1
                            }
                            return d.copyOfRange(hdrAt, minOf(j, hdrAt + 256))
                        }
                        i = hdrAt + 1
                    } else {
                        i += 1
                    }
                }
                return null
            }
            return nal(7) to nal(8)
        } catch (_: Exception) {
            return null to null
        }
    }

    /**
     * Copia un archivo a la galería pública. Devuelve la content:// URI o null.
     * kind = "video" (Movies/DankoTV) o "image" (Pictures/DankoTV).
     */
    fun saveToGallery(
        ctx: Context,
        src: File,
        displayName: String,
        kind: String,
    ): Uri? = try {
        if (!src.exists() || src.length() == 0L) return null
        val (collection, mime, relPath) = when (kind) {
            "image" -> Triple(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                if (displayName.endsWith(".png")) "image/png" else "image/jpeg",
                Environment.DIRECTORY_PICTURES + "/DankoTV",
            )
            else -> Triple(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                if (displayName.endsWith(".mp4")) "video/mp4" else "video/mp2t",
                Environment.DIRECTORY_MOVIES + "/DankoTV",
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = ctx.contentResolver.insert(collection, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
        }
        uri
    } catch (_: Exception) {
        null
    }

    /** Abre el menú compartir (WhatsApp/IG/...) con 1 o varios archivos. */
    fun shareUris(ctx: Context, uris: List<Uri>, video: Boolean) {
        if (uris.isEmpty()) return
        try {
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = if (video) "video/*" else "image/*"
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = if (video) "video/*" else "image/*"
                    putParcelableArrayListExtra(
                        Intent.EXTRA_STREAM, ArrayList(uris),
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            ctx.startActivity(Intent.createChooser(intent, "Compartir"))
        } catch (_: Exception) {
        }
    }
}
