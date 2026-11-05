package com.infinity.comicreader.data

import com.infinity.comicreader.util.PageHash
import com.infinity.comicreader.util.SimilarBooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

class SimilarFinderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun hash(n: Int) = PageHash(n * -0x61c8864680b583ebL, n * 0x7F4A7C159E3779B9L, flat = false)

    /** A book of [pages] pages whose page i hashes as page i + [shift] of a shared run. */
    private fun entry(pages: Int, shift: Int = 0) = SimilarStore.Entry(
        SimilarBooks.Print(pages, SimilarBooks.slotPages(pages).map { slot -> slot.map { hash(it + shift) } }), 100, 150, "cbz", "jpg"
    )

    private val ids = mapOf("a.cbz" to 1L, "a copy.cbz" to 1L, "b.cbr" to 2L, "c.cbz" to 3L, "broken.cbz" to 4L)
    private val books = mapOf("a.cbz" to entry(30), "b.cbr" to entry(31), "c.cbz" to entry(30, shift = 100))

    private fun files(vararg names: String) = names.map { File(tmp.root, it) }

    private fun idOf(f: File) = ids[f.name]?.let(::BookId)

    @Test
    fun groupsContentsAlikeOnceEachAndKeepsWhatItRead() {
        val reads = Collections.synchronizedList(ArrayList<String>())
        val store = SimilarStore(File(tmp.root, "store.txt"))
        val finder = SimilarFinder(store, ::idOf, { f, _ -> reads += f.name; books[f.name] })
        val progress = Collections.synchronizedList(ArrayList<Pair<Int, Int>>())
        val found = finder.find(files("a.cbz", "a copy.cbz", "b.cbr", "c.cbz", "broken.cbz", "unknown.cbz")) { d, t -> progress += d to t }
        val groups = found.groups
        assertEquals(3, found.compared)
        assertEquals(4, found.total)
        assertEquals(1, groups.size)
        assertEquals(listOf(BookId(1), BookId(2)), groups[0].map { it.id })
        assertEquals(listOf("a.cbz", "a copy.cbz"), groups[0][0].files.map { it.name })
        // Bit-identical copies are read once; a book that cannot be read is skipped.
        assertEquals(listOf("a.cbz", "b.cbr", "broken.cbz", "c.cbz"), reads.sorted())
        assertEquals(0 to 4, progress.first())
        assertEquals(4, progress.maxOf { it.first })
        // A second search reads only what it could not keep.
        reads.clear()
        assertEquals(1, finder.find(files("a.cbz", "b.cbr", "c.cbz", "broken.cbz")).groups.size)
        assertEquals(listOf("broken.cbz"), reads.toList())
    }

    @Test
    fun stoppedEarlyComparesWhatWasRead() {
        val store = SimilarStore(File(tmp.root, "store.txt"))
        store.put(BookId(1), entry(30))
        var alive = true
        val finder = SimilarFinder(store, ::idOf, { f, _ -> alive = false; books[f.name] }, workers = 1)
        val found = finder.find(files("a.cbz", "b.cbr", "c.cbz"), { alive })
        // The read under way when the search stopped is kept and compared; none starts after it.
        assertEquals(listOf(listOf(BookId(1), BookId(2))), found.groups.map { g -> g.map { it.id } })
        assertEquals(2, found.compared)
        assertEquals(3, found.total)
        assertEquals(entry(31), store.get(BookId(2)))
        assertNull(store.get(BookId(3)))
    }
}
