package tv.danko.app.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.danko.app.data.ListsRepository
import tv.danko.app.data.SettingsStore
import tv.danko.app.data.db.ChannelEntity
import tv.danko.app.data.db.FavoriteEntity
import tv.danko.app.data.db.ListEntity
import tv.danko.app.data.db.PlaylistDao
import tv.danko.app.data.model.PlaylistSpec
import tv.danko.app.ui.channels.ChannelsViewModel
import tv.danko.app.ui.lists.ListsViewModel
import java.io.File
import java.nio.file.Files

/** Fake en memoria: valida la orquestación sin Room. */
class FakeDao2 : PlaylistDao {
    val lists = mutableMapOf<String, ListEntity>()
    val channels = mutableMapOf<String, MutableList<ChannelEntity>>()
    val favs = mutableSetOf<Pair<String, String>>()

    override suspend fun lists() = lists.values.sortedByDescending { it.updatedAt }
    override suspend fun upsertList(e: ListEntity) {
        lists[e.name] = e
    }
    override suspend fun deleteList(name: String) {
        lists.remove(name)
    }
    override suspend fun clearChannels(list: String) {
        channels[list] = mutableListOf()
    }
    override suspend fun insertChannels(rows: List<ChannelEntity>) {
        val l = rows.firstOrNull()?.listName ?: return
        channels.getOrPut(l) { mutableListOf() }.addAll(rows)
    }
    override suspend fun channels(list: String) =
        (channels[list] ?: emptyList()).sortedBy { it.title }
    override suspend fun groups(list: String) =
        (channels[list] ?: emptyList()).map { it.group }.toSortedSet().toList()
    override suspend fun search(list: String, q: String) =
        (channels[list] ?: emptyList())
            .filter { it.title.contains(q.replace("\\", ""), ignoreCase = true) }
            .sortedBy { it.title }.take(2000)
    override suspend fun favoriteUrls(list: String) =
        favs.filter { it.first == list }.map { it.second }
    override suspend fun addFavorite(f: FavoriteEntity) {
        favs.add(f.listName to f.url)
    }
    override suspend fun removeFavorite(list: String, url: String) {
        favs.remove(list to url)
    }
    override suspend fun clearFavorites(list: String) {
        favs.removeIf { it.first == list }
    }

    // --- páginas limitadas (espejo del SQL limitado del DAO real) ---
    private fun all(list: String) = channels[list] ?: mutableListOf()
    override suspend fun channelsLimited(list: String, limit: Int) =
        all(list).sortedBy { it.title }.take(limit)
    override suspend fun countChannels(list: String) = all(list).size
    override suspend fun channelsInGroup(list: String, group: String, limit: Int) =
        all(list).filter { it.group == group }.sortedBy { it.title }.take(limit)
    override suspend fun countInGroup(list: String, group: String) =
        all(list).count { it.group == group }
    override suspend fun searchInGroup(list: String, group: String, q: String, limit: Int) =
        all(list).filter { it.group == group && it.title.contains(q.replace("\\", ""), true) }
            .sortedBy { it.title }.take(limit)
    override suspend fun countSearchInGroup(list: String, group: String, q: String) =
        all(list).count { it.group == group && it.title.contains(q.replace("\\", ""), true) }
    override suspend fun countSearch(list: String, q: String) =
        all(list).count { it.title.contains(q.replace("\\", ""), true) }
    private fun favUrls(list: String) = favs.filter { it.first == list }.map { it.second }.toSet()
    override suspend fun favoriteChannels(list: String, limit: Int) =
        all(list).filter { it.url in favUrls(list) }.sortedBy { it.title }.take(limit)
    override suspend fun countFavorites(list: String) = favUrls(list).size
    override suspend fun searchFavorites(list: String, q: String, limit: Int) =
        all(list).filter { it.url in favUrls(list) && it.title.contains(q.replace("\\", ""), true) }
            .sortedBy { it.title }.take(limit)
    override suspend fun countSearchFavorites(list: String, q: String) =
        all(list).count { it.url in favUrls(list) && it.title.contains(q.replace("\\", ""), true) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelsTest {

    private lateinit var server: MockWebServer
    private val folder: File = Files.createTempDirectory("dsvm").toFile()
    private val scope = TestScope(UnconfinedTestDispatcher() + Job())
    private val dao = FakeDao2()
    private val settings = SettingsStore(
        PreferenceDataStoreFactory.create(scope = scope) {
            folder.resolve("v.preferences_pb")
        }
    )
    private val repo = ListsRepository(dao, settings)

    /** Espera real (los viewModelScope corren en hilos de verdad). */
    private suspend fun awaitTrue(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!cond()) delay(25)
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.close()
        Dispatchers.resetMain()
    }

    private fun m3u(): String =
        javaClass.getResource("/sample.m3u")!!.readText()

    @Test
    fun listsAddYDelete(): Unit = runBlocking {
        server.enqueue(MockResponse(body = m3u()))
        val vm = ListsViewModel(repo)
        awaitTrue { vm.ui.value.lists.isEmpty() } // init refresh (vacío)
        var opened: Boolean? = null
        vm.addSpec(PlaylistSpec.M3u("L", server.url("/l.m3u").toString())) { opened = it }
        awaitTrue { opened != null }
        assertEquals(true, opened)
        assertEquals("L", vm.ui.value.lists.single().name)
        vm.delete("L")
        awaitTrue { vm.ui.value.lists.isEmpty() }
    }

    @Test
    fun listsErrorMuestraMensaje(): Unit = runBlocking {
        repeat(5) { server.enqueue(MockResponse(code = 500)) }
        val vm = ListsViewModel(repo)
        var opened: Boolean? = null
        vm.addSpec(PlaylistSpec.M3u("M", server.url("/x.m3u").toString())) { opened = it }
        awaitTrue { opened != null }
        assertEquals(false, opened)
        assertTrue(vm.ui.value.error!!.contains("No se pudo cargar"))
    }

    @Test
    fun channelsFiltraYFavoritos(): Unit = runBlocking {
        server.enqueue(MockResponse(body = m3u()))
        val lv = ListsViewModel(repo)
        var done: Boolean? = null
        lv.addSpec(PlaylistSpec.M3u("L", server.url("/l.m3u").toString())) { done = it }
        awaitTrue { done == true }
        val vm = ChannelsViewModel(repo, settings, "L", debounceMs = 0)
        awaitTrue { vm.ui.value.total == 9 }
        // F3: el grupo ★ Favoritos siempre está primero tras "Todas".
        assertTrue(vm.ui.value.groups.take(2).contains("★ Favoritos"))
        vm.setQuery("hbo")
        awaitTrue { vm.ui.value.channels.size == 2 }
        vm.setQuery("")
        awaitTrue { vm.ui.value.channels.size == 9 }
        vm.setGroup("Cine")
        awaitTrue { vm.ui.value.channels.size == 2 }
        val url = vm.ui.value.channels.first().url
        vm.toggleFav(url)
        awaitTrue { url in vm.ui.value.favs }
        // F3: grupo ★ Favoritos filtra por favoritos.
        vm.setGroup("★ Favoritos")
        awaitTrue { vm.ui.value.channels.all { it.url == url } && vm.ui.value.channels.isNotEmpty() }
        vm.toggleFav(url)
        awaitTrue { url !in vm.ui.value.favs }
        // Al sacar el único favorito, el grupo queda vacío.
        awaitTrue { vm.ui.value.channels.isEmpty() }
        vm.setGroup("Todas")
        awaitTrue { vm.ui.value.channels.size == 9 }
        // F3: last_group se persiste al cambiar el chip.
        assertEquals("Todas", settings.lastGroup.first())
        vm.setGroup("Cine")
        awaitTrue { runBlocking { settings.lastGroup.first() == "Cine" } }
    }

    @Test
    fun reloadRecargaDelServidor(): Unit = runBlocking {
        // Primera carga: 9 canales. Segunda (reload): server sirve otro m3u.
        server.enqueue(MockResponse(body = m3u()))
        val lv = ListsViewModel(repo)
        var done: Boolean? = null
        lv.addSpec(PlaylistSpec.M3u("R", server.url("/r.m3u").toString())) { done = it }
        awaitTrue { done == true }
        val vm = ChannelsViewModel(repo, settings, "R", debounceMs = 0)
        awaitTrue { vm.ui.value.total == 9 }

        server.enqueue(MockResponse(body = m3u().replace("HBO", "HBO 2")))
        var reloaded: Boolean? = null
        vm.reload { reloaded = it }
        awaitTrue { reloaded == true }
        // El título cambió → demostró bajar de nuevo del servidor.
        awaitTrue { vm.ui.value.channels.any { it.title.contains("HBO 2") } }
        assertEquals(9, vm.ui.value.total)
    }

    @Test
    fun busquedaRapidaNoPisaElQuery(): Unit = runBlocking {
        // Regresión del bug "cursor que salta / letras en desorden": el
        // filtro viejo terminaba después y pisaba el query con texto viejo.
        server.enqueue(MockResponse(body = m3u()))
        val lv = ListsViewModel(repo)
        var done: Boolean? = null
        lv.addSpec(PlaylistSpec.M3u("Q", server.url("/q.m3u").toString())) { done = it }
        awaitTrue { done == true }
        val vm = ChannelsViewModel(repo, settings, "Q", debounceMs = 0)
        awaitTrue { vm.ui.value.total == 9 }
        // Tipeo rápido: 3 teclas sin esperar el filtro entre ellas.
        vm.setQuery("h")
        vm.setQuery("hb")
        vm.setQuery("hbo")
        // El query visible NUNCA retrocede y los resultados son del último.
        awaitTrue { vm.ui.value.query == "hbo" && vm.ui.value.channels.size == 2 }
        assertEquals("hbo", vm.ui.value.query)
        assertTrue(vm.ui.value.channels.all { it.title.contains("HBO", ignoreCase = true) })
        // Vaciar restaura la página completa sin pisar el query.
        vm.setQuery("")
        awaitTrue { vm.ui.value.query == "" && vm.ui.value.channels.size == 9 }
    }

    @Test
    fun testSpecCuentaSinGuardar(): Unit = runBlocking {
        server.enqueue(MockResponse(body = m3u()))
        val vm = ListsViewModel(repo)
        var msg: String? = null
        var ok: Boolean? = null
        vm.testSpec(PlaylistSpec.M3u("T", server.url("/t.m3u").toString())) { o, m ->
            ok = o
            msg = m
        }
        awaitTrue { msg != null }
        assertEquals(true, ok)
        assertTrue(msg!!.contains("9 canales"))
        assertTrue(vm.ui.value.lists.isEmpty()) // no guarda
    }
}
