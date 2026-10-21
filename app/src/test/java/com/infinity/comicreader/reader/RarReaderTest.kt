package com.infinity.comicreader.reader

import com.infinity.comicreader.reader.Fixtures.entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RarReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pages = arrayOf(entry("02.jpg"), entry("10.jpg"), entry("01.jpg"), entry("notes.txt"))

    private fun ArchiveSource.text(index: Int) = readPage(index) { String(it.readBytes()) }

    private fun check(file: File) {
        val reader = ArchiveReader.open(file, "UTF-8")
        assertTrue(reader is RarReader)
        ArchiveSource(reader!!).use { src ->
            assertEquals(listOf("01.jpg", "02.jpg", "10.jpg"), src.pagePaths)
            assertEquals("10.jpg", src.text(2))
        }
    }

    @Test
    fun rar4Pages() {
        check(File(tmp.root, "book.cbr").apply { writeBytes(Fixtures.rar4Bytes(*pages)) })
    }

    @Test
    fun rar5Pages() {
        check(File(tmp.root, "book.cbr").apply { writeBytes(Fixtures.rar5Bytes(*pages)) })
    }

    @Test
    fun rarSubfoldersAndJunk() {
        val entries = arrayOf(entry("b/01.jpg"), entry("a/02.jpg"), entry("a/01.jpg"), entry("__MACOSX/a/._01.jpg"))
        for (bytes in listOf(Fixtures.rar4Bytes(*entries), Fixtures.rar5Bytes(*entries))) {
            val f = File(tmp.newFolder(), "book.cbr").apply { writeBytes(bytes) }
            ArchiveSource(ArchiveReader.open(f, "UTF-8")!!).use { src ->
                assertEquals(3, src.pageCount)
                assertEquals(listOf("a/01.jpg", "a/02.jpg", "b/01.jpg"), src.pagePaths.map { it.replace('\\', '/') })
            }
        }
    }

    @Test
    fun rarNamedCbzOpensAsRar() {
        check(File(tmp.root, "book.cbz").apply { writeBytes(Fixtures.rar5Bytes(*pages)) })
    }

    @Test
    fun rarInsideZipAndZipInsideRar() {
        val cache = File(tmp.root, "cache")
        val f = Fixtures.zip(
            File(tmp.root, "book.cbz"),
            entry("Issue 1.cbr", Fixtures.rar4Bytes(entry("01.jpg", "issue1".toByteArray()))),
            entry("Issue 2.cbr", Fixtures.rar5Bytes(entry("01.jpg", "issue2".toByteArray())))
        )
        ArchiveSource(ZipReader(f, "UTF-8"), NestedArchives.keyOf(f), NestedArchives(cache, "UTF-8")).use { src ->
            assertEquals(listOf("Issue 1.cbr/01.jpg", "Issue 2.cbr/01.jpg"), src.pagePaths)
            assertEquals("issue2", src.text(1))
        }
        val outer = File(tmp.root, "book.cbr").apply {
            writeBytes(Fixtures.rar5Bytes(entry("Issue 1.cbz", Fixtures.zipBytes(entry("01.jpg", "inner".toByteArray())))))
        }
        val reader = ArchiveReader.open(outer, "UTF-8")!!
        ArchiveSource(reader, NestedArchives.keyOf(outer), NestedArchives(cache, "UTF-8")).use { src ->
            assertEquals(listOf("Issue 1.cbz/01.jpg"), src.pagePaths)
            assertEquals("inner", src.text(0))
        }
    }

    @Test
    fun partlyReadEntryLeavesTheNextReadWhole() {
        for (bytes in listOf(Fixtures.rar4Bytes(*pages), Fixtures.rar5Bytes(*pages))) {
            val f = File(tmp.newFolder(), "book.cbr").apply { writeBytes(bytes) }
            ArchiveSource(ArchiveReader.open(f, "UTF-8")!!).use { src ->
                assertEquals('0'.code, src.readPage(0) { it.read() })
                assertEquals("02.jpg", src.text(1))
                assertEquals("01.jpg", src.text(0))
            }
        }
    }
}
