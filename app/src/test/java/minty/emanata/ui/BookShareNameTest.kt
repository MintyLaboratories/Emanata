package minty.emanata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookShareNameTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun aShortTitleIsTheName() {
        assertEquals("Vol 1 (draft)", BookShare.coverName("Vol 1 (draft)"))
    }

    @Test
    fun longTitlesAreCutToTheBytesAFileNameTakes() {
        assertEquals("a".repeat(BookShare.MAX_NAME_BYTES), BookShare.coverName("a".repeat(255)))
        // Three bytes a character: 82 fit in 246.
        val wide = BookShare.coverName("\u6f2b".repeat(255))
        assertEquals(82, wide.length)
        assertTrue(bytes("$wide.png") <= 255)
    }

    @Test
    fun charactersOutsideTheBasicPlaneAreNeverSplit() {
        // Four bytes and two chars each: 61 fit in 246.
        val name = BookShare.coverName("\uD83D\uDCD6".repeat(100))
        assertEquals(122, name.length)
        assertTrue(Character.isLowSurrogate(name.last()))
        assertEquals(244, bytes(name))
    }

    @Test
    fun aCutNeverEndsInSpaces() {
        assertEquals("a".repeat(240), BookShare.coverName("a".repeat(240) + " ".repeat(20) + "b"))
    }

    @Test
    fun titlesThatCannotNameAFileAreCover() {
        for (title in listOf("", " ", ".", "..", "a/b", "a\\b")) assertEquals(title, "cover", BookShare.coverName(title))
    }
}
