package tv.danko.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.danko.app.data.db.EpgProgramme
import tv.danko.app.data.db.PlaylistDao
import tv.danko.app.data.model.PlaylistSpec

/** Programa EPG normalizado (ms epoch UTC, sin adivinar zonas horarias). */
data class EpgEntry(
    val title: String,
    val desc: String,
    val startMs: Long,
    val endMs: Long,
)

/** Títulos/descripciones Xtream vienen en base64; si no decodifica, crudo. */
fun decodeB64(s: String?): String {
    if (s.isNullOrEmpty()) return ""
    return try {
        String(java.util.Base64.getDecoder().decode(s.trim()), Charsets.UTF_8)
    } catch (_: Exception) {
        s
    }
}

/**
 * stream_id desde la URL de reproducción. Xtream API:
 * `host/live/user/pass/123.ts`; M3U estilo Xtream: `host/user/pass/12345`.
 * Devuelve el último segmento numérico del path.
 */
fun streamIdFromUrl(url: String): Int? {
    val path = url.substringBefore('?').trimEnd('/')
    val last = path.substringAfterLast('/')
    return last.toIntOrNull() ?: last.substringBefore('.').toIntOrNull()
}

/**
 * Credenciales Xtream desde una URL M3U `get.php`:
 * `http://host/.../get.php?username=U&password=P&type=m3u_plus`.
 */
fun xtreamCredsFromM3uUrl(url: String): Triple<String, String, String>? {
    return try {
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return null
        val params = q.split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }.toMap()
        val user = params["username"] ?: return null
        val pass = params["password"] ?: return null
        val host = "http://" + url.substringAfter("://").substringBefore('/')
        Triple(host, user, pass)
    } catch (_: Exception) {
        null
    }
}

/** De una lista ordenada, el programa en curso y el siguiente a [nowMs]. */
fun pickNowNext(list: List<EpgEntry>, nowMs: Long): Pair<EpgEntry?, EpgEntry?> {
    val sorted = list.sortedBy { it.startMs }
    val now = sorted.firstOrNull { nowMs in it.startMs until it.endMs }
    val next = sorted.firstOrNull { it.startMs >= (now?.endMs ?: nowMs) && it != now }
        ?: sorted.firstOrNull { it.startMs >= nowMs && it != now }
    return now to next
}

/**
 * EPG ahora/siguiente con caché Room (TTL 6h). Resuelve credenciales del
 * spec guardado (Xtream directo) o parseando la URL M3u get.php, y el
 * stream_id desde la URL de reproducción. Sin red si el caché cubre.
 */
class EpgRepository(
    private val dao: PlaylistDao,
    private val lists: ListsRepository,
    private val xtream: XtreamClient = XtreamClient(),
) {
    companion object {
        const val TTL_MS = 6L * 3600 * 1000
        const val LIMIT = 4
    }

    suspend fun nowNext(listName: String, channelUrl: String): Pair<EpgEntry?, EpgEntry?> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            try {
                val cached = dao.epgFor(listName, channelUrl, now)
                val fresh = cached.isNotEmpty() &&
                    cached.all { now - it.fetchedAt < TTL_MS } &&
                    cached.any { now in it.startMs until it.endMs }
                val entries = if (fresh) {
                    cached.map { EpgEntry(it.title, it.desc, it.startMs, it.endMs) }
                } else {
                    fetchAndStore(listName, channelUrl) ?: return@withContext null to null
                }
                pickNowNext(entries, now)
            } catch (_: Exception) {
                null to null
            }
        }

    private suspend fun fetchAndStore(listName: String, channelUrl: String): List<EpgEntry>? {
        val spec = lists.specOf(listName) ?: return null
        val (host, user, pass) = when (spec) {
            is PlaylistSpec.Xtream -> Triple(spec.host, spec.user, spec.pass)
            is PlaylistSpec.M3u -> {
                val u = spec.url
                xtreamCredsFromM3uUrl(u) ?: return null
            }
        }
        val streamId = streamIdFromUrl(channelUrl) ?: return null
        val entries = xtream.getShortEpg(host, user, pass, streamId, LIMIT)
        if (entries.isEmpty()) return null
        val now = System.currentTimeMillis()
        dao.replaceEpg(
            listName, channelUrl,
            entries.map {
                EpgProgramme(
                    listName = listName, channelUrl = channelUrl,
                    startMs = it.startMs, endMs = it.endMs,
                    title = it.title, desc = it.desc, fetchedAt = now,
                )
            },
        )
        return entries
    }
}
