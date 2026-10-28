package com.infinity.comicreader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class DuplicatesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(path: String, bytes: ByteArray): File =
        File(tmp.root, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    @Test
    fun groupsOnlyBitIdenticalFiles() {
        val content = Random(1).nextBytes(300_000)
        // Same size, head and tail as the copies, one byte apart in the middle.
        val near = content.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }
        val a = file("shelf/b/Book.cbz", content)
        val b = file("shelf/a/Book copy.cbz", content)
        val c = file("shelf/c/Other.cbz", near)
        val d = file("shelf/d/Small.cbz", Random(2).nextBytes(10))
        val groups = Duplicates.find(sequenceOf(a, b, c, d))
        assertEquals(1, groups.size)
        assertEquals(listOf(b, a), groups[0].files)
        assertEquals(300_000L, groups[0].wasted)
        assertEquals(Duplicates.quickHash(a), Duplicates.quickHash(c))
        assertNotEquals(Duplicates.fullHash(a), Duplicates.fullHash(c))
    }

    @Test
    fun mostWastedSpaceComesFirstAndEmptyFilesAreSkipped() {
        val small = Random(3).nextBytes(1000)
        val large = Random(4).nextBytes(5000)
        val files = listOf(
            file("1.cbz", small), file("2.cbz", small), file("3.cbz", small),
            file("4.cbz", large), file("5.cbz", large),
            file("6.cbz", ByteArray(0)), file("7.cbz", ByteArray(0))
        )
        val groups = Duplicates.find(files.asSequence())
        assertEquals(listOf(5000L, 1000L), groups.map { it.size })
        assertEquals(3, groups[1].files.size)
    }

    @Test
    fun quickHashTakesFilesUpToBothEndsWhole() {
        // 64-128 KiB: the bytes after the first 64 KiB count too.
        for (size in listOf(65_537, 100_000, 131_072)) {
            val content = Random(size).nextBytes(size)
            val tail = content.copyOf().also { it[size - 1] = (it[size - 1] + 1).toByte() }
            assertNotEquals("size $size", Duplicates.quickHash(file("a$size.cbz", content)), Duplicates.quickHash(file("b$size.cbz", tail)))
        }
        // Past that only the ends count: the middle is left to the full hash.
        val content = Random(6).nextBytes(131_073)
        val middle = content.copyOf().also { it[65_536] = (it[65_536] + 1).toByte() }
        assertEquals(Duplicates.quickHash(file("m1.cbz", content)), Duplicates.quickHash(file("m2.cbz", middle)))
    }

    @Test
    fun stopsWhenNoLongerWanted() {
        val content = Random(5).nextBytes(100)
        val groups = Duplicates.find(sequenceOf(file("x.cbz", content), file("y.cbz", content))) { false }
        assertTrue(groups.isEmpty())
    }
}
