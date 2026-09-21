package tv.danko.app.data

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class XtreamClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.close()
    }

    private fun res(name: String): String =
        javaClass.getResource("/$name")!!.readText()

    private fun client() = XtreamClient()

    private fun base() = server.url("").toString().trimEnd('/')

    @Test
    fun cargaCanalesMapeaGruposYUrls() = runTest {
        server.enqueue(MockResponse(body = res("xtream_auth.json")))
        server.enqueue(MockResponse(body = res("xtream_categories.json")))
        server.enqueue(MockResponse(body = res("xtream_streams.json")))

        val out = client().loadLive(base(), "testuser", "testpass")

        // 7 streams - 1 sin nombre - 1 movie = 5
        assertEquals(5, out.channels.size)
        val espn = out.channels.first { it.title == "ESPN HD" }
        assertEquals("Deportes", espn.group)
        assertEquals("${base()}/live/testuser/testpass/201.ts", espn.url)
        assertEquals("http://logo.example.com/espn.png", espn.logo)
        // created_live se trata como live
        assertTrue(out.channels.any { it.title == "Fox Sports" })
        // sin grupo válido -> catch-all
        assertTrue(out.channels.any { it.group == XtreamClient.CATCH_ALL })
        assertEquals(
            listOf("Deportes", "Noticias", XtreamClient.CATCH_ALL),
            out.groups,
        )
    }

    @Test
    fun authFallidaLanza() = runTest {
        server.enqueue(MockResponse(body = """{"user_info": null}"""))
        try {
            client().loadLive(base(), "x", "y")
            fail("debió lanzar")
        } catch (e: XtreamAuthException) {
            assertTrue(e.message!!.contains("autenticación"))
        }
    }

    @Test
    fun httpErrorReintentaYLanza() = runTest {
        repeat(3) { server.enqueue(MockResponse(code = 500)) }
        try {
            client().loadLive(base(), "x", "y")
            fail("debió lanzar")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("HTTP 500"))
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun userAgentIgualQueLinux() = runTest {
        server.enqueue(MockResponse(body = res("xtream_auth.json")))
        server.enqueue(MockResponse(body = "[]"))
        server.enqueue(MockResponse(body = "[]"))
        try {
            client().loadLive(base(), "u", "p")
        } catch (_: Exception) {
        }
        val ua = server.takeRequest().headers["User-Agent"]
        assertEquals(XtreamClient.UA, ua)
    }

    @Test
    fun vodParseaToleraIdsString() = runTest {
        server.enqueue(
            MockResponse(
                body = """[
                    {"stream_id": 10, "name": "Peli A", "stream_icon": "http://x/a.png",
                     "rating": "7.3", "category_id": "5", "container_extension": "mkv"},
                    {"stream_id": "11", "name": "Peli B", "container_extension": ""},
                    {"stream_id": null, "name": "Sin id"},
                    {"stream_id": 12, "name": "  "}
                ]""",
            ),
        )
        val out = client().getVodStreams(base(), "u", "p")
        assertEquals(2, out.size)
        assertEquals(10, out[0].id)
        assertEquals("mkv", out[0].ext)
        assertEquals("7.3", out[0].rating)
        // ext vacía -> mp4 por defecto
        assertEquals("mp4", out[1].ext)
    }

    @Test
    fun seriesInfoAgrupaEpisodiosPorTemporada() = runTest {
        server.enqueue(
            MockResponse(
                body = """{"seasons": [{"season_number": 2, "name": "T2", "cover": "c2"},
                                       {"season_number": 1, "name": "T1", "cover": "c1"}],
                         "episodes": {"2": [{"id": "72", "episode_num": 1, "title": "E1", "container_extension": "mp4"}],
                                      "1": [{"id": 71, "episode_num": 2, "title": "E2", "container_extension": "mkv"},
                                            {"id": 70, "episode_num": 1, "title": "E1", "container_extension": "mp4"}],
                                      "X": [{"id": 1, "episode_num": 1, "title": "?", "container_extension": "mp4"}]}}""",
            ),
        )
        val d = client().getSeriesDetail(base(), "u", "p", 124)
        assertEquals(listOf(1, 2), d.seasons.map { it.number })
        // Ordenados por temporada y episodio, temporada inválida descartada.
        assertEquals(listOf(70, 71, 72), d.episodes.map { it.id })
        assertEquals("mkv", d.episodes[1].ext)
    }

    @Test
    fun urlsVodYEpisodios() {
        assertEquals(
            "http://h/movie/u/p/10.mkv",
            vodUrl("http://h/", "u", "p", 10, "mkv"),
        )
        assertEquals(
            "http://h/series/u/p/72.mp4",
            episodeUrl("http://h", "u", "p", 72, ""),
        )
    }

    @Test
    fun conexionesActivasYMaximo() = runTest {
        server.enqueue(
            MockResponse(
                body = """{"user_info": {"username": "u", "password": "p",
                    "active_cons": "3", "max_connections": 3}}""",
            ),
        )
        assertEquals(3 to 3, client().connectionUsage(base(), "u", "p"))
    }

    @Test
    fun conexionesSinDatosDaCero() = runTest {
        server.enqueue(MockResponse(body = """{"user_info": {"username": "u"}}"""))
        assertEquals(0 to 0, client().connectionUsage(base(), "u", "p"))
    }
}
