package tv.danko.app.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.danko.app.data.model.Channel
import tv.danko.app.data.model.LoadResult
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
    )

    private fun get(url: String, retries: Int = 3): String {
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
}
