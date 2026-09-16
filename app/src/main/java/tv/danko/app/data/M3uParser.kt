package tv.danko.app.data

import tv.danko.app.data.model.Channel
import tv.danko.app.data.model.LoadResult

/**
 * Parser M3U. Port fiel de M3UParser.parse_m3u() en
 * usr/lib/dankoiptv/dankoiptv_lib/playlist_m3u.py + norm_m3u() en dankotv/engine.py.
 *
 * Kotlin puro (sin deps Android) para poder testearse en JVM.
 */
object M3uParser {

    private const val DEFAULT_GROUP = "General"

    private val attrRegexCache = mutableMapOf<String, Regex>()

    private fun attr(name: String, line: String, default: String = ""): String {
        val re = attrRegexCache.getOrPut(name) { Regex(Regex.escape(name) + "=\"(.*?)\"") }
        return re.find(line)?.groupValues?.getOrNull(1)?.trim() ?: default
    }

    private fun titleOf(extinf: String): String {
        // quita attrs (name="...") y parte por la primera coma, como get_title()
        val stripped = extinf.replace(Regex("\\=\"(.*?)\""), "")
        val parts = stripped.split(",", limit = 2)
        return if (parts.size < 2) "" else parts[1].trim()
    }

    /** Quita sufijos estilo Kodi "|User-Agent=...&Referer=..." de la URL. */
    private fun stripKodiArgs(url: String): String = url.split("|", limit = 2)[0]

    /**
     * Parse STREAMING línea por línea (memoria O(1) por líneas, no carga el
     * texto entero). Es la vía para playlists gigantes (344k canales / 88 MB):
     * cargar el String completo + lista resultante excede el heap Android
     * (verificado en device: OOM 175MB request con growth limit 256MB).
     * Los canales se van entregando por [onChannel] para insertar por lotes.
     * Devuelve el resultado agregado (grupos + epg) sin la lista de canales.
     */
    fun parseStreaming(reader: java.io.Reader, onChannel: (Channel) -> Unit): LoadResult {
        val groups = java.util.TreeSet<String>()
        val epgUrls = mutableListOf<String>()
        var headerEpg = ""
        var sawExtinf = false
        var sawExtm3u = false
        var buffer = mutableListOf<String>()

        reader.forEachLine { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTM3U") -> {
                    sawExtm3u = true
                    headerEpg = firstMatch(line, "x-tvg-url=\"(.*?)\"")
                        ?: firstMatch(line, "tvg-url=\"(.*?)\"")
                        ?: firstMatch(line, "url-tvg=\"(.*?)\"")
                        ?: ""
                }
                line.isEmpty() -> {}
                line.startsWith("#") -> buffer.add(line)
                else -> {
                    var extinf: String? = null
                    val overrides = mutableMapOf<String, String>()
                    for (b in buffer) {
                        when {
                            b.startsWith("#EXTINF:") -> extinf = b
                            b.startsWith("#EXTGRP:") -> b.removePrefix("#EXTGRP:").trim()
                                .takeIf { it.isNotEmpty() }?.let { overrides["group"] = it }
                            b.startsWith("#EXTLOGO:") -> b.removePrefix("#EXTLOGO:").trim()
                                .takeIf { it.isNotEmpty() }?.let { overrides["logo"] = it }
                        }
                    }
                    if (extinf != null) {
                        sawExtinf = true
                        val title = titleOf(extinf).ifEmpty { "Sin nombre" }
                        var group = attr("group-title", extinf)
                            .ifEmpty { attr("tvg-group", extinf) }
                            .ifEmpty { DEFAULT_GROUP }
                        var logo = attr("tvg-logo", extinf)
                        val epgId = attr("tvg-id", extinf)
                            .ifEmpty { attr("tvg-ID", extinf) }
                        overrides["group"]?.let { group = it }
                        overrides["logo"]?.let { logo = it }
                        val url = stripKodiArgs(line)
                        if (url.isNotEmpty()) {
                            val chEpg = attr("tvg-url", extinf)
                                .ifEmpty { attr("url-tvg", extinf) }
                            if (chEpg.isNotEmpty() && chEpg !in epgUrls) epgUrls.add(chEpg)
                            groups.add(group)
                            onChannel(Channel(title, group, url, logo, epgId))
                        }
                    }
                    buffer = mutableListOf()
                }
            }
        }
        if (!sawExtm3u && !sawExtinf) {
            throw IllegalArgumentException("Malformed M3U: no #EXTM3U and #EXTINF tags found")
        }
        if (groups.isEmpty()) throw IllegalArgumentException("No channels found")
        val epgFinal = headerEpg.ifEmpty { epgUrls.joinToString(",") }
        return LoadResult(emptyList(), groups.toList(), epgFinal)
    }

    fun parse(text: String): LoadResult {
        if (!("#EXTM3U" in text && "#EXTINF" in text)) {
            throw IllegalArgumentException("Malformed M3U: no #EXTM3U and #EXTINF tags found")
        }
        val channels = mutableListOf<Channel>()
        val titles = mutableSetOf<String>()
        val epgUrls = mutableListOf<String>()
        var headerEpg = ""
        var buffer = mutableListOf<String>()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTM3U")) {
                headerEpg = firstMatch(line, "x-tvg-url=\"(.*?)\"")
                    ?: firstMatch(line, "tvg-url=\"(.*?)\"")
                    ?: firstMatch(line, "url-tvg=\"(.*?)\"")
                    ?: ""
            } else if (line.isEmpty()) {
                continue
            } else if (line.startsWith("#")) {
                buffer.add(line)
            } else {
                // línea URL: procesa el buffer acumulado
                var extinf: String? = null
                val overrides = mutableMapOf<String, String>()
                for (b in buffer) {
                    when {
                        b.startsWith("#EXTINF:") -> extinf = b
                        b.startsWith("#EXTGRP:") -> b.removePrefix("#EXTGRP:").trim()
                            .takeIf { it.isNotEmpty() }?.let { overrides["group"] = it }
                        b.startsWith("#EXTLOGO:") -> b.removePrefix("#EXTLOGO:").trim()
                            .takeIf { it.isNotEmpty() }?.let { overrides["logo"] = it }
                    }
                }
                if (extinf != null) {
                    var title = titleOf(extinf).ifEmpty { "Sin nombre" }
                    var group = attr("group-title", extinf)
                        .ifEmpty { attr("tvg-group", extinf) }
                        .ifEmpty { DEFAULT_GROUP }
                    var logo = attr("tvg-logo", extinf)
                    val epgId = attr("tvg-id", extinf)
                        .ifEmpty { attr("tvg-ID", extinf) }
                    overrides["group"]?.let { group = it }
                    overrides["logo"]?.let { logo = it }
                    val url = stripKodiArgs(line)
                    if (url.isNotEmpty()) {
                        // títulos duplicados llevan sufijo " (k)", como el original
                        if (title in titles) {
                            var k = 0
                            while (true) {
                                k += 1
                                val cand = "$title ($k)"
                                if (cand !in titles) {
                                    title = cand
                                    break
                                }
                            }
                        }
                        titles.add(title)
                        val chEpg = attr("tvg-url", extinf)
                            .ifEmpty { attr("url-tvg", extinf) }
                        if (chEpg.isNotEmpty() && chEpg !in epgUrls) epgUrls.add(chEpg)
                        channels.add(Channel(title, group, url, logo, epgId))
                    }
                }
                buffer = mutableListOf()
            }
        }
        if (channels.isEmpty()) throw IllegalArgumentException("No channels found")
        val epgFinal = headerEpg.ifEmpty { epgUrls.joinToString(",") }
        return LoadResult(channels, channels.map { it.group }.toSortedSet().toList(), epgFinal)
    }

    private fun firstMatch(line: String, pattern: String): String? =
        Regex(pattern).find(line)?.groupValues?.getOrNull(1)
}
