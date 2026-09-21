package tv.danko.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.danko.app.data.model.Episode
import tv.danko.app.data.model.SeasonInfo
import tv.danko.app.data.model.SeriesDetail
import tv.danko.app.data.model.SeriesItem
import tv.danko.app.data.model.VodCategory
import tv.danko.app.data.model.VodItem
import tv.danko.app.data.model.PlaylistSpec

/**
 * F6 VOD + series: resuelve credenciales Xtream de la lista (directa o
 * parseando la URL M3U get.php, igual que EPG) y expone catálogos.
 * Sin caché Room en v1 (se trae por apertura; los catálogos cambian poco).
 */
class VodRepository(
    private val lists: ListsRepository,
    private val xtream: XtreamClient = XtreamClient(),
) {
    data class Creds(val host: String, val user: String, val pass: String)

    suspend fun creds(listName: String): Creds? = withContext(Dispatchers.IO) {
        try {
            when (val spec = lists.specOf(listName)) {
                is PlaylistSpec.Xtream -> Creds(spec.host, spec.user, spec.pass)
                is PlaylistSpec.M3u -> {
                    val (h, u, p) = xtreamCredsFromM3uUrl(spec.url) ?: return@withContext null
                    Creds(h, u, p)
                }
                null -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun vodCategories(listName: String): List<VodCategory> {
        val c = creds(listName) ?: return emptyList()
        return try {
            xtream.getVodCategories(c.host, c.user, c.pass)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun vodStreams(listName: String, categoryId: String? = null): List<VodItem> {
        val c = creds(listName) ?: return emptyList()
        return try {
            xtream.getVodStreams(c.host, c.user, c.pass, categoryId)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun seriesCategories(listName: String): List<VodCategory> {
        val c = creds(listName) ?: return emptyList()
        return try {
            xtream.getSeriesCategories(c.host, c.user, c.pass)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun series(listName: String, categoryId: String? = null): List<SeriesItem> {
        val c = creds(listName) ?: return emptyList()
        return try {
            xtream.getSeries(c.host, c.user, c.pass, categoryId)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun seriesDetail(listName: String, seriesId: Int): SeriesDetail? {
        val c = creds(listName) ?: return null
        return try {
            xtream.getSeriesDetail(c.host, c.user, c.pass, seriesId)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun movieUrl(listName: String, item: VodItem): String? {
        val c = creds(listName) ?: return null
        return vodUrl(c.host, c.user, c.pass, item.id, item.ext)
    }

    suspend fun episodeUrl(listName: String, ep: Episode): String? {
        val c = creds(listName) ?: return null
        return episodeUrl(c.host, c.user, c.pass, ep.id, ep.ext)
    }
}
