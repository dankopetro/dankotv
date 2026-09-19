package tv.danko.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.danko.app.data.db.ChannelEntity
import tv.danko.app.data.db.FavoriteEntity
import tv.danko.app.data.db.ListEntity
import tv.danko.app.data.db.PlaylistDao
import tv.danko.app.data.db.escapeLike
import tv.danko.app.data.model.LoadResult
import tv.danko.app.data.model.PlaylistSpec
import java.util.concurrent.TimeUnit

/**
 * Orquesta descarga + parseo + guardado. Espejo de load_spec()/load_and_store()
 * en dankotv/home.py (sin UI: el llamante decide hilos/vistas).
 */
class ListsRepository(
    private val dao: PlaylistDao,
    private val settings: SettingsStore,
    http: OkHttpClient? = null,
    private val xtream: XtreamClient = XtreamClient(http),
) {
    private val http: OkHttpClient = http ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // playlists gigantes (88 MB ~26s)
        .build()

    /** Descarga M3U en STREAMING: parse línea a línea + insert por lotes.
     *  El camino anterior (body.string() + lista de 344k en RAM) reventaba
     *  el heap Android (OOM verificado en device). */
    private fun streamM3uIntoDao(url: String, listName: String): Triple<Int, Int, String> {
        val req = Request.Builder().url(url)
            .header("User-Agent", XtreamClient.UA).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val groups = java.util.TreeSet<String>()
            var count = 0
            var anyChannel = false
            val batch = ArrayList<ChannelEntity>(5000)
            val result = M3uParser.parseStreaming(resp.body!!.charStream()) { ch ->
                anyChannel = true
                groups.add(ch.group)
                batch.add(
                    ChannelEntity(
                        listName = listName, title = ch.title, group = ch.group,
                        url = ch.url, logo = ch.logo, epgId = ch.epgId,
                    ),
                )
                count += 1
                if (batch.size >= 5000) {
                    val rows = ArrayList(batch)
                    batch.clear()
                    // runBlocking OK: corré en Dispatchers.IO (hilo de trabajo)
                    kotlinx.coroutines.runBlocking { dao.insertChannels(rows) }
                }
            }
            if (batch.isNotEmpty()) {
                kotlinx.coroutines.runBlocking { dao.insertChannels(batch) }
            }
            if (!anyChannel) throw IllegalArgumentException("Sin canales")
            return Triple(count, groups.size, result.epgUrl)
        }
    }

    suspend fun loadSpec(spec: PlaylistSpec): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            when (spec) {
                is PlaylistSpec.M3u -> {
                    // Staging + swap atómico: si la descarga falla a mitad, la
                    // lista anterior SIGUE intacta (antes el clear previo la
                    // dejaba en 0 — bug 17/09 verificado en device).
                    val staging = "${spec.name}__staging"
                    dao.clearChannels(staging)
                    try {
                        val (count, groupCount, epg) = streamM3uIntoDao(spec.url, staging)
                        if (count == 0) throw IllegalArgumentException("Sin canales")
                        dao.swapChannels(spec.name, staging)
                        dao.upsertList(
                            ListEntity(
                                name = spec.name, type = "m3u", url = spec.url,
                                channelCount = count, groupCount = groupCount,
                                epgUrl = epg, updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    } catch (e: Exception) {
                        try {
                            dao.clearChannels(staging)
                        } catch (_: Exception) {
                        }
                        throw e
                    }
                }
                is PlaylistSpec.Xtream -> {
                    val res = xtream.loadLive(spec.host, spec.user, spec.pass)
                    if (res.channels.isEmpty()) throw IllegalArgumentException("Sin canales")
                    dao.upsertList(
                        ListEntity(
                            name = spec.name, type = "xtream", host = spec.host,
                            user = spec.user, pass = spec.pass,
                            channelCount = res.channels.size, groupCount = res.groups.size,
                            epgUrl = res.epgUrl, updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    dao.replaceChannels(
                        spec.name,
                        res.channels.map {
                            ChannelEntity(
                                listName = spec.name, title = it.title, group = it.group,
                                url = it.url, logo = it.logo, epgId = it.epgId,
                            )
                        },
                    )
                }
            }
            settings.setLastList(spec.name)
        }
    }

    suspend fun lists() = dao.lists()
    suspend fun deleteList(name: String) {
        dao.deleteList(name)
        dao.clearFavorites(name)
    }
    suspend fun channels(list: String) = dao.channels(list)

    // --- páginas limitadas (344k canales): display 2000 + COUNT del total ---

    suspend fun channelsPage(list: String) = dao.channelsLimited(list, 2000)
    suspend fun countChannels(list: String) = dao.countChannels(list)
    suspend fun groupPage(list: String, group: String) = dao.channelsInGroup(list, group, 2000)
    suspend fun countInGroup(list: String, group: String) = dao.countInGroup(list, group)
    suspend fun searchPage(list: String, q: String): Pair<List<ChannelEntity>, Int> {
        val e = q.escapeLike()
        return dao.search(list, e) to dao.countSearch(list, e)
    }
    suspend fun favoritesPage(list: String) = dao.favoriteChannels(list, 2000)
    suspend fun searchFavoritesPage(list: String, q: String): Pair<List<ChannelEntity>, Int> {
        val e = q.escapeLike()
        return dao.searchFavorites(list, e) to dao.countSearchFavorites(list, e)
    }
    suspend fun searchInGroupPage(list: String, group: String, q: String): Pair<List<ChannelEntity>, Int> {
        val e = q.escapeLike()
        return dao.searchInGroup(list, group, e) to dao.countSearchInGroup(list, group, e)
    }
    suspend fun groups(list: String) = dao.groups(list)
    suspend fun search(list: String, q: String) = dao.search(list, q.escapeLike())
    suspend fun favoriteUrls(list: String) = dao.favoriteUrls(list).toSet()
    suspend fun addFavorite(list: String, url: String) =
        dao.addFavorite(FavoriteEntity(list, url))
    suspend fun removeFavorite(list: String, url: String) = dao.removeFavorite(list, url)
    suspend fun lastListName(): String = settings.lastList.first()

    /** Reconstruye el spec desde la lista guardada (para reload). */
    suspend fun specOf(name: String): PlaylistSpec? {
        val e = lists().firstOrNull { it.name == name } ?: return null
        return when (e.type) {
            "m3u" -> e.url?.let { PlaylistSpec.M3u(name, it) }
            "xtream" -> if (e.host != null && e.user != null && e.pass != null) {
                PlaylistSpec.Xtream(name, e.host!!, e.user!!, e.pass!!)
            } else null
            else -> null
        }
    }

    /** Prueba una lista sin guardarla: devuelve cantidad de canales (test_connection). */
    suspend fun testSpec(spec: PlaylistSpec): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            when (spec) {
                is PlaylistSpec.M3u -> {
                    val req = Request.Builder().url(spec.url)
                        .header("User-Agent", XtreamClient.UA).build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                        M3uParser.parse(resp.body!!.string()).channels.size
                    }
                }
                is PlaylistSpec.Xtream ->
                    xtream.loadLive(spec.host, spec.user, spec.pass).channels.size
            }
        }
    }
}
