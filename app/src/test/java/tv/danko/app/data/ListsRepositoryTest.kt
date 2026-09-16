package tv.danko.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import tv.danko.app.data.db.ChannelEntity
import tv.danko.app.data.db.FavoriteEntity
import tv.danko.app.data.db.ListEntity
import tv.danko.app.data.db.PlaylistDao
import tv.danko.app.data.db.escapeLike
import tv.danko.app.data.model.PlaylistSpec
import java.io.File
import java.nio.file.Files

/** Fake en memoria: valida la orquestación del repositorio sin Room. */
class FakeDao : PlaylistDao {
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
class ListsRepositoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    private val folder: File = Files.createTempDirectory("ds").toFile()
    private val scope = TestScope(UnconfinedTestDispatcher() + Job())
    private val dao = FakeDao()
    private val settings = SettingsStore(
        PreferenceDataStoreFactory.create(scope = scope) {
            folder.resolve("s.preferences_pb")
        }
    )
    private val repo = ListsRepository(dao, settings)

    private fun m3u(): String =
        javaClass.getResource("/sample.m3u")!!.readText()

    @Test
    fun loadM3uGuardaListaYCanales() = runTest {
        server.enqueue(MockResponse(body = m3u()))
        val url = server.url("/lista.m3u").toString()

        val r = repo.loadSpec(PlaylistSpec.M3u("MiLista", url))
        assertTrue(r.isSuccess)
        assertEquals(1, dao.lists().size)
        assertEquals(9, dao.channels("MiLista").size)
        assertEquals("MiLista", settings.lastList.first())
        assertEquals(6, dao.groups("MiLista").size)
    }

    @Test
    fun loadM3uConErrorNoGuarda() = runTest {
        repeat(5) { server.enqueue(MockResponse(code = 500)) }
        val r = repo.loadSpec(PlaylistSpec.M3u("Mala", server.url("/x.m3u").toString()))
        assertTrue(r.isFailure)
        assertTrue(dao.lists().isEmpty())
    }

    @Test
    fun deleteLimpiaFavoritos() = runTest {
        server.enqueue(MockResponse(body = m3u()))
        repo.loadSpec(PlaylistSpec.M3u("L", server.url("/l.m3u").toString()))
        val url = dao.channels("L").first().url
        dao.addFavorite(FavoriteEntity("L", url))
        assertEquals(1, dao.favoriteUrls("L").size)
        repo.deleteList("L")
        assertTrue(dao.lists().isEmpty())
        assertTrue(dao.favoriteUrls("L").isEmpty())
    }

    @Test
    fun busquedaConPorcentajeLiteral() {
        assertEquals("100\\%", "100%".escapeLike())
        assertEquals("a\\_b", "a_b".escapeLike())
    }

    @Test
    fun settingsDefaultsYRoundtrip() = runTest {
        assertEquals("Naranja Oscuro", settings.skin.first())
        assertEquals("Todas", settings.lastGroup.first())
        assertEquals(45, settings.cacheSecs.first())
        settings.setSkin("Azul")
        settings.setLastGroup("Cine")
        settings.setCacheSecs(30)
        assertEquals("Azul", settings.skin.first())
        assertEquals("Cine", settings.lastGroup.first())
        assertEquals(30, settings.cacheSecs.first())
    }
}
