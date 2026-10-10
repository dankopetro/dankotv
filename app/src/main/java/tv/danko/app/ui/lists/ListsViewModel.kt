package tv.danko.app.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.danko.app.data.ListsRepository
import tv.danko.app.data.db.ListEntity
import tv.danko.app.data.loadErrorMessage
import tv.danko.app.data.model.PlaylistSpec

data class ListsUiState(
    val lists: List<ListEntity> = emptyList(),
    val loading: Boolean = false,
    val loadingName: String = "",
    /** True si el alta vino por intent adb (tarjeta grande, ver 9.21). */
    val loadingBig: Boolean = false,
    val error: String? = null,
    /** True tras el primer refresh real a DB (evita auto-diálogo prematuro). */
    val loaded: Boolean = false,
)

/** Espejo de HomeWindow (home.py): Mis listas + alta + borrado + test. */
class ListsViewModel(private val repo: ListsRepository) : ViewModel() {

    private val _ui = MutableStateFlow(ListsUiState())
    val ui: StateFlow<ListsUiState> = _ui

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(lists = repo.lists(), loaded = true)
        }
    }

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    /** Descarga + guarda (equivale a load_and_store). onDone(true) = abrir.
     *  Si falla, se avisa y se conserva lo guardado (regla 10/10).
     *  viaIntent=true: alta por intent adb → tarjeta de progreso grande. */
    fun addSpec(spec: PlaylistSpec, viaIntent: Boolean = false, onDone: (Boolean) -> Unit) {
        _ui.value = _ui.value.copy(loading = true, loadingName = spec.name, loadingBig = viaIntent, error = null)
        viewModelScope.launch {
            val r = repo.loadSpec(spec)
            val listsNow = repo.lists()
            val had = listsNow.any { it.name == spec.name }
            _ui.value = _ui.value.copy(
                loading = false,
                loadingName = "",
                loadingBig = false,
                lists = listsNow,
                error = r.exceptionOrNull()?.let { loadErrorMessage(it, had) },
            )
            onDone(r.isSuccess)
        }
    }

    fun delete(name: String) {
        viewModelScope.launch {
            repo.deleteList(name)
            _ui.value = _ui.value.copy(lists = repo.lists())
        }
    }

    /** Editor F7: renombra y/o recarga desde el nuevo origen.
     *  Si falla, se avisa y se conserva lo guardado (regla 10/10). */
    fun saveEdit(oldName: String, spec: PlaylistSpec, onDone: () -> Unit) {
        _ui.value = _ui.value.copy(loading = true, loadingName = spec.name, error = null)
        viewModelScope.launch {
            val r = repo.updateSpec(oldName, spec)
            val listsNow = repo.lists()
            val had = listsNow.any { it.name == spec.name } ||
                listsNow.any { it.name == oldName }
            _ui.value = _ui.value.copy(
                loading = false,
                loadingName = "",
                lists = listsNow,
                error = r.exceptionOrNull()?.let { loadErrorMessage(it, had) },
            )
            onDone()
        }
    }

    /** Prueba sin guardar (equivale a test_connection): devuelve "OK: N canales" o error. */
    fun testSpec(spec: PlaylistSpec, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val r = repo.testSpec(spec)
            r.fold(
                onSuccess = { n -> onResult(true, "OK: $n canales encontrados.") },
                onFailure = { e -> onResult(false, "Fallo: ${e.message}") },
            )
        }
    }

    suspend fun lastListName(): String = repo.lastListName()
}
