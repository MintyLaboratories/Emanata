package com.infinity.comicreader.compact

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The books a selection of books and folders gives Compact and Make compatible. */
class RebuildTargetsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(path: String) = File(tmp.root, path).apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(4))
    }

    @Test
    fun foldersGiveEveryArchiveInsideInNaturalOrderOnce() {
        val loose = file("Loose.cbz")
        file("Series/Vol 10.cbz")
        file("Series/Vol 2.cb7")
        file("Series/Extras/Art.cbr")
        file("Series/cover.jpg")
        file("Series/Reading.pdf")
        file("Series/.cache/Old.cbz")
        file("Series/._Vol 2.cbz")
        val series = File(tmp.root, "Series")
        val got = RebuildTargets.of(listOf(loose), listOf(series, File(series, "Extras")))
        val names = got.map { it.removePrefix(tmp.root.absolutePath + File.separator).replace(File.separatorChar, '/') }
        assertEquals(listOf("Loose.cbz", "Series/Extras/Art.cbr", "Series/Vol 2.cb7", "Series/Vol 10.cbz"), names)
    }

    @Test
    fun aStoppedSearchEndsBeforeTheNextFolder() {
        file("A/1.cbz")
        file("A/B/2.cbz")
        var asked = 0
        org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
            RebuildTargets.of(emptyList(), listOf(File(tmp.root, "A"))) { ++asked > 1 }
        }
        assertEquals(2, asked)
    }

    @Test
    fun anEmptyOrMissingFolderGivesNothing() {
        val empty = File(tmp.root, "Empty").apply { mkdirs() }
        assertEquals(emptyList<String>(), RebuildTargets.of(emptyList(), listOf(empty, File(tmp.root, "Gone"))))
    }
}
