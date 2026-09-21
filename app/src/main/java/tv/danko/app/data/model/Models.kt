package tv.danko.app.data.model

/** Canal normalizado. Espejo de norm_m3u() en dankotv/engine.py. */
data class Channel(
    val title: String,
    val group: String,
    val url: String,
    val logo: String = "",
    val epgId: String = "",
)

/** Especificación de lista (lo que pide NewListDialog en Linux). */
sealed interface PlaylistSpec {
    val name: String

    data class M3u(override val name: String, val url: String) : PlaylistSpec

    data class Xtream(
        override val name: String,
        val host: String,
        val user: String,
        val pass: String,
    ) : PlaylistSpec
}

/** Resultado de cargar una lista: canales + grupos ordenados + URL EPG (para F6). */
data class LoadResult(
    val channels: List<Channel>,
    val groups: List<String>,
    val epgUrl: String = "",
)

/** Categoría VOD/series. */
data class VodCategory(val id: String, val name: String)

/** Película VOD. */
data class VodItem(
    val id: Int,
    val title: String,
    val icon: String = "",
    val rating: String = "",
    val catId: String = "",
    val ext: String = "mp4",
)

/** Serie. */
data class SeriesItem(
    val id: Int,
    val title: String,
    val cover: String = "",
    val plot: String = "",
    val rating: String = "",
)

/** Temporada + episodios. */
data class SeasonInfo(val number: Int, val name: String = "", val cover: String = "")
data class Episode(
    val id: Int,
    val season: Int,
    val epNum: Int,
    val title: String = "",
    val ext: String = "mp4",
)
data class SeriesDetail(val seasons: List<SeasonInfo>, val episodes: List<Episode>)
