package tv.danko.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.danko.app.data.model.PlaylistSpec
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/** Regresión 10/10 (VisCard): una descarga truncada en silencio NO puede
 *  importarse como lista mocha; tiene que fallar y no guardar nada. */
@OptIn(ExperimentalCoroutinesApi::class)
class TruncatedDownloadTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    private val folder: File = Files.createTempDirectory("tr").toFile()
    private val scope = TestScope(UnconfinedTestDispatcher() + Job())
    private val dao = FakeDao()
    private val settings = SettingsStore(
        PreferenceDataStoreFactory.create(scope = scope) {
            folder.resolve("s.preferences_pb")
        },
    )
    private val repo = ListsRepository(dao, settings)

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    /** Declara 100000 bytes pero manda 100 y corta: tiene que FALLAR (no mocha). */
    @Test
    fun truncadaFallaYSinGuardar() = runTest {
        server.createContext("/t.m3u") { ex ->
            val body = "#EXTM3U\n#EXTINF:-1,Uno\nhttp://127.0.0.1/x.ts\n".toByteArray()
            ex.sendResponseHeaders(200, 100000)
            ex.responseBody.use { it.write(body) }
            ex.close()
        }
        val r = repo.loadSpec(PlaylistSpec.M3u("T", "http://127.0.0.1:$port/t.m3u"))
        assertTrue(r.isFailure)
        assertTrue(dao.lists().isEmpty())
        assertTrue(dao.channels("T").isEmpty())
    }

    /** Basura con 200: falla por cabecera, no guarda. */
    @Test
    fun basuraFallaYSinGuardar() = runTest {
        server.createContext("/b.m3u") { ex ->
            val body = "hola mundo, no soy m3u".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
            ex.close()
        }
        val r = repo.loadSpec(PlaylistSpec.M3u("B", "http://127.0.0.1:$port/b.m3u"))
        assertTrue(r.isFailure)
        assertTrue(dao.lists().isEmpty())
    }
}
