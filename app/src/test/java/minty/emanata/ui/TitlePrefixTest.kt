package minty.emanata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitlePrefixTest {

    @Test
    fun theSharedSeriesNameEndsAtASeparator() {
        assertEquals("Lumen - ", TitlePrefix.common(listOf("Lumen - 001.cbz", "Lumen - 002.cbz", "Lumen - 010.cbz")))
        assertEquals("Wanderer ", TitlePrefix.common(listOf("Wanderer v01 (2017).cbz", "Wanderer v02 (2018).cbz")))
        assertEquals("Orbit_", TitlePrefix.common(listOf("Orbit_01.cbr", "Orbit_02.cbr")))
    }

    @Test
    fun nothingSharedOrNoNameLeavesTitlesAlone() {
        assertNull(TitlePrefix.common(listOf("Lumen 01.cbz")))
        assertNull(TitlePrefix.common(listOf("Lumen 01.cbz", "Orbit 01.cbz")))
        // Shared digits are no series name.
        assertNull(TitlePrefix.common(listOf("001 Start.cbz", "002 End.cbz")))
        // Never cuts into a word.
        assertNull(TitlePrefix.common(listOf("Lumens.cbz", "Lumen.cbz")))
    }

    @Test
    fun neverCutsIntoANumber() {
        assertEquals("Lumen ", TitlePrefix.common(listOf("Lumen 2019-01.cbz", "Lumen 2019-02.cbz")))
        assertEquals("Lumen ", TitlePrefix.common(listOf("Lumen v1.1.cbz", "Lumen v1.2.cbz")))
        assertEquals("Lumen ", TitlePrefix.common(listOf("Lumen 2019 01.cbz", "Lumen 2019 02.cbz")))
        // A number followed by a lone separator ends the name.
        assertEquals("Lumen 2 - ", TitlePrefix.common(listOf("Lumen 2 - 001.cbz", "Lumen 2 - 002.cbz")))
        assertNull(TitlePrefix.common(listOf("2019-01.cbz", "2019-02.cbz")))
    }

    @Test
    fun noTitleIsLeftEmpty() {
        assertNull(TitlePrefix.common(listOf("Lumen - ", "Lumen - 2.cbz")))
    }
}
