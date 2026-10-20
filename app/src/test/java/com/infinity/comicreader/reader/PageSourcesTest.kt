package com.infinity.comicreader.reader

import com.infinity.comicreader.reader.Fixtures.entry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PageSourcesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cacheDir get() = File(tmp.root, "cache")

    private fun copies() = NestedArchives.dirIn(cacheDir).listFiles()?.filter { it.isFile }.orEmpty()

    @Test
    fun zipNamedCbrOpens() {
        val f = Fixtures.zip(File(tmp.root, "book.cbr"), entry("01.jpg"), entry("02.jpg"), entry("__MACOSX/._01.jpg"))
        PageSources.open(f, "UTF-8", cacheDir).use { assertEquals(2, it.pageCount) }
    }

    @Test
    fun nestedBookInCbrOpensWithEveryPage() {
        val f = Fixtures.zip(
            File(tmp.root, "book.cbr"),
            entry("Issue 2.cbz", Fixtures.zipBytes(entry("01.jpg"), entry("02.jpg"))),
            entry("Issue 1.cbr", Fixtures.rar5Bytes(entry("01.jpg"))),
            entry("Issue 3.cb7", Fixtures.sevenZip(File(tmp.newFolder(), "inner.cb7"), entry("01.jpg")).readBytes())
        )
        PageSources.openCover(f, "UTF-8", cacheDir).use { assertEquals(1, it.pageCount) }
        assertEquals(1, copies().size)
        PageSources.open(f, "UTF-8", cacheDir).use { assertEquals(4, it.pageCount) }
        assertEquals(3, copies().size)
    }

    @Test(expected = IOException::class)
    fun unknownContentFails() {
        val f = File(tmp.root, "book.cbz").apply { writeText("plain text") }
        PageSources.open(f, "UTF-8", cacheDir)
    }
}
