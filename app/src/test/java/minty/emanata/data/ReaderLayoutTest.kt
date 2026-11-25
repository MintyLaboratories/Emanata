package minty.emanata.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Layout × direction: only top to bottom reads as one column. */
class ReaderLayoutTest {

    @Test
    fun leftAndRightAlwaysPage() {
        assertEquals("paged", ReaderLayout.shownMode("continuous", "ltr"))
        assertEquals("paged", ReaderLayout.shownMode("continuous", "rtl", sliced = true))
        assertEquals("continuous", ReaderLayout.shownMode("continuous", "ttb"))
        assertEquals("paged", ReaderLayout.shownMode("paged", "ttb"))
    }

    @Test
    fun detectedSlicesShowAsOneColumn() {
        assertEquals("continuous", ReaderLayout.shownMode("paged", "ttb", sliced = true))
    }

    @Test
    fun oddValuesFallBack() {
        assertEquals("paged", ReaderLayout.modeOf("longstrip"))
        assertEquals("paged", ReaderLayout.modeOf(null))
        assertEquals("ltr", ReaderLayout.dirOf("up"))
        assertEquals("ttb", ReaderLayout.dirOf("ttb"))
    }
}
