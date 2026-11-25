package minty.emanata.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarBooksTest {

    /** A hash told apart from others by [n]: its own bits, never flat. */
    private fun hash(n: Int) = PageHash(n * -0x61c8864680b583ebL, n * 0x7F4A7C159E3779B9L, flat = false)

    /** [h] with its lowest [bits] bits flipped. */
    private fun off(h: PageHash, bits: Int) = h.copy(across = h.across xor ((1L shl bits) - 1))

    /** The print of a book of [pageCount] pages whose page i hashes as [page] says. */
    private fun print(pageCount: Int, vector: Boolean = false, page: (Int) -> PageHash?) =
        SimilarBooks.Print(pageCount, SimilarBooks.slotPages(pageCount).map { slot -> slot.map(page) }, vector)

    @Test
    fun samplesTheCoverAndPagesAQuarterHalfAndThreeQuartersIn() {
        assertEquals(listOf(listOf(0, 1), listOf(25, 26), listOf(50, 51), listOf(74, 75)), SimilarBooks.slotPages(100))
        assertEquals(listOf(listOf(0), listOf(0), listOf(0), listOf(0)), SimilarBooks.slotPages(1))
        assertEquals(listOf(0, 1, 2), SimilarBooks.pagesToRead(3))
        assertEquals(List(4) { emptyList<Int>() }, SimilarBooks.slotPages(0))
    }

    @Test
    fun aReEncodedCopyIsAlike() {
        val a = print(40) { hash(it) }
        val b = print(40) { off(hash(it), 5) }
        assertTrue(SimilarBooks.alike(a, b))
    }

    @Test
    fun aPageAddedAtTheStartOrEndStillLinesUp() {
        val book = print(60) { hash(it) }
        val tagPageAtEnd = print(61) { if (it == 60) hash(999) else hash(it) }
        val extraCoverAtStart = print(61) { if (it == 0) hash(998) else hash(it - 1) }
        assertTrue(SimilarBooks.alike(book, tagPageAtEnd))
        assertTrue(SimilarBooks.alike(book, extraCoverAtStart))
        assertFalse(SimilarBooks.alike(book, print(62) { hash(it) }))
    }

    @Test
    fun anotherBookOfAsManyPagesIsNot() {
        assertFalse(SimilarBooks.alike(print(40) { hash(it) }, print(40) { hash(it + 1000) }))
    }

    @Test
    fun oneOtherPlaceOfFourIsForgivenButNotOfThree() {
        val book = print(40) { hash(it) }
        val otherCover = print(40) { if (it <= 1) hash(it + 500) else hash(it) }
        assertTrue(SimilarBooks.alike(book, otherCover))
        // A flat place leaves three to compare: all must match.
        val blankMiddle = print(40) { if (it == 20 || it == 21) PageHash(0, 0, flat = true) else hash(it) }
        assertTrue(SimilarBooks.alike(book, blankMiddle))
        assertFalse(SimilarBooks.alike(blankMiddle, otherCover))
    }

    @Test
    fun flatOrUnreadablePagesProveNothing() {
        val blank = print(40) { PageHash(0, 0, flat = true) }
        assertFalse(SimilarBooks.alike(blank, blank))
        val unread = print(40) { null }
        assertFalse(SimilarBooks.alike(unread, unread))
        assertNull(SimilarBooks.distance(listOf(null), listOf(hash(1))))
    }

    @Test
    fun groupsFollowLikenessThroughAndKeepTheGivenOrder() {
        val a = print(30) { hash(it) }
        val b = print(31) { off(hash(it), 4) }
        val c = print(32) { off(hash(it), 8) }
        val other = print(30) { hash(it + 700) }
        val prints = mapOf("c" to c, "x" to other, "a" to a, "none" to null, "b" to b)
        val groups = SimilarBooks.groups(listOf("x", "c", "none", "a", "b")) { prints[it] }
        // a-b and b-c are a page apart; a and c, two, join through b.
        assertEquals(listOf(listOf("c", "a", "b")), groups)
    }

    @Test
    fun shortBooksAreComparedStrictly() {
        assertTrue(SimilarBooks.alike(print(40) { hash(it) }, print(40) { off(hash(it), 9) }))
        assertFalse(SimilarBooks.alike(print(6) { hash(it) }, print(6) { off(hash(it), 9) }))
        assertTrue(SimilarBooks.alike(print(6) { hash(it) }, print(6) { off(hash(it), 4) }))
        // No other place is forgiven.
        assertFalse(SimilarBooks.alike(print(6) { hash(it) }, print(6) { if (it <= 1) hash(it + 500) else hash(it) }))
    }

    @Test
    fun vectorBooksAreComparedStrictly() {
        val pdf = print(40, vector = true) { hash(it) }
        assertFalse(SimilarBooks.alike(pdf, print(40) { off(hash(it), 9) }))
        assertTrue(SimilarBooks.alike(pdf, print(40) { off(hash(it), 4) }))
        assertFalse(SimilarBooks.alike(pdf, print(40) { if (it <= 1) hash(it + 500) else hash(it) }))
    }
}
