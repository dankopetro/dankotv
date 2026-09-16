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
    fun remuxTsToMp4(src: File, dst: File): Boolean {
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
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    tracks.add(Track(i, format, mime.startsWith("video/")))
                }
            }
            android.util.Log.i("DankoTV", "remux: ${tracks.size} tracks en ${src.name}")
            if (tracks.isEmpty()) {
                android.util.Log.w("DankoTV", "remux: sin tracks A/V en ${src.name}")
                return false
            }
            val video = tracks.firstOrNull { it.isVideo }
            if (video != null && !video.format.containsKey("csd-0")) {
                val (sps, pps) = findSpsPps(src)
                android.util.Log.i(
                    "DankoTV",
                    "remux: csd ausente, SPS=${sps?.size} PPS=${pps?.size}",
                )
                if (sps != null) video.format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps))
                if (pps != null) video.format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps))
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
            // Arrancar en keyframe de video (el corte es mid-GOP).
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
            muxer.start()
            val buf = java.nio.ByteBuffer.allocate(512 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            var samples = 0
            var first = ""
            try {
                while (true) {
                    val track = extractor.sampleTrackIndex
                    if (track < 0) break
                    info.offset = 0
                    info.size = extractor.readSampleData(buf, 0)
                    if (info.size < 0) break
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    if (samples == 0) {
                        first = "track=$track size=${info.size} flags=${info.flags} pts=${info.presentationTimeUs}"
                    }
                    muxer.writeSampleData(trackMap[track] ?: break, buf, info)
                    extractor.advance()
                    samples += 1
                }
            } catch (e: Exception) {
                android.util.Log.w("DankoTV", "remux: write falló en sample $samples: $e")
                throw e
            }
            android.util.Log.i("DankoTV", "remux: pre-stop samples=$samples first=[$first]")
            muxer.stop()
            android.util.Log.i(
                "DankoTV",
                "remux: tracks=${trackMap.size} samples=$samples out=${dst.length()}",
            )
            return dst.exists() && dst.length() > 0
        } catch (e: Exception) {
            android.util.Log.w("DankoTV", "remux falló: $e")
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
     * Busca NAL SPS (7) y PPS (8) Annex-B (00 00 01 67/68) en el archivo.
     * Devuelve los NAL SIN start code (como los quiere csd-0/csd-1).
     */
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
