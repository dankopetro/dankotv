package tv.danko.app.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.danko.app.data.model.Channel
import tv.danko.app.data.model.Episode
import tv.danko.app.data.model.LoadResult
import tv.danko.app.data.model.SeasonInfo
import tv.danko.app.data.model.SeriesDetail
import tv.danko.app.data.model.SeriesItem
import tv.danko.app.data.model.VodCategory
import tv.danko.app.data.model.VodItem
import java.io.IOException
import java.util.concurrent.TimeUnit

class XtreamAuthException(msg: String) : IOException(msg)

/**
 * Cliente Xtream (solo TV en vivo en F1; VOD/series en fase posterior).
 * Port de usr/lib/dankoiptv/thirdparty/xtream.py — mismos endpoints, mismos
 * mapeos de campos y misma URL de stream.
 */
class XtreamClient(
    okHttp: OkHttpClient? = null,
    private val gson: Gson = Gson(),
) {
    private val http: OkHttpClient = okHttp ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Cliente corto para APIs chicas (EPG): el default de 120s es para
     * playlists gigantes; un endpoint colgado no puede frenar la grilla
     * 6 minutos por canal (bug 19/09).
     */
    private val apiHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        /**
         * UA SOLO para descargar playlists/EPG (get.php, player_api.php). Los
         * paneles suelen aceptar cualquiera para DESCARGA. El UA de REPRODUCCIÓN
         * es otro asunto: verificado con superxlatino — el stream rechaza 401 a
         * Wget pero sirve 302→200 a "libmpv"/"Mozilla/5.0". La app NO setea
         * user-agent en mpv salvo que la playlist lo pida (url-commands de Kodi
         * llegan en F6): mpv usa su default "libmpv x.y" que los paneles aceptan.
         * (Bug real 13/09: el UA Wget de descarga se colaba a la reproducción →
         * 401 en todos los canales mientras Linux con default mpv andaba.)
         */
        const val UA = "Mozilla/5.0"
        const val CATCH_ALL = "xEverythingElse"
        /** Corrección horaria EPG, ver getShortEpg. */
        const val EPG_TZ_SHIFT_MS = 3L * 3600 * 1000
    }

    private data class AuthResponse(val user_info: UserInfo?)
    private data class UserInfo(val username: String?, val password: String?)
    private data class Category(
        @SerializedName("category_id") val categoryId: String?,
        @SerializedName("category_name") val categoryName: String?,
    )
    private data class Stream(
        @SerializedName("stream_id") val streamId: Int?,
        @SerializedName("name") val name: String?,
        @SerializedName("stream_icon") val streamIcon: String?,
        @SerializedName("category_id") val categoryId: String?,
        @SerializedName("stream_type") val streamType: String?,
        @SerializedName("epg_channel_id") val epgChannelId: String?,
    )

    private data class ShortEpgResponse(val epg_listings: List<ShortEpgListing>?)
    private data class ShortEpgListing(
        val title: String?,
        val description: String?,
        @SerializedName("start_timestamp") val startTs: String?,
        @SerializedName("stop_timestamp") val stopTs: String?,
    )

    // --- VOD / series (F6). IDs tolerantes: el panel a veces manda String. ---
    private data class VodCategoryRaw(
        @SerializedName("category_id") val categoryId: String?,
        @SerializedName("category_name") val categoryName: String?,
    )

    private data class VodStreamRaw(
        @SerializedName("stream_id") val streamId: com.google.gson.JsonElement?,
        @SerializedName("name") val name: String?,
        @SerializedName("stream_icon") val streamIcon: String?,
        @SerializedName("rating") val rating: String?,
        @SerializedName("category_id") val categoryId: String?,
        @SerializedName("container_extension") val ext: String?,
    )

    private data class SeriesEntryRaw(
        @SerializedName("series_id") val seriesId: com.google.gson.JsonElement?,
        @SerializedName("name") val name: String?,
        @SerializedName("cover") val cover: String?,
        @SerializedName("plot") val plot: String?,
        @SerializedName("rating") val rating: String?,
        @SerializedName("category_id") val categoryId: String?,
    )

    private data class SeriesInfoRaw(
        val seasons: List<SeasonEntryRaw>?,
        val episodes: Map<String, List<EpisodeEntryRaw>>?,
    )

    private data class SeasonEntryRaw(
        @SerializedName("season_number") val number: com.google.gson.JsonElement?,
        @SerializedName("name") val name: String?,
        @SerializedName("cover") val cover: String?,
    )

    private data class EpisodeEntryRaw(
        val id: com.google.gson.JsonElement?,
        @SerializedName("episode_num") val epNum: com.google.gson.JsonElement?,
        val title: String?,
        @SerializedName("container_extension") val ext: String?,
    )

    /** Int tolerante (el panel mezcla números y strings según el campo). */
    private fun jsonInt(el: com.google.gson.JsonElement?): Int? = try {
        when {
            el == null || el.isJsonNull || !el.isJsonPrimitive -> null
            else -> try {
                el.asInt
            } catch (_: Exception) {
                el.asString.toIntOrNull()
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun get(url: String, retries: Int = 3, readSecs: Long = 120): String {
        var last: IOException? = null
        repeat(retries) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", UA).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    return resp.body!!.string()
                }
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("request failed: $url")
    }

    /** GET corto (APIs EPG): 2 intentos, 15s de lectura. */
    private fun getApi(url: String, retries: Int = 2): String {
        var last: IOException? = null
        repeat(retries) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", UA).build()
                apiHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    return resp.body!!.string()
                }
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("request failed: $url")
    }

    suspend fun loadLive(host: String, user: String, pass: String): LoadResult =
        withContext(Dispatchers.IO) {
            val h = host.trimEnd('/')
            val authJson = get("$h/player_api.php?username=$user&password=$pass")
            val auth = gson.fromJson(authJson, AuthResponse::class.java)
            val authUser = auth.user_info?.username
            val authPass = auth.user_info?.password
            if (authUser.isNullOrEmpty() || authPass.isNullOrEmpty()) {
                throw XtreamAuthException("Xtream: autenticación fallida (revisa host/usuario/clave)")
            }
            val base = "$h/player_api.php?username=$user&password=$pass"
            val cats: List<Category> = gson.fromJson(
                get("$base&action=get_live_categories"),
                Array<Category>::class.java,
            ).toList()
            val catName = cats.mapNotNull {
                val id = it.categoryId
                val name = it.categoryName
                if (id != null && name != null) id to name else null
            }.toMap()
            val streams: List<Stream> = gson.fromJson(
                get("$base&action=get_live_streams"),
                Array<Stream>::class.java,
            ).toList()

            val channels = mutableListOf<Channel>()
            for (s in streams) {
                val name = s.name?.trim().orEmpty()
                if (name.isEmpty()) continue // como el original: salta sin nombre
                val type = when (s.streamType) {
                    "live", "created_live", "radio_streams" -> "live"
                    else -> continue // VOD/series/desconocidos: no en F1
                }
                val group = catName[s.categoryId] ?: CATCH_ALL
                val id = s.streamId ?: continue
                channels.add(
                    Channel(
                        title = name,
                        group = group,
                        url = "$h/$type/$authUser/$authPass/$id.ts",
                        logo = s.streamIcon.orEmpty(),
                    )
                )
            }
            if (channels.isEmpty()) throw IOException("Xtream: sin canales en vivo")
            LoadResult(channels, channels.map { it.group }.toSortedSet().toList())
        }

    /**
     * Mapa stream_id → epg_channel_id (solo los que tienen EPG). UNA llamada
     * bulk: así la grilla sabe QUÉ canales vale la pena consultar en vez de
     * probar a ciegas los primeros 40 (casi nunca tienen guía — bug 17/09).
     */
    suspend fun getLiveEpgMap(
        host: String,
        user: String,
        pass: String,
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        val h = host.trimEnd('/')
        val streams: List<Stream> = gson.fromJson(
            getApi("$h/player_api.php?username=$user&password=$pass&action=get_live_streams"),
            Array<Stream>::class.java,
        ).toList()
        buildEpgMap(streams.map { (it.streamId ?: -1) to (it.epgChannelId.orEmpty()) })
    }

    /**
     * EPG corto de un stream (ahora/siguiente). Títulos en base64 (estándar Xtream).
     * EPG_TZ_SHIFT_MS: estos paneles etiquetan hora local (UTC-3 Argentina)
     * como si fuera UTC → todo llega 3h temprano (verificado 19/09: River
     * 15:45 vs transmisión real 18:45). Se corrige acá una sola vez.
     */
    suspend fun getShortEpg(
        host: String,
        user: String,
        pass: String,
        streamId: Int,
        limit: Int = 4,
    ): List<EpgEntry> = withContext(Dispatchers.IO) {
        val h = host.trimEnd('/')
        val json = getApi(
            "$h/player_api.php?username=$user&password=$pass" +
                "&action=get_short_epg&stream_id=$streamId&limit=$limit",
        )
        val resp = gson.fromJson(json, ShortEpgResponse::class.java)
        (resp.epg_listings ?: emptyList()).mapNotNull {
            val startMs = it.startTs?.toLongOrNull()?.times(1000)?.plus(EPG_TZ_SHIFT_MS)
                ?: return@mapNotNull null
            val endMs = it.stopTs?.toLongOrNull()?.times(1000)?.plus(EPG_TZ_SHIFT_MS)
                ?: return@mapNotNull null
            EpgEntry(decodeB64(it.title), decodeB64(it.description), startMs, endMs)
        }
    }

    /** Categorías VOD. */
    suspend fun getVodCategories(host: String, user: String, pass: String): List<VodCategory> =
        withContext(Dispatchers.IO) {
            val h = host.trimEnd('/')
            gson.fromJson(
                getApi("$h/player_api.php?username=$user&password=$pass&action=get_vod_categories"),
                Array<VodCategoryRaw>::class.java,
            ).toList().mapNotNull {
                val id = it.categoryId ?: return@mapNotNull null
                val name = it.categoryName?.trim().orEmpty()
                if (name.isEmpty()) null else VodCategory(id, name)
            }
        }

    /** Películas (opcionalmente de una categoría). */
    suspend fun getVodStreams(
        host: String,
        user: String,
        pass: String,
        categoryId: String? = null,
    ): List<VodItem> = withContext(Dispatchers.IO) {
        val h = host.trimEnd('/')
        val cat = if (categoryId != null) "&category_id=$categoryId" else ""
        gson.fromJson(
            getApi("$h/player_api.php?username=$user&password=$pass&action=get_vod_streams$cat"),
            Array<VodStreamRaw>::class.java,
        ).toList().mapNotNull {
            val id = jsonInt(it.streamId) ?: return@mapNotNull null
            val name = it.name?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            VodItem(
                id = id, title = name, icon = it.streamIcon.orEmpty(),
                rating = it.rating.orEmpty(), catId = it.categoryId.orEmpty(),
                ext = it.ext?.trim().orEmpty().ifEmpty { "mp4" },
            )
        }
    }

    /** Categorías de series. */
    suspend fun getSeriesCategories(host: String, user: String, pass: String): List<VodCategory> =
        withContext(Dispatchers.IO) {
            val h = host.trimEnd('/')
            gson.fromJson(
                getApi("$h/player_api.php?username=$user&password=$pass&action=get_series_categories"),
                Array<VodCategoryRaw>::class.java,
            ).toList().mapNotNull {
                val id = it.categoryId ?: return@mapNotNull null
                val name = it.categoryName?.trim().orEmpty()
                if (name.isEmpty()) null else VodCategory(id, name)
            }
        }

    /** Series (opcionalmente de una categoría). */
    suspend fun getSeries(
        host: String,
        user: String,
        pass: String,
        categoryId: String? = null,
    ): List<SeriesItem> = withContext(Dispatchers.IO) {
        val h = host.trimEnd('/')
        val cat = if (categoryId != null) "&category_id=$categoryId" else ""
        gson.fromJson(
            getApi("$h/player_api.php?username=$user&password=$pass&action=get_series$cat"),
            Array<SeriesEntryRaw>::class.java,
        ).toList().mapNotNull {
            val id = jsonInt(it.seriesId) ?: return@mapNotNull null
            val name = it.name?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            SeriesItem(
                id = id, title = name, cover = it.cover.orEmpty(),
                plot = it.plot.orEmpty(), rating = it.rating.orEmpty(),
            )
        }
    }

    /** Temporadas + episodios de una serie. */
    suspend fun getSeriesDetail(
        host: String,
        user: String,
        pass: String,
        seriesId: Int,
    ): SeriesDetail = withContext(Dispatchers.IO) {
        val h = host.trimEnd('/')
        val info = gson.fromJson(
            getApi("$h/player_api.php?username=$user&password=$pass&action=get_series_info&series_id=$seriesId"),
            SeriesInfoRaw::class.java,
        )
        val seasons = (info.seasons ?: emptyList()).mapNotNull {
            val n = jsonInt(it.number) ?: return@mapNotNull null
            SeasonInfo(n, it.name.orEmpty(), it.cover.orEmpty())
        }.sortedBy { it.number }
        val episodes = (info.episodes ?: emptyMap()).flatMap { (season, list) ->
            val sn = season.toIntOrNull() ?: return@flatMap emptyList()
            list.mapNotNull {
                val id = jsonInt(it.id) ?: return@mapNotNull null
                Episode(
                    id = id, season = sn,
                    epNum = jsonInt(it.epNum) ?: 0,
                    title = it.title?.trim().orEmpty(),
                    ext = it.ext?.trim().orEmpty().ifEmpty { "mp4" },
                )
            }
        }.sortedWith(compareBy({ it.season }, { it.epNum }))
        SeriesDetail(seasons, episodes)
    }
}

/** URL de reproducción de una película (port del stream_url de xtream.py). */
fun vodUrl(host: String, user: String, pass: String, id: Int, ext: String): String {
    val e = ext.trim().ifEmpty { "mp4" }
    return "${host.trimEnd('/')}/movie/$user/$pass/$id.$e"
}

/** URL de reproducción de un episodio de serie. */
fun episodeUrl(host: String, user: String, pass: String, id: Int, ext: String): String {
    val e = ext.trim().ifEmpty { "mp4" }
    return "${host.trimEnd('/')}/series/$user/$pass/$id.$e"
}
