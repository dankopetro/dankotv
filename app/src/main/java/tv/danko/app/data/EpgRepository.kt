package tv.danko.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

/** ¿Alguno cubre [nowMs]? (filtro de filas con datos para la grilla). */
fun hasNow(programmes: List<EpgEntry>, nowMs: Long): Boolean =
    programmes.any { nowMs in it.startMs until it.endMs }

/** De pares (streamId, epgId) crudos al mapa (testeable JVM). */
fun buildEpgMap(pairs: List<Pair<Int, String>>): Map<Int, String> =
    pairs.filter { it.first >= 0 && it.second.isNotEmpty() }.toMap()

/**
 * Candidatos a consultar: si hay mapa EPG, SOLO esos (en orden); si no,
 * los primeros [cap] a ciegas. (Testeable JVM.)
 */
fun filterCandidates(urls: List<String>, epgIds: Set<Int>?, cap: Int): List<String> {
    if (epgIds == null) return urls.take(cap)
    if (epgIds.isEmpty()) return emptyList()
    return urls.filter { streamIdFromUrl(it) in epgIds }.take(cap)
}

/** De una lista ordenada, el programa en curso y el siguiente a [nowMs]. */fun pickNowNext(list: List<EpgEntry>, nowMs: Long): Pair<EpgEntry?, EpgEntry?> {
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
        // 8 programas cubren ~12h aun con los solapes del proveedor (con 4
        // no se llegaba al "siguiente" — bug 17/09).
        const val LIMIT = 8
        const val MAP_TTL_MS = 24L * 3600 * 1000
        const val MAX_ROWS = 60
    }

    // Mapa stream_id→epgId en memoria por lista (una llamada bulk lo trae).
    private var epgMapCache: Triple<String, Long, Map<Int, String>>? = null

    /** IDs con EPG de la lista (null si no se pueden resolver credenciales). */
    suspend fun epgStreamIds(listName: String): Set<Int>? = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            epgMapCache?.let { (name, at, map) ->
                if (name == listName && now - at < MAP_TTL_MS) return@withContext map.keys
            }
            val spec = lists.specOf(listName) ?: return@withContext null
            val (host, user, pass) = when (spec) {
                is PlaylistSpec.Xtream -> Triple(spec.host, spec.user, spec.pass)
                is PlaylistSpec.M3u -> xtreamCredsFromM3uUrl(spec.url) ?: return@withContext null
            }
            val map = xtream.getLiveEpgMap(host, user, pass)
            android.util.Log.i("DankoTV", "EPG map $listName: ${map.size} con guía")
            epgMapCache = Triple(listName, now, map)
            map.keys
        } catch (e: Exception) {
            android.util.Log.w("DankoTV", "EPG map falló: $e")
            null
        }
    }

    suspend fun nowNext(listName: String, channelUrl: String): Pair<EpgEntry?, EpgEntry?> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            try {
                pickNowNext(entriesFor(listName, channelUrl, now), now)
            } catch (_: Exception) {
                null to null
            }
        }

    /** Entradas (caché o red) para un canal. Base de nowNext y de la grilla. */
    suspend fun entriesFor(
        listName: String,
        channelUrl: String,
        nowMs: Long,
        force: Boolean = false,
    ): List<EpgEntry> {
        if (!force) {
            val cached = dao.epgFor(listName, channelUrl, nowMs)
            val fresh = cached.isNotEmpty() &&
                cached.all { nowMs - it.fetchedAt < TTL_MS } &&
                cached.any { nowMs in it.startMs until it.endMs }
            if (fresh) return cached.map { EpgEntry(it.title, it.desc, it.startMs, it.endMs) }
        }
        return fetchAndStore(listName, channelUrl) ?: emptyList()
    }

    /** Fila de la grilla para un canal (vacía si no hay datos). */
    data class GuideRow(val channelUrl: String, val programmes: List<EpgEntry>)

    /**
     * Grilla bulk: trae EPG de varios canales en paralelo (de a 8) y devuelve
     * SOLO los que tienen datos (auto-detección pedida por el usuario).
     */
    suspend fun guideRows(
        listName: String,
        channelUrls: List<String>,
        force: Boolean = false,
    ): List<GuideRow> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // Mapa previo: solo vale la pena consultar los que tienen guía (si no
        // hay mapa —lista no-Xtream— se prueba a ciegas con tope chico).
        val epgIds = try {
            epgStreamIds(listName)
        } catch (_: Exception) {
            null
        }
        val cap = if (epgIds != null) MAX_ROWS else 40
        val candidates = filterCandidates(channelUrls, epgIds, cap)
        android.util.Log.i(
            "DankoTV",
            "EPG grilla $listName: ${channelUrls.size} urls, ${candidates.size} candidatas",
        )
        val out = mutableListOf<GuideRow>()
        for (chunk in candidates.chunked(8)) {
            val jobs = chunk.map { url ->
                async {
                    try {
                        url to entriesFor(listName, url, now, force)
                    } catch (_: Exception) {
                        url to emptyList()
                    }
                }
            }
            for ((url, entries) in jobs.map { it.await() }) {
                if (hasNow(entries, now)) {
                    out.add(GuideRow(url, entries.sortedBy { it.startMs }))
                }
            }
        }
        out
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
