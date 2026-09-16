package tv.danko.app.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.danko.app.data.ListsRepository
import tv.danko.app.data.db.ListEntity
import tv.danko.app.data.model.PlaylistSpec

data class ListsUiState(
    val lists: List<ListEntity> = emptyList(),
    val loading: Boolean = false,
    val loadingName: String = "",
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

    /** Descarga + guarda (equivale a load_and_store). onDone(true) = abrir. */
    fun addSpec(spec: PlaylistSpec, onDone: (Boolean) -> Unit) {
        _ui.value = _ui.value.copy(loading = true, loadingName = spec.name, error = null)
        viewModelScope.launch {
            val r = repo.loadSpec(spec)
            _ui.value = _ui.value.copy(
                loading = false,
                loadingName = "",
                lists = repo.lists(),
                error = r.exceptionOrNull()?.let { "No se pudo cargar la lista.\n${it.message}" },
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
