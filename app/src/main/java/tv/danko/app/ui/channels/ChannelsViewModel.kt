package tv.danko.app.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.danko.app.data.ListsRepository
import tv.danko.app.data.SettingsStore
import tv.danko.app.data.db.ChannelEntity

data class ChannelsUiState(
    val channels: List<ChannelEntity> = emptyList(),
    val groups: List<String> = emptyList(),
    val query: String = "",
    val group: String = "Todas",
    val favs: Set<String> = emptySet(),
    val total: Int = 0,
    val loading: Boolean = false,
)

/**
 * Espejo de MainWindow sin video (mainview.py): buscar, filtrar, favoritos.
 * Con 344k canales TODO es SQL limitado + COUNT (el camino anterior — cargar
 * la lista entera y filtrar en Kotlin — colgaba la pantalla "0 canales" y
 * ponía en riesgo el heap; verificado en device).
 */
class ChannelsViewModel(
    private val repo: ListsRepository,
    private val settings: SettingsStore,
    val listName: String,
    private val debounceMs: Long = 250, // 0 en tests (delay virtual no avanza fuera de runTest)
) : ViewModel() {

    private val _ui = MutableStateFlow(ChannelsUiState())
    val ui: StateFlow<ChannelsUiState> = _ui

    private var filterJob: Job? = null

    init {
        viewModelScope.launch {
            val groups = listOf("Todas", FAV_GROUP, HID_GROUP) + repo.groups(listName)
            val saved = settings.lastGroup.first()
            val group = if (saved in groups) saved else "Todas"
            _ui.value = ChannelsUiState(
                groups = groups,
                group = group,
                favs = repo.favoriteUrls(listName),
            )
            scheduleFilter(debounceMs = 0)
        }
    }

    fun setQuery(q: String) {
        // El texto se publica INMEDIATO (el campo nunca se traba) y el SQL
        // va con debounce + cancelación (cada tecla cancela la anterior).
        _ui.value = _ui.value.copy(query = q)
        scheduleFilter(debounceMs = debounceMs)
    }

    fun setGroup(g: String) {
        _ui.value = _ui.value.copy(group = g)
        viewModelScope.launch { settings.setLastGroup(g) } // como Linux (mainview.py:555)
        scheduleFilter(debounceMs = 0)
    }

    /**
     * Filtro con debounce + cancelación. Bug original (cursor que saltaba,
     * letras en desorden): applyFilter() capturaba el estado viejo y al
     * terminar lo escribía COMPLETO (s.copy), pisando el query con texto de
     * teclas anteriores. Ahora el query SOLO lo escriben setQuery/setGroup y
     * el filtro solo toca channels/total/favs sobre el estado FRESCO.
     */
    private fun scheduleFilter(debounceMs: Long) {
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val s = _ui.value
            val favs = repo.favoriteUrls(listName)
            val (channels, total) = when (s.group) {
                "Todas" -> if (s.query.isBlank()) {
                    repo.channelsPage(listName) to repo.countChannels(listName)
                } else {
                    repo.searchPage(listName, s.query)
                }
                FAV_GROUP -> if (s.query.isBlank()) {
                    repo.favoritesPage(listName) to favs.size
                } else {
                    repo.searchFavoritesPage(listName, s.query)
                }
                HID_GROUP -> {
                    val all = repo.hiddenPage(listName)
                    val rows = if (s.query.isBlank()) all
                    else all.filter { it.title.contains(s.query, ignoreCase = true) }
                    rows.take(2000) to rows.size
                }
                else -> if (s.query.isBlank()) {
                    repo.groupPage(listName, s.group) to repo.countInGroup(listName, s.group)
                } else {
                    repo.searchInGroupPage(listName, s.group, s.query)
                }
            }
            _ui.value = _ui.value.copy(channels = channels, total = total, favs = favs)
        }
    }

    fun toggleFav(url: String) {
        viewModelScope.launch {
            val cur = repo.favoriteUrls(listName)
            if (url in cur) repo.removeFavorite(listName, url)
            else repo.addFavorite(listName, url)
            _ui.value = _ui.value.copy(favs = repo.favoriteUrls(listName))
            if (_ui.value.group == FAV_GROUP) scheduleFilter(debounceMs = 0)
        }
    }

    /** Oculta/muestra un canal (editor F7). Refresca si se mira Ocultos. */
    fun toggleHide(url: String, hidden: Boolean) {
        viewModelScope.launch {
            repo.setHidden(listName, url, hidden)
            scheduleFilter(debounceMs = 0)
        }
    }

    /** Recarga la lista del servidor (reload_list en Linux). */
    fun reload(onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true)
            val spec = repo.specOf(listName)
            val res = if (spec == null) null else repo.loadSpec(spec)
            val ok = res?.isSuccess == true
            if (!ok) {
                android.util.Log.w(
                    "DankoTV",
                    "reload $listName falló: ${res?.exceptionOrNull()}",
                )
            }
            val groups = listOf("Todas", FAV_GROUP, HID_GROUP) + repo.groups(listName)
            val group = if (_ui.value.group in groups) _ui.value.group else "Todas"
            _ui.value = _ui.value.copy(loading = false, groups = groups, group = group)
            scheduleFilter(debounceMs = 0)
            onDone(ok)
        }
    }

    companion object {
        const val DISPLAY_LIMIT = 2000 // como DISPLAY_LIMIT en mainview.py
        const val FAV_GROUP = "★ Favoritos" // igual que mainview.py:417
        const val HID_GROUP = "Ocultos" // pseudo-grupo del editor (texto: sin glifos)
    }
}

/**
 * Filtro de grupos/categorías en memoria (F7): son cientos como máximo,
 * sin SQL. Insensible a mayúsculas; query vacío = todo.
 */
fun filterGroups(groups: List<String>, q: String): List<String> {
    val needle = q.trim()
    if (needle.isEmpty()) return groups
    return groups.filter { it.contains(needle, ignoreCase = true) }
}
