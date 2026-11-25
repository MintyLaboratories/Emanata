package minty.emanata.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BadgeFitTest {

    /** 5 px per character plus 12 px of padding. */
    private val measure = { s: String -> s.length * 5f + 12f }

    @Test
    fun theLongestFormThatFitsWins() {
        val forms = listOf("123 / 456", "26%")
        assertEquals("123 / 456", BadgeFit.choose(forms, 60f, measure))
        assertEquals("26%", BadgeFit.choose(forms, 42f, measure))
    }

    @Test
    fun nothingFittingLeavesADot() {
        assertEquals("", BadgeFit.choose(listOf("Read"), 26f, measure))
    }

    @Test
    fun percentRoundsDownAndStaysInRange() {
        assertEquals(20, BadgeFit.percent(39, 192))
        assertEquals(100, BadgeFit.percent(191, 192))
        // A book begun never shows 0%.
        assertEquals(1, BadgeFit.percent(1, 222))
        assertEquals(0, BadgeFit.percent(5, 0))
    }
}
