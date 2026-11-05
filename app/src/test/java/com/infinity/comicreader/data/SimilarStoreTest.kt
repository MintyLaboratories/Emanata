package com.infinity.comicreader.data

import com.infinity.comicreader.util.PageHash
import com.infinity.comicreader.util.SimilarBooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SimilarStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(pages: Int) = SimilarStore.Entry(
        SimilarBooks.Print(pages, listOf(listOf(PageHash(1, 2, false), null), listOf(PageHash(3, 4, true)), emptyList(), listOf(PageHash(-5, 6, false)))),
        1200, 1800, "cbz", "jxl"
    )

    private fun file() = File(tmp.root, "similar.txt")

    @Test
    fun keepsEntriesAcrossInstances() {
        SimilarStore(file()).put(BookId(7), entry(20))
        SimilarStore(file()).put(BookId(-8), entry(21))
        val again = SimilarStore(file())
        assertEquals(entry(20), again.get(BookId(7)))
        assertEquals(entry(21), again.get(BookId(-8)))
        assertNull(again.get(BookId(9)))
    }

    @Test
    fun aForgottenBookStaysForgotten() {
        val store = SimilarStore(file())
        store.put(BookId(7), entry(20))
        store.forget(BookId(7))
        assertNull(store.get(BookId(7)))
        assertNull(SimilarStore(file()).get(BookId(7)))
        store.put(BookId(7), entry(22))
        assertEquals(entry(22), SimilarStore(file()).get(BookId(7)))
    }

    @Test
    fun pastItsLimitKeepsTheLatestHalf() {
        val store = SimilarStore(file(), max = 4)
        for (i in 1..5) store.put(BookId(i.toLong()), entry(i))
        val again = SimilarStore(file(), max = 4)
        assertNull(again.get(BookId(3)))
        assertEquals(entry(4), again.get(BookId(4)))
        assertEquals(entry(5), again.get(BookId(5)))
    }

    @Test
    fun skipsLinesItCannotRead() {
        file().writeText("nothex\t1\n0000000000000007\tbroken\n0000000000000008\t" + SimilarStore.encode(entry(3)) + "\n")
        val store = SimilarStore(file())
        assertNull(store.get(BookId(7)))
        assertEquals(entry(3), store.get(BookId(8)))
    }

    @Test
    fun clearForgetsEverything() {
        val store = SimilarStore(file())
        store.put(BookId(7), entry(20))
        store.clear()
        assertNull(store.get(BookId(7)))
        assertNull(SimilarStore(file()).get(BookId(7)))
    }
}
