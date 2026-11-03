package com.infinity.comicreader.compact

import com.infinity.comicreader.compact.OutputMarks.Operation
import com.infinity.comicreader.data.BookIdBytes
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.testing.Xxh3Port
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class OutputMarksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun hashOnTheJvm() {
        BookIds.hasher = Xxh3Port
    }

    @After
    fun forget() = BookIds.reset()

    /** A stored ZIP of [entries], no comment. */
    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val f = File(tmp.newFolder(), "book.cbz")
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.setMethod(ZipOutputStream.STORED)
            for ((name, bytes) in entries) {
                val e = ZipEntry(name)
                e.method = ZipEntry.STORED
                e.size = bytes.size.toLong()
                e.compressedSize = bytes.size.toLong()
                e.crc = CRC32().apply { update(bytes) }.value
                e.time = 315_532_800_000L
                zip.putNextEntry(e)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return f
    }

    private val pages = arrayOf("01.jpg" to "one".toByteArray(), "02.jpg" to "two".toByteArray(), "ComicInfo.xml" to "<x/>".toByteArray())

    /** [zip]'s comment, written over whatever it had. */
    private fun setComment(zip: File, comment: ByteArray) {
        val tail = OutputMarks.tail(SeekableInMemoryByteChannel(zip.readBytes()), Xxh3Port)!!
        RandomAccessFile(zip, "rw").use { raf ->
            raf.seek(tail.end + 20)
            raf.write(comment.size and 0xFF)
            raf.write(comment.size ushr 8)
            raf.write(comment)
            raf.setLength(tail.end + 22 + comment.size)
        }
    }

    private fun directoryHash(zip: File): Long = OutputMarks.tail(SeekableInMemoryByteChannel(zip.readBytes()), Xxh3Port)!!.directory

    private fun commentOf(zip: File): String = ZipFile(zip).use { it.comment.orEmpty() }

    @Test
    fun markWrittenAndVerified() {
        val f = zip(*pages)
        val hash = directoryHash(f)
        assertTrue(OutputMarks.write(f, ByteArray(0), Operation.COMPACTED, Xxh3Port))
        assertEquals("[i:Emanata compacted v1 ${"%016x".format(hash)}]", commentOf(f))
        assertEquals(hash, directoryHash(f))
        assertEquals(OutputMarks.Done("Emanata", compatible = false, repacked = false), OutputMarks.verified(f, Xxh3Port))
        // Still a ZIP every entry of which reads.
        ZipFile(f).use { z -> assertEquals("two", z.getInputStream(z.getEntry("02.jpg")).readBytes().decodeToString()) }
    }

    @Test
    fun aSourceCommentIsKeptWithTheBlockAfterItOnANewLine() {
        val f = zip(*pages)
        val kept = "Scanned for testing\nsecond line".toByteArray()
        assertTrue(OutputMarks.write(f, kept, Operation.REPACKED, Xxh3Port))
        val comment = commentOf(f)
        assertTrue(comment, comment.startsWith("Scanned for testing\nsecond line\n[i:Emanata repacked v1 "))
        assertEquals(1, OutputMarks.blocks(comment).size)
        assertTrue(OutputMarks.verified(f, Xxh3Port).repacked)
    }

    @Test
    fun oldBlocksAreDroppedNeverDoubled() {
        val f = zip(*pages)
        val kept = "Notes\n[i:Emanata compacted v1 0123456789abcdef]\n[i:OtherTool squeezed v3 xyz]".toByteArray()
        assertTrue(OutputMarks.write(f, kept, Operation.COMPATIBLE, Xxh3Port))
        val blocks = OutputMarks.blocks(commentOf(f))
        assertEquals(listOf("compatible"), blocks.map { it.operation })
        assertTrue(commentOf(f).startsWith("Notes\n[i:Emanata compatible"))
        val done = OutputMarks.verified(f, Xxh3Port)
        assertTrue(done.compatible)
        assertNull("its compacted block was dropped", done.compactedBy)
    }

    @Test
    fun anyToolOperationAndVersionParses() {
        val blocks = OutputMarks.blocks("x [i:Tool-9 squeezed v42 abcd] y\n[i:A b v7 0123456789ABCDEF][i:Big v v99999999999999999999 0000000000000000]")
        assertEquals(listOf("Tool-9", "A"), blocks.map { it.tool })
        assertEquals(listOf("squeezed", "b"), blocks.map { it.operation })
        assertEquals(listOf(42L, 7L), blocks.map { it.version })
        assertNull("not 16 hex digits: unverifiable", blocks[0].value)
        assertEquals(0x0123456789ABCDEFL, blocks[1].value)
    }

    @Test
    fun anotherToolsValidCompactedBlockCountsAsCompacted() {
        val f = zip(*pages)
        val hash = "%016x".format(directoryHash(f))
        setComment(f, "[i:SomeTool compacted v9 $hash]".toByteArray())
        assertEquals("SomeTool", OutputMarks.verified(f, Xxh3Port).compactedBy)
        setComment(f, "[i:SomeTool compatible v9 $hash]".toByteArray())
        assertEquals("only compacting counts from other tools", OutputMarks.Done.NOTHING, OutputMarks.verified(f, Xxh3Port))
        setComment(f, "[i:SomeTool compacted v9 not-a-hash]".toByteArray())
        assertNull(OutputMarks.verified(f, Xxh3Port).compactedBy)
    }

    @Test
    fun emanataBlocksCountAtTheirVersionOrLater() {
        val f = zip(*pages)
        val hash = "%016x".format(directoryHash(f))
        setComment(f, "[i:Emanata compacted v0 $hash]".toByteArray())
        assertNull("an older version may be offered again", OutputMarks.verified(f, Xxh3Port).compactedBy)
        setComment(f, "[i:Emanata compacted v2 $hash]".toByteArray())
        assertEquals("Emanata", OutputMarks.verified(f, Xxh3Port).compactedBy)
        setComment(f, "[i:Emanata compacted-partial v1 $hash]".toByteArray())
        assertEquals("Emanata", OutputMarks.verified(f, Xxh3Port).compactedBy)
    }

    @Test
    fun staleAfterOneEntryChanges() {
        val f = zip(*pages)
        assertTrue(OutputMarks.write(f, ByteArray(0), Operation.COMPACTED, Xxh3Port))
        val comment = ZipFile(f).use { it.comment }.toByteArray()
        val changed = zip(pages[0], "02.jpg" to "TWO".toByteArray(), pages[2])
        setComment(changed, comment)
        assertEquals(OutputMarks.Done.NOTHING, OutputMarks.verified(changed, Xxh3Port))
    }

    @Test
    fun anOversizedCommentSkipsTheMark() {
        val f = zip(*pages)
        val kept = ByteArray(OutputMarks.MAX_COMMENT - 10) { 'a'.code.toByte() }
        assertFalse(OutputMarks.write(f, kept, Operation.COMPACTED, Xxh3Port))
        assertArrayEquals(kept, OutputMarks.commentOf(f))
        assertEquals(OutputMarks.Done.NOTHING, OutputMarks.verified(f, Xxh3Port))
    }

    @Test
    fun theTailReadsThroughAChannelAsOnAShare() {
        val f = zip(*pages)
        assertTrue(OutputMarks.write(f, ByteArray(0), Operation.COMPACTED, Xxh3Port))
        val tail = OutputMarks.tail(SeekableInMemoryByteChannel(f.readBytes()), Xxh3Port)!!
        assertEquals("Emanata", OutputMarks.verified(tail).compactedBy)
    }

    @Test
    fun notAZipMarksNothing() {
        val f = File(tmp.root, "x.cbz").apply { writeBytes(ByteArray(100)) }
        assertEquals(OutputMarks.Done.NOTHING, OutputMarks.verified(f, Xxh3Port))
        assertEquals(0, OutputMarks.commentOf(f).size)
    }

    @Test
    fun theBookIdStillHashesTheFileAsItIs() {
        val f = zip(*pages)
        val before = BookIds.peek(f)
        assertTrue(OutputMarks.write(f, ByteArray(0), Operation.COMPACTED, Xxh3Port))
        val bytes = f.readBytes()
        val layout = BookIdBytes.of(bytes.size.toLong()) { at, into, offset, length -> bytes.copyInto(into, offset, at.toInt(), at.toInt() + length) }
        assertEquals(BookIdBytes.idOf(layout, Xxh3Port), BookIds.peek(f))
        assertNotEquals("the comment is in the file's tail", before, BookIds.peek(f))
    }
}
