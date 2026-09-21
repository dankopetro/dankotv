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

/**
 * URL apta para Chromecast (F8): el receptor default NO reproduce .ts en
 * vivo (la TV queda girando — bug 21/09). Los paneles Xtream sirven el mismo
 * stream en HLS (`host/live/user/pass/id.m3u8`), que sí entiende.
 * Solo reescribe vivo estilo Xtream con id numérico; el resto pasa igual.
 */
fun castStreamUrl(url: String, live: Boolean): String {
    if (!live) return url
    return try {
        val noQuery = url.substringBefore('?')
        if (noQuery.contains(".m3u8")) return url
        val schemeEnd = noQuery.indexOf("://")
        if (schemeEnd < 0) return url
        val scheme = noQuery.substring(0, schemeEnd)
        if (scheme != "http" && scheme != "https") return url
        val segs = noQuery.substring(schemeEnd + 3).split('/').filter { it.isNotEmpty() }
        if (segs.size < 4) return url
        val id = segs.last().substringBefore('.').toIntOrNull() ?: return url
        val host = segs[0]
        // Pelis/series van directo (el receptor sí reproduce mkv/mp4).
        if (segs[1] == "movie" || segs[1] == "series") return url
        val (user, pass) = if (segs[1] == "live") {
            if (segs.size < 5) return url
            segs[2] to segs[3]
        } else {
            segs[1] to segs[2]
        }
        if (user.isEmpty() || pass.isEmpty()) return url
        "$scheme://$host/live/$user/$pass/$id.m3u8"
    } catch (_: Exception) {
        url
    }
}
