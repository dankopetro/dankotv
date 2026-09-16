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
}
