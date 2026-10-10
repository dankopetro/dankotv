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
    /** Dir para el temporal de descarga (AppContainer pasa cacheDir; tests usan default). */
    private val tmpDir: java.io.File? = null,
) {
    private val http: OkHttpClient = http ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // playlists gigantes (88 MB ~26s)
        .build()

    /** Fase 1 (10/10): baja el cuerpo a un archivo temporal a velocidad de red,
     *  SIN tocar la base. Leer "a tirones" con inserts interleaved hacía que el
     *  proveedor cortara la conexión a mitad de la descarga (75 MB de una).
     *  Además valida lo bajado: una transferencia truncada en silencio (sin
     *  error de socket) importaría una lista mocha como si estuviera bien
     *  (VisCard 10/10: 5000 canales → 1). */
    private fun downloadToTemp(url: String): java.io.File {
        val dir = tmpDir ?: java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        if (!dir.isDirectory) dir.mkdirs()
        val tmp = java.io.File.createTempFile("danko-list-", ".m3u", dir)
        val req = Request.Builder().url(url)
            .header("User-Agent", XtreamClient.UA).build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                val expected = resp.header("Content-Length")?.toLongOrNull()
                resp.body!!.byteStream().use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                if (expected != null && tmp.length() != expected) {
                    throw java.io.IOException(
                        "descarga truncada (${tmp.length()}/$expected bytes)",
                    )
                }
                if (!tmpHasM3uHeader(tmp)) {
                    throw java.io.IOException("descarga inválida (sin cabecera M3U)")
                }
            }
        } catch (e: Exception) {
            try {
                tmp.delete()
            } catch (_: Exception) {
            }
            throw e
        }
        return tmp
    }

    /** Lee solo los primeros KB: tiene que aparecer #EXTM3U. */
    private fun tmpHasM3uHeader(tmp: java.io.File): Boolean {
        return try {
            tmp.inputStream().bufferedReader().use { r ->
                val buf = CharArray(8192)
                val n = r.read(buf, 0, buf.size)
                n > 0 && String(buf, 0, n).contains("#EXTM3U", ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Reintenta solo cortes de red (abort/timeout/reset). Los HTTP 4xx/5xx
     *  NO se reintentan: se avisa enseguida (regla del usuario 10/10). */
    private suspend fun downloadToTempRetrying(url: String, attempts: Int = 3): java.io.File {
        var last: java.io.IOException? = null
        repeat(attempts) { i ->
            try {
                return downloadToTemp(url)
            } catch (e: java.io.IOException) {
                if (Regex("HTTP \\d{3}").containsMatchIn(e.message.orEmpty())) throw e
                last = e
                if (i + 1 < attempts) kotlinx.coroutines.delay((i + 1) * 2000L)
            }
        }
        throw last!!
    }

    /** Descarga M3U en DOS FASES: red a archivo temporal (rápido, sin frenadas)
     *  y después parse + inserts desde el archivo (el socket ya está libre).
     *  El camino anterior (parse línea a línea con inserts en el callback de
     *  red) frenaba el socket ~69 veces en listas de 344k y el proveedor
     *  cortaba a mitad ("Software caused connection abort" verificado 10/10).
     *  La vía body.string() completa en RAM reventaba el heap (OOM) y no vuelve. */
    private suspend fun streamM3uIntoDao(url: String, listName: String): Triple<Int, Int, String> {
        val tmp = downloadToTempRetrying(url)
        try {
            val groups = java.util.TreeSet<String>()
            var count = 0
            var anyChannel = false
            val batch = ArrayList<ChannelEntity>(5000)
            // Fase 2: parse + inserts desde el archivo (el socket ya está libre;
            // el runBlocking solo frena lectura de disco, no la red).
            val result = tmp.bufferedReader().use { reader ->
                M3uParser.parseStreaming(reader) { ch ->
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
                        kotlinx.coroutines.runBlocking { dao.insertChannels(rows) }
                    }
                }
            }
            if (batch.isNotEmpty()) {
                kotlinx.coroutines.runBlocking { dao.insertChannels(batch) }
            }
            if (!anyChannel) throw IllegalArgumentException("Sin canales")
            return Triple(count, groups.size, result.epgUrl)
        } finally {
            try {
                tmp.delete()
            } catch (_: Exception) {
            }
        }
    }

    /** Pre-descarga (10/10): si la cuenta ya está al límite, el servidor corta
     *  la descarga a mitad. Se avisa y no se toca nada (fail-open: si la
     *  consulta falla, se intenta igual). Vale para alta y recarga, M3U y Xtream. */
    private suspend fun checkConnections(spec: PlaylistSpec) {
        val creds = when (spec) {
            is PlaylistSpec.M3u -> xtreamCredsFromM3uUrl(spec.url)
            is PlaylistSpec.Xtream -> Triple(spec.host, spec.user, spec.pass)
        } ?: return
        val (active, max) = xtream.connectionUsage(creds.first, creds.second, creds.third)
        if (max > 0 && active >= max) {
            throw java.io.IOException(
                "Límite de conexiones ($active/$max): la cuenta está llena; " +
                    "cerrá otro equipo e intentá de nuevo.",
            )
        }
    }

    suspend fun loadSpec(spec: PlaylistSpec): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            checkConnections(spec)
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

    /**
     * Editor F7: renombra (3 tablas atómico + last_list) y/o recarga desde
     * el nuevo origen. Si el nombre no cambió, solo recarga contenido.
     */
    suspend fun updateSpec(oldName: String, spec: PlaylistSpec): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (spec.name != oldName) {
                    dao.renameListData(oldName, spec.name)
                    if (settings.lastList.first() == oldName) {
                        settings.setLastList(spec.name)
                    }
                }
                loadSpec(spec).getOrThrow()
            }
        }

    suspend fun renameList(oldName: String, newName: String) = withContext(Dispatchers.IO) {
        dao.renameListData(oldName, newName)
        if (settings.lastList.first() == oldName) {
            settings.setLastList(newName)
        }
    }

    suspend fun setHidden(list: String, url: String, hidden: Boolean) =
        dao.setHidden(list, url, hidden)

    suspend fun hiddenPage(list: String) = dao.hiddenChannels(list, 2000)
    suspend fun channels(list: String) = dao.channels(list)

    // --- páginas limitadas (344k canales): display 2000 + COUNT del total ---

    suspend fun channelsPage(list: String) = dao.channelsLimited(list, 2000)
    suspend fun channelsPaged(list: String, group: String?, limit: Int, offset: Int) =
        dao.channelsPaged(list, group, limit, offset)
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

/** Mensaje amable para fallos de alta/recarga (regla del usuario 10/10 + espejo
 *  de classify_error en dankotv/engine.py):
 *  - Si el servidor no da 200 (401/403/429/404/5xx): se avisa y NO se toca nada.
 *  - hadPrevious=true (recarga): se sigue con los canales guardados en la base.
 *  - hadPrevious=false (primera vez): no hay nada guardado, se avisa igual. */
fun loadErrorMessage(err: Throwable?, hadPrevious: Boolean): String {
    val verb = if (hadPrevious) "recargar" else "cargar"
    val keep = if (hadPrevious) {
        "Se mantiene la lista guardada."
    } else {
        "Todavía no hay lista guardada."
    }
    val s = err?.message.orEmpty()
    val code = Regex("HTTP (\\d{3})").find(s)?.groupValues?.getOrNull(1)
    if (code != null) {
        return when (code) {
            "401" -> "No se pudo $verb (401: credenciales rechazadas). $keep"
            "403", "429" -> "No se pudo $verb (HTTP $code: bloqueo temporal del servidor). $keep"
            "404" -> "No se pudo $verb (404: lista no encontrada). $keep"
            else -> "No se pudo $verb (HTTP $code). $keep"
        }
    }
    val low = s.lowercase()
    return when {
        "límite de conexiones" in low -> "No se pudo $verb (${s.trim()}). $keep"
        "timeout" in low || "abort" in low || "reset" in low ||
            "failed to connect" in low || "unable to resolve" in low ||
            "unreachable" in low || "end of stream" in low ->
            "No se pudo $verb (corte de red a mitad de descarga). $keep"
        s.isBlank() -> "No se pudo $verb. $keep"
        else -> "No se pudo $verb ($s). $keep"
    }
}
