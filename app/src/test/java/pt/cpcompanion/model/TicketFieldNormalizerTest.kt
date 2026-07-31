package pt.cpcompanion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TicketFieldNormalizerTest {
    @Test
    fun stripsOnlyTheMatchingLeadingLabel() {
        assertEquals("24", normalizeCarriage("C24"))
        assertEquals("24", normalizeCarriage("c24"))
        assertEquals("107", normalizeSeat("L107"))
        assertEquals("107", normalizeSeat("l107"))
    }

    @Test
    fun preservesUnlabelledAndSuffixValues() {
        assertEquals("24", normalizeCarriage("24"))
        assertEquals("32A", normalizeSeat("32A"))
        assertEquals("32A", normalizeSeat("L32A"))
        assertNull(normalizeCarriage(""))
        assertNull(normalizeSeat("  "))
    }
}
