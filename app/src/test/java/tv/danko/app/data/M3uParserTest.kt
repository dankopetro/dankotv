package tv.danko.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class M3uParserTest {

    private fun sample(): String =
        javaClass.getResource("/sample.m3u")!!.readText()

    @Test
    fun parseaCanalesGruposYLogos() {
        val res = M3uParser.parse(sample())
        // 9 URLs válidas ("Sin URL se salta" no tiene línea URL propia)
        assertEquals(9, res.channels.size)
        assertEquals(
            listOf("Cine", "Deportes", "General", "Infantiles", "Música", "Noticias"),
            res.groups,
        )
        val bbc = res.channels.first { it.title == "BBC News" }
        assertEquals("Noticias", bbc.group)
        assertEquals("http://logo.example.com/bbc.png", bbc.logo)
        assertEquals("BBCNews.uk", bbc.epgId)
        assertEquals("http://epg.example.com/xmltv.gz", res.epgUrl)
    }

    @Test
    fun tituloConComaYDuplicados() {
        val res = M3uParser.parse(sample())
        assertTrue(res.channels.any { it.title == "CNN en Español, con coma" })
        assertTrue(res.channels.any { it.title == "HBO" })
        assertTrue(res.channels.any { it.title == "HBO (1)" })
    }

    @Test
    fun quitaSufijoKodiYDefaults() {
        val res = M3uParser.parse(sample())
        val espn = res.channels.first { it.title == "ESPN" }
        assertEquals("http://srv.example.com:8080/live/u1/p1/201.ts", espn.url)
        val solo = res.channels.first { it.title == "Solo Titulo Sin Grupo" }
        assertEquals("General", solo.group)
    }

    @Test
    fun extgrpOverrideYGrupoInexistente() {
        val res = M3uParser.parse(sample())
        val toon = res.channels.first { it.title.startsWith("Cartoon") }
        assertEquals("Infantiles", toon.group) // EXTGRP gana a group-title="Otro"
    }

    @Test
    fun m3uMalformadoLanza() {
        try {
            M3uParser.parse("basura sin tags")
            fail("debió lanzar")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Malformed"))
        }
        try {
            M3uParser.parse("#EXTM3U\n#EXTINF:-1,Solo\n")
            fail("debió lanzar por sin canales")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("No channels"))
        }
    }

    @Test
    fun rendimiento20kCanales() {
        val sb = StringBuilder("#EXTM3U\n")
        repeat(20_000) { i ->
            sb.append("#EXTINF:-1 tvg-logo=\"http://l/x.png\" group-title=\"G${i % 50}\",Canal $i\n")
            sb.append("http://srv/$i.ts\n")
        }
        val t0 = System.currentTimeMillis()
        val res = M3uParser.parse(sb.toString())
        val ms = System.currentTimeMillis() - t0
        assertEquals(20_000, res.channels.size)
        assertEquals(50, res.groups.size)
        println("parse 20k canales: ${ms}ms")
        assertTrue("demasiado lento: ${ms}ms", ms < 10_000)
    }

    @Test
    fun streamMatchesParse() {
        // El camino streaming (playlists gigantes) debe dar los mismos canales
        // que el clásico. DIFERENCIA deliberada: el dedup de títulos " (k)"
        // (cosmético, Set gigante en RAM) NO corre en streaming.
        val classic = M3uParser.parse(sample())
        val streamed = mutableListOf<tv.danko.app.data.model.Channel>()
        val res = M3uParser.parseStreaming(sample().reader(), streamed::add)
        assertEquals(classic.channels.size, streamed.size)
        assertEquals(classic.groups, res.groups)
        assertEquals(classic.epgUrl, res.epgUrl)
        fun String.stripDedup() = replace(Regex(""" \(\d+\)$"""), "")
        classic.channels.sortedBy { it.url }.zip(streamed.sortedBy { it.url })
            .forEach { (a, b) ->
                assertEquals(a.title.stripDedup(), b.title)
                assertEquals(a.group, b.group)
                assertEquals(a.url, b.url)
                assertEquals(a.logo, b.logo)
                assertEquals(a.epgId, b.epgId)
            }
    }

    @Test
    fun streamRechazaBasura() {
        try {
            M3uParser.parseStreaming("garbage".reader()) {}
            fail("debía fallar")
        } catch (_: IllegalArgumentException) {
        }
    }
}
