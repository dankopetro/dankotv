package tv.danko.app.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tv.danko.app.data.ListsRepository
import tv.danko.app.data.db.ChannelEntity

/**
 * Cola de reproducción de la lista filtrada actual. Espejo de self.filtered +
 * _playing_idx + _step() en mainview.py: prev/next recorren la lista SIN
 * recrear el player (misma instancia mpv, loadfile replace).
 */
class PlayQueue(private val repo: ListsRepository) {
    var listName by mutableStateOf("")
    var channels by mutableStateOf<List<ChannelEntity>>(emptyList())
    var index by mutableIntStateOf(-1)

    val current: ChannelEntity? get() = channels.getOrNull(index)
    val isEmpty: Boolean get() = channels.isEmpty()

    fun set(listName: String, channels: List<ChannelEntity>, startIndex: Int) {
        this.listName = listName
        this.channels = channels
        this.index = startIndex
    }

    fun clear() {
        listName = ""
        channels = emptyList()
        index = -1
    }

    /** Paso con wrap-around (idéntico a _step en Linux). Devuelve el nuevo canal. */
    fun step(delta: Int): ChannelEntity? {
        if (channels.isEmpty()) return null
        index = ((index + delta) % channels.size + channels.size) % channels.size
        return current
    }

    suspend fun isFavorite(url: String): Boolean = url in repo.favoriteUrls(listName)

    /** Alterna favorito y devuelve el nuevo estado (cfg.toggle_favorite). */
    suspend fun toggleFavorite(url: String): Boolean {
        val cur = repo.favoriteUrls(listName)
        return if (url in cur) {
            repo.removeFavorite(listName, url)
            false
        } else {
            repo.addFavorite(listName, url)
            true
        }
    }
}
