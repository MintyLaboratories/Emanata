package com.infinity.comicreader.compact

import com.infinity.comicreader.compact.OutputMarks.Entry
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

    private fun hex(zip: File) = "%016x".format(directoryHash(zip))

    private fun commentOf(zip: File): String = String(OutputMarks.commentOf(zip), Charsets.ISO_8859_1)

    private fun write(zip: File, kept: String, vararg ops: Operation) = OutputMarks.write(zip, kept.toByteArray(Charsets.ISO_8859_1), ops.toList(), Xxh3Port)

    private fun chainOf(zip: File) = OutputMarks.chain(commentOf(zip), directoryHash(zip)).joinToString(", ")

    private fun entries(text: String) = if (text.isEmpty()) emptyList() else text.split(",").map { e ->
        val (tool, action, version) = e.trim().split(":")
        Entry(tool, action, version.removePrefix("v").toLong())
    }

    @Test
    fun markWrittenAndVerified() {
        val f = zip(*pages)
        val hash = directoryHash(f)
        assertTrue(write(f, "", Operation.COMPACT))
        assertEquals("[i: Emanata:compact:v1 | ${"%016x".format(hash)}]", commentOf(f))
        assertEquals(hash, directoryHash(f))
        assertEquals(OutputMarks.Done("Emanata", compatible = false, repacked = false), OutputMarks.verified(f, Xxh3Port))
        // Still a ZIP every entry of which reads.
        ZipFile(f).use { z -> assertEquals("two", z.getInputStream(z.getEntry("02.jpg")).readBytes().decodeToString()) }
    }

    @Test
    fun theHumanTextIsKeptByteForByteWithTheBlockOnItsOwnLastLine() {
        for (text in listOf("Scanned for testing\nsecond line", "Notes\n", "Notes\r\n", "  spaced  ", "caf\u00e9")) {
            val f = zip(*pages)
            assertTrue(write(f, text, Operation.REPACK))
            val comment = commentOf(f)
            assertEquals("$text\n[i: Emanata:repack:v1 | ${hex(f)}]", comment)
            assertEquals(text, OutputMarks.withoutBlocks(comment))
            assertTrue(OutputMarks.verified(f, Xxh3Port).repacked)
        }
    }

    @Test
    fun aBlockGoesWithOneLineBreakBeforeItOrAfterItAtTheStart() {
        val block = "[i: A:b:v1 | 0123456789abcdef]"
        assertEquals("Notes", OutputMarks.withoutBlocks("Notes\r\n$block"))
        assertEquals("Notes\n", OutputMarks.withoutBlocks("Notes\n\n$block"))
        assertEquals("Notes", OutputMarks.withoutBlocks("$block\nNotes"))
        assertEquals("Notes", OutputMarks.withoutBlocks("$block$block\nNotes"))
        assertEquals("a  b", OutputMarks.withoutBlocks("a $block b"))
    }

    @Test
    fun theSourcesChainGetsOurEntryAndANewHash() {
        val f = zip(*pages)
        assertTrue(write(f, "Notes\n[i: Other:compact-max:v2 | 0123456789abcdef]", Operation.COMPATIBLE))
        assertEquals("Notes\n[i: Other:compact-max:v2, Emanata:compatible:v1 | ${hex(f)}]", commentOf(f))
        val done = OutputMarks.verified(f, Xxh3Port)
        assertTrue(done.compatible)
        assertNull("making compatible changed the pages after compacting", done.compactedBy)
    }

    @Test
    fun anEntryTheSameAsTheLastIsNotRepeated() {
        val f = zip(*pages)
        assertTrue(write(f, "[i: Emanata:compact:v1 | 0123456789abcdef]", Operation.COMPACT))
        assertEquals("Emanata:compact:v1", chainOf(f))
        assertEquals(entries("A:x:v1, B:y:v2"), OutputMarks.appended(entries("A:x:v1, B:y:v2"), entries("b:Y:v2")))
        assertEquals(entries("A:x:v1, B:y:v2, A:x:v1"), OutputMarks.appended(entries("A:x:v1, B:y:v2"), entries("A:x:v1")))
        assertEquals(entries("A:x:v1, B:y:v2, B:y:v3"), OutputMarks.appended(entries("A:x:v1, B:y:v2"), entries("B:y:v3")))
        assertEquals("merged blocks repeat none either", entries("A:x:v1"), OutputMarks.appended(entries("A:x:v1, A:x:v1"), entries("")))
    }

    @Test
    fun severalBlocksMergeIntoOneChainInOrder() {
        val f = zip(*pages)
        assertTrue(write(f, "[i: A:x:v1 | 0123456789abcdef]\ncredits\n[i:B:y:v2|fedcba9876543210]", Operation.PACK, Operation.COMPACT))
        assertEquals("credits\n[i: A:x:v1, B:y:v2, Emanata:pack:v1, Emanata:compact:v1 | ${hex(f)}]", commentOf(f))
        assertEquals(1, OutputMarks.blocks(commentOf(f)).size)
        assertEquals("Emanata", OutputMarks.verified(f, Xxh3Port).compactedBy)
    }

    @Test
    fun blocksParseWithOddSpacingAndCase() {
        val blocks = OutputMarks.blocks("x [i:\tTool-9 : squeeze.it :v42 ,A_b:c:v7|0123456789ABCDEF  ] y\n[i:Big:v:v99999999999999999999|0000000000000000]")
        assertEquals(1, blocks.size)
        assertEquals(listOf(Entry("Tool-9", "squeeze.it", 42), Entry("A_b", "c", 7)), blocks[0].entries)
        assertEquals(0x0123456789ABCDEFL, blocks[0].hash)
        for (none in listOf(
            "[i: A:b:v1 | 0123456789abcde]",
            "[i: A:b:v1 | 0123456789abcdef0]",
            "[ i: A:b:v1 | 0123456789abcdef]",
            "[I: A:b:v1 | 0123456789abcdef]",
            "[i: A:b:1 | 0123456789abcdef]",
            "[i: A b:v1 | 0123456789abcdef]",
            "[i: A:b:v1,\n C:d:v2 | 0123456789abcdef]",
            "[i: | 0123456789abcdef]",
            "[i:Emanata compacted v1 0123456789abcdef]"
        )) assertEquals(none, emptyList<OutputMarks.Block>(), OutputMarks.blocks(none))
    }

    @Test
    fun anOperationIsDoneUntilALaterEntryChangesPages() {
        fun done(chain: String) = OutputMarks.done(entries(chain))
        assertEquals("Emanata", done("Emanata:compact:v1, Other:embed:v3, X:Metadata:v1, Y:tag:v1").compactedBy)
        assertNull(done("Emanata:compact:v1, Other:recolor:v1").compactedBy)
        assertNull(done("Emanata:compact:v1, Emanata:compatible:v1").compactedBy)
        assertTrue(done("Emanata:compatible:v1, Other:embed:v1").compatible)
        assertFalse(done("Emanata:compatible:v1, Emanata:compact:v1").compatible)
        assertTrue(done("Emanata:pack:v1, Emanata:repack:v1").repacked)
        assertEquals(OutputMarks.Done.NOTHING, done("Emanata:pack:v1"))
        assertEquals(setOf("embed", "metadata", "tag"), OutputMarks.KEEPS_PAGES)
    }

    @Test
    fun anyToolsCompactOrCompactVariantCountsAsCompacted() {
        val f = zip(*pages)
        setComment(f, "[i: SomeTool:compact-max:v9 | ${hex(f)}]".toByteArray())
        assertEquals("SomeTool", OutputMarks.verified(f, Xxh3Port).compactedBy)
        setComment(f, "[i: SomeTool:Compact:v0 | ${hex(f).uppercase()}]".toByteArray())
        assertEquals("any version, hash in either case", "SomeTool", OutputMarks.verified(f, Xxh3Port).compactedBy)
        setComment(f, "[i: SomeTool:compatible:v9 | ${hex(f)}]".toByteArray())
        assertEquals("only compacting counts from other tools", OutputMarks.Done.NOTHING, OutputMarks.verified(f, Xxh3Port))
        setComment(f, "[i: SomeTool:compacted:v9 | ${hex(f)}]".toByteArray())
        assertNull(OutputMarks.verified(f, Xxh3Port).compactedBy)
    }

    @Test
    fun emanataEntriesCountAtTheirVersionOrLater() {
        val f = zip(*pages)
        setComment(f, "[i: Emanata:compact:v0 | ${hex(f)}]".toByteArray())
        assertNull("an older version may be offered again", OutputMarks.verified(f, Xxh3Port).compactedBy)
        setComment(f, "[i: Emanata:compact:v2 | ${hex(f)}]".toByteArray())
        assertEquals("Emanata", OutputMarks.verified(f, Xxh3Port).compactedBy)
        setComment(f, "[i: Emanata:compact-partial:v1 | ${hex(f)}]".toByteArray())
        assertEquals("Emanata", OutputMarks.verified(f, Xxh3Port).compactedBy)
        setComment(f, "[i: Emanata:compatible:v0 | ${hex(f)}]".toByteArray())
        assertFalse(OutputMarks.verified(f, Xxh3Port).compatible)
    }

    @Test
    fun aStaleHashLeavesTheChainHistoryOnly() {
        val f = zip(*pages)
        assertTrue(write(f, "", Operation.COMPACT))
        val comment = commentOf(f)
        val changed = zip(pages[0], "02.jpg" to "TWO".toByteArray(), pages[2])
        setComment(changed, comment.toByteArray())
        assertEquals(OutputMarks.Done.NOTHING, OutputMarks.verified(changed, Xxh3Port))
        assertEquals(1, OutputMarks.blocks(commentOf(changed)).size)
        // Rewritten, the history stays with the new entry after it.
        val rewritten = zip(*pages)
        assertTrue(write(rewritten, comment, Operation.COMPATIBLE))
        assertEquals("Emanata:compact:v1, Emanata:compatible:v1", chainOf(rewritten))
    }

    @Test
    fun theOldestEntriesGoFirstWhenTheCommentWouldBeTooLong() {
        val all = OutputMarks.block(entries("A:x:v1, B:y:v2, Emanata:compact:v1"), 0L).length
        val room = OutputMarks.block(entries("B:y:v2, Emanata:compact:v1"), 0L).length
        val text = "a".repeat(OutputMarks.MAX_COMMENT - 1 - room)
        val f = zip(*pages)
        assertTrue(write(f, "$text\n[i: A:x:v1, B:y:v2 | 0123456789abcdef]", Operation.COMPACT))
        assertTrue(all > room)
        assertEquals(OutputMarks.MAX_COMMENT, commentOf(f).length)
        assertEquals("B:y:v2, Emanata:compact:v1", chainOf(f))
        assertEquals("Emanata", OutputMarks.verified(f, Xxh3Port).compactedBy)
    }

    @Test
    fun noBlockWhenEvenTheNewestEntryDoesNotFit() {
        val f = zip(*pages)
        val kept = ByteArray(OutputMarks.MAX_COMMENT - 10) { 'a'.code.toByte() }
        assertFalse(OutputMarks.write(f, kept, listOf(Operation.COMPACT), Xxh3Port))
        assertArrayEquals(kept, OutputMarks.commentOf(f))
        assertEquals(OutputMarks.Done.NOTHING, OutputMarks.verified(f, Xxh3Port))
    }

    @Test
    fun aDirectoryNotLocatedWritesTheHumanTextAlone() {
        val f = zip(*pages)
        // The end record's entry count a ZIP64 sentinel with no ZIP64 record to say where the directory is.
        RandomAccessFile(f, "rw").use { raf ->
            raf.seek(raf.length() - 22 + 10)
            raf.write(0xFF)
            raf.write(0xFF)
        }
        assertFalse(write(f, "Notes\n[i: A:x:v1 | 0123456789abcdef]", Operation.COMPACT))
        assertEquals("Notes", commentOf(f))
    }

    @Test
    fun theTailReadsThroughAChannelAsOnAShare() {
        val f = zip(*pages)
        assertTrue(write(f, "", Operation.COMPACT))
        val tail = OutputMarks.tail(SeekableInMemoryByteChannel(f.readBytes()), Xxh3Port)!!
        assertEquals("Emanata", OutputMarks.verified(tail).compactedBy)
    }

    /**
     * [zip] (no comment) with a ZIP64 end record and locator before its end record, as some
     * writers add to every archive, the end record's fields left as they were.
     */
    private fun withZip64(zip: File): File {
        val bytes = zip.readBytes()
        val end = bytes.size - 22
        val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val entries = b.getShort(end + 10).toLong() and 0xFFFF
        val size = b.getInt(end + 12).toLong()
        val offset = b.getInt(end + 16).toLong()
        val extra = java.nio.ByteBuffer.allocate(56 + 20).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        extra.putInt(0x06064b50).putLong(44).putShort(45).putShort(45).putInt(0).putInt(0)
            .putLong(entries).putLong(entries).putLong(size).putLong(offset)
        extra.putInt(0x07064b50).putInt(0).putLong(end.toLong()).putInt(1)
        val out = File(zip.parentFile, "zip64.cbz")
        out.writeBytes(bytes.copyOfRange(0, end) + extra.array() + bytes.copyOfRange(end, bytes.size))
        return out
    }

    @Test
    fun aZip64EndRecordWithoutSentinelsIsLeftOutOfTheDirectory() {
        val plain = zip(*pages)
        val f = withZip64(plain)
        assertEquals("the directory alone is hashed", directoryHash(plain), directoryHash(f))
        setComment(f, "[i: SomeTool:compact:v1 | ${hex(plain)}]".toByteArray())
        assertEquals("SomeTool", OutputMarks.verified(f, Xxh3Port).compactedBy)
        ZipFile(f).use { z -> assertEquals("one", z.getInputStream(z.getEntry("01.jpg")).readBytes().decodeToString()) }
    }

    @Test
    fun aZip64ArchiveIsMarked() {
        val plain = zip(*pages)
        val f = withZip64(plain)
        assertTrue(write(f, "", Operation.REPACK))
        assertEquals("[i: Emanata:repack:v1 | ${hex(plain)}]", commentOf(f))
        assertTrue(OutputMarks.verified(f, Xxh3Port).repacked)
    }

    @Test
    fun dataBeforeTheArchiveMovesNothing() {
        val plain = zip(*pages)
        val prefix = ByteArray(1000) { 7 }
        val f = File(plain.parentFile, "prefixed.cbz").apply { writeBytes(prefix + plain.readBytes()) }
        assertEquals(directoryHash(plain), directoryHash(f))
        assertTrue(write(f, "", Operation.COMPACT))
        assertEquals("Emanata", OutputMarks.verified(f, Xxh3Port).compactedBy)
        val zip64 = File(plain.parentFile, "prefixed64.cbz").apply { writeBytes(prefix + withZip64(plain).readBytes()) }
        assertEquals("found right before a ZIP64 locator whose offset is off", directoryHash(plain), directoryHash(zip64))
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
        assertTrue(write(f, "", Operation.COMPACT))
        val bytes = f.readBytes()
        val layout = BookIdBytes.of(bytes.size.toLong()) { at, into, offset, length -> bytes.copyInto(into, offset, at.toInt(), at.toInt() + length) }
        assertEquals(BookIdBytes.idOf(layout, Xxh3Port), BookIds.peek(f))
        assertNotEquals("the comment is in the file's tail", before, BookIds.peek(f))
    }
}
