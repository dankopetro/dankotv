package tv.danko.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PtsReorderTest {

    @Test
    fun ordenaPorPtsConEmpateEstable() {
        val r = ShareUtils.PtsReorder(4)
        fun b(s: String) = s.toByteArray()
        // Orden decode con B-frames: I0 P3 B1 B2 (pts 0,3,1,2).
        assertNull(r.push(0, 0, b("I0"), 2, 1))
        assertNull(r.push(1, 3, b("P3"), 2, 0))
        assertNull(r.push(2, 1, b("B1"), 2, 0))
        assertNull(r.push(3, 2, b("B2"), 2, 0))
        // Al 5to empuja el menor (I0).
        val o1 = r.push(4, 4, b("P4"), 2, 0)!!
        assertEquals(0L, o1.pts)
        assertEquals("I0", String(o1.bytes))
        // Drenar el resto ordenado: B1 B2 P3 P4.
        val rest = r.drain().map { String(it.bytes) }
        assertEquals(listOf("B1", "B2", "P3", "P4"), rest)
    }

    @Test
    fun empateDesempataPorLlegada() {
        val r = ShareUtils.PtsReorder(2)
        fun b(s: String) = s.toByteArray()
        r.push(0, 5, b("a"), 1, 0)
        r.push(1, 5, b("b"), 1, 0)
        val o = r.push(2, 5, b("c"), 1, 0)!!
        assertEquals("a", String(o.bytes))
        assertEquals(listOf("b", "c"), r.drain().map { String(it.bytes) })
    }
}
