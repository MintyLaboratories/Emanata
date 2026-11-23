package com.infinity.comicreader.compact

import com.infinity.comicreader.data.BookCaches
import com.infinity.comicreader.data.BookId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Books' data on its way to their rebuilt files, kept across a stop of the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandoversTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun entriesComeBackWithTheirIdenticalPages() {
        val dir = File(tmp.root, "handovers")
        val same = BookCaches.SamePages(intArrayOf(0, 2), listOf("a/1.jpg", "a/2.jpg", "a/3.jpg"), listOf("1.jpg", "3.jpg"))
        val kept = Handovers(dir).add(Handovers.Entry(BookId(1), BookId(2), "/b/Book.cbr", "/b/Book.cbz", true, intArrayOf(0, -1, 1), 2, same))
        Handovers(dir).add(Handovers.Entry(BookId(3), BookId(4), "/b/Other.cbz", "/b/Other.cbz", false, intArrayOf(0), 1))
        val read = Handovers(dir).entries()
        assertEquals(2, read.size)
        val first = read[0]
        assertEquals(BookId(1), first.old)
        assertEquals(BookId(2), first.new)
        assertEquals("/b/Book.cbz", first.target)
        assertTrue(first.removed)
        assertArrayEquals(intArrayOf(0, -1, 1), first.moves)
        assertEquals(2, first.pageCount)
        assertArrayEquals(intArrayOf(0, 2), first.same!!.pages)
        assertEquals(same.oldNames, first.same!!.oldNames)
        assertEquals(same.newNames, first.same!!.newNames)
        assertNull(read[1].same)
        Handovers(dir).remove(kept)
        assertEquals(listOf(BookId(3)), Handovers(dir).entries().map { it.old })
    }

    @Test
    fun anUnreadableEntryIsDropped() {
        val dir = File(tmp.root, "handovers").apply { mkdirs() }
        val bad = File(dir, "1.json").apply { writeText("""{"old":"0000000000000001","same":{"pages":[0]}}""") }
        assertTrue(Handovers(dir).entries().isEmpty())
        assertFalse(bad.exists())
    }
}
