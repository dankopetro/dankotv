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
