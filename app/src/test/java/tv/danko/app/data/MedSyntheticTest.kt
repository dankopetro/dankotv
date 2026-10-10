package tv.danko.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MedSyntheticTest {
    @Test
    fun sintetico5000Da5000() {
        val sb = StringBuilder("#EXTM3U\n")
        repeat(5000) { i ->
            sb.append("#EXTINF:-1 group-title=\"G${i % 50}\",Canal $i\n")
            sb.append("http://127.0.0.1/$i.ts\n")
        }
        var n = 0
        M3uParser.parseStreaming(java.io.StringReader(sb.toString())) { n++ }
        assertEquals(5000, n)
    }
}
