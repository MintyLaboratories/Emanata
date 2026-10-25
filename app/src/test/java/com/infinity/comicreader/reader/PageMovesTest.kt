package com.infinity.comicreader.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where pages went when their order changed, by their keys. */
class PageMovesTest {

    @Test
    fun pagesGoWhereTheirKeysWent() {
        val moves = PageMoves.of(listOf("0", "1", "2/0", "2/1"), listOf("2/1", "0", "2/0", "1"))
        assertArrayEquals(intArrayOf(1, 3, 2, 0), moves)
        assertFalse(PageMoves.none(moves, 4))
    }

    @Test
    fun aPageGoneHasNoPlace() {
        assertArrayEquals(intArrayOf(0, -1), PageMoves.of(listOf("0", "1"), listOf("0")))
    }

    @Test
    fun theSameOrderMovesNothing() {
        assertTrue(PageMoves.none(PageMoves.of(listOf("0", "1"), listOf("0", "1")), 2))
        // A page more is a move.
        assertFalse(PageMoves.none(PageMoves.of(listOf("0", "1"), listOf("0", "1", "2")), 3))
    }
}
