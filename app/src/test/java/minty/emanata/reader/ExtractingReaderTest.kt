package minty.emanata.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream

/** A solid archive read once, front to back, its entries kept as files. */
class ExtractingReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Entries named by index, each holding its name; [reads] logs what is unpacked, in order. */
    private class Solid(count: Int) : ArchiveReader {
        val reads = ArrayList<Int>()
        var closed = 0
        override val entries = List(count) { ArchiveEntry("p$it.jpg", 4, it) }
        override val randomAccess = false

        override fun <T> read(entry: ArchiveEntry, block: (InputStream) -> T): T {
            reads += entry.index
            return block(ByteArrayInputStream(entry.path.toByteArray()))
        }

        override fun close() {
            closed++
        }
    }

    private fun ArchiveReader.text(i: Int) = read(entries[i]) { String(it.readBytes()) }

    @Test
    fun anEntryUnpacksThoseBeforeItOnceAndAllAreKept() {
        val solid = Solid(6)
        val reader = ExtractingReader(solid, tmp.newFolder("pages"))
        assertEquals("p3.jpg", reader.text(3))
        assertEquals(listOf(0, 1, 2, 3), solid.reads)
        // Behind the pass, or the page itself again: from the kept files.
        assertEquals("p1.jpg", reader.text(1))
        assertEquals("p3.jpg", reader.text(3))
        // Further on, the pass goes on from where it stopped.
        assertEquals("p5.jpg", reader.text(5))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), solid.reads)
        assertEquals(6, reader.unpacked)
    }

    @Test
    fun anEntryBehindThePassThatIsNoLongerKeptIsUnpackedAnew() {
        val solid = Solid(4)
        val dir = tmp.newFolder("pages")
        val reader = ExtractingReader(solid, dir)
        reader.text(3)
        assertTrue(java.io.File(dir, "1").delete())
        assertEquals("p1.jpg", reader.text(1))
        assertEquals(listOf(0, 1, 2, 3, 1), solid.reads)
    }

    @Test
    fun pagesKeptByAnEarlierReaderAreReadFromTheirFiles() {
        val dir = tmp.newFolder("pages")
        ExtractingReader(Solid(4), dir).text(2)
        val again = Solid(4)
        val reader = ExtractingReader(again, dir)
        assertEquals("p2.jpg", reader.text(2))
        assertEquals(emptyList<Int>(), again.reads)
        // Past them, the pass unpacks from the start without writing them again.
        assertEquals("p3.jpg", reader.text(3))
        assertEquals(listOf(0, 1, 2, 3), again.reads)
        assertEquals(1, reader.unpacked)
    }

    @Test
    fun closingClosesTheArchiveAndTellsOnce() {
        val solid = Solid(2)
        var told = 0
        val reader = ExtractingReader(solid, tmp.newFolder("pages")) { told++ }
        reader.close()
        reader.close()
        assertEquals(1, solid.closed)
        assertEquals(1, told)
    }
}
