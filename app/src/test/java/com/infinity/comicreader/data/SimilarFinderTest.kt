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
import java.util.concurrent.atomic.AtomicInteger

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

    private fun read(f: File) = books[f.name]?.let { SimilarFinder.Read(it) }

    @Test
    fun groupsContentsAlikeOnceEachAndKeepsWhatItRead() {
        val reads = Collections.synchronizedList(ArrayList<String>())
        val store = SimilarStore(File(tmp.root, "store.txt"))
        val finder = SimilarFinder(store, ::idOf, { f, _ -> reads += f.name; read(f) })
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
        val finder = SimilarFinder(store, ::idOf, { f, _ -> alive = false; read(f) }, workers = 1)
        val found = finder.find(files("a.cbz", "b.cbr", "c.cbz"), { alive })
        // The read under way when the search stopped is kept and compared; none starts after it.
        assertEquals(listOf(listOf(BookId(1), BookId(2))), found.groups.map { g -> g.map { it.id } })
        assertEquals(2, found.compared)
        assertEquals(3, found.total)
        assertEquals(entry(31), store.get(BookId(2)))
        assertNull(store.get(BookId(3)))
    }

    @Test
    fun readThatMayGoBetterIsComparedButNotKept() {
        val store = SimilarStore(File(tmp.root, "store.txt"))
        val reads = Collections.synchronizedList(ArrayList<String>())
        val finder = SimilarFinder(store, ::idOf, { f, _ -> reads += f.name; books[f.name]?.let { SimilarFinder.Read(it, keep = f.name != "b.cbr") } })
        assertEquals(1, finder.find(files("a.cbz", "b.cbr")).groups.size)
        assertNull(store.get(BookId(2)))
        reads.clear()
        finder.find(files("a.cbz", "b.cbr"))
        assertEquals(listOf("b.cbr"), reads.toList())
    }

    @Test
    fun readsNoMoreAtOnceThanTheLimitAllows() {
        val store = SimilarStore(File(tmp.root, "store.txt"))
        val inside = AtomicInteger()
        val most = AtomicInteger()
        val finder = SimilarFinder(store, { f -> BookId(f.name.hashCode().toLong()) }, { f, _ ->
            most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
            Thread.sleep(20)
            inside.decrementAndGet()
            SimilarFinder.Read(entry(30, shift = f.name.length * 1000))
        }, workers = 4, limit = { 2 })
        finder.find((1..12).map { File(tmp.root, "b" + "x".repeat(it) + ".cbz") })
        assertEquals(2, most.get())
    }

    @Test
    fun withoutReadingNewComparesOnlyWhatIsKept() {
        val store = SimilarStore(File(tmp.root, "store.txt"))
        store.put(BookId(1), entry(30))
        store.put(BookId(2), entry(31))
        val reads = Collections.synchronizedList(ArrayList<String>())
        val finder = SimilarFinder(store, ::idOf, { f, _ -> reads += f.name; read(f) })
        val contents = finder.contents(files("a.cbz", "b.cbr", "c.cbz"))
        assertEquals(listOf(BookId(3)), contents.unread)
        val found = finder.find(contents, readNew = false)
        assertEquals(listOf(listOf(BookId(1), BookId(2))), found.groups.map { g -> g.map { it.id } })
        assertEquals(2, found.compared)
        assertEquals(3, found.total)
        assertEquals(emptyList<String>(), reads.toList())
    }

    @Test
    fun contentsWithoutDeletedFilesDropEmptiedOnes() {
        val store = SimilarStore(File(tmp.root, "store.txt"))
        val finder = SimilarFinder(store, ::idOf, { f, _ -> read(f) })
        val contents = finder.contents(files("a.cbz", "a copy.cbz", "b.cbr", "c.cbz"))
        val left = contents.without(setOf(File(tmp.root, "a.cbz").absolutePath, File(tmp.root, "b.cbr").absolutePath))
        assertEquals(listOf(BookId(1), BookId(3)), left.byId.keys.toList())
        assertEquals(listOf("a copy.cbz"), left.byId.getValue(BookId(1)).map { it.name })
        assertEquals(listOf(BookId(1), BookId(3)), left.unread)
    }

    @Test
    fun clearingTheStoreMidwayStillComparesWhatWasRead() {
        val store = SimilarStore(File(tmp.root, "store.txt"))
        val finder = SimilarFinder(store, ::idOf, { f, _ -> if (f.name == "a.cbz") store.clear(); read(f) }, workers = 1)
        val found = finder.find(files("a.cbz", "b.cbr", "c.cbz"))
        assertEquals(listOf(listOf(BookId(1), BookId(2))), found.groups.map { g -> g.map { it.id } })
        // Read after the clear, so kept anew.
        assertEquals(entry(31), SimilarStore(File(tmp.root, "store.txt")).get(BookId(2)))
    }
}
