package com.infinity.comicreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersTest {

    @Test
    fun foldersInsideAnArchiveAreChapters() {
        val paths = listOf("Part 1/01.jpg", "Part 1/02.jpg", "Part 2/01.jpg", "Part 3/01.jpg", "Part 3/02.jpg")
        assertEquals(
            listOf(Chapter("Part 1", 0, 2), Chapter("Part 2", 2, 1), Chapter("Part 3", 3, 2)),
            Chapters.of(paths)
        )
    }

    @Test
    fun innerArchivesOfANestedBookAreChapters() {
        val paths = listOf("Issue 1.cbz/01.jpg", "Issue 1.cbz/02.jpg", "Issue 2.cbr/01.jpg", "Issue 3.cb7/01.png")
        assertEquals(
            listOf(Chapter("Issue 1", 0, 2), Chapter("Issue 2", 2, 1), Chapter("Issue 3", 3, 1)),
            Chapters.of(paths)
        )
    }

    @Test
    fun sharedFoldersAreLeftOut() {
        val paths = listOf("Book/Vol 1/a/01.jpg", "Book/Vol 1/a/02.jpg", "Book/Vol 2/01.jpg")
        assertEquals(listOf(Chapter("Vol 1", 0, 2), Chapter("Vol 2", 2, 1)), Chapters.of(paths))
    }

    @Test
    fun deeperFoldersStayInTheirChapter() {
        val paths = listOf("Ch 1/a/01.jpg", "Ch 1/b/01.jpg", "Ch 2/01.jpg")
        assertEquals(listOf(Chapter("Ch 1", 0, 2), Chapter("Ch 2", 2, 1)), Chapters.of(paths))
    }

    @Test
    fun pagesOutsideFoldersHaveNoName() {
        val paths = listOf("cover.jpg", "Ch 1/01.jpg", "Ch 2/01.jpg", "notes.png")
        assertEquals(
            listOf(Chapter(null, 0, 1), Chapter("Ch 1", 1, 1), Chapter("Ch 2", 2, 1), Chapter(null, 3, 1)),
            Chapters.of(paths)
        )
    }

    @Test
    fun backslashesSeparateToo() {
        val paths = listOf("Ch 1\\01.jpg", "Ch 2\\01.jpg")
        assertEquals(listOf(Chapter("Ch 1", 0, 1), Chapter("Ch 2", 1, 1)), Chapters.of(paths))
    }

    @Test
    fun aSingleFolderHasNoChapters() {
        assertTrue(Chapters.of(listOf("Book/01.jpg", "Book/02.jpg")).isEmpty())
        assertTrue(Chapters.of(listOf("01.jpg", "02.jpg")).isEmpty())
        assertTrue(Chapters.of(emptyList()).isEmpty())
    }

    @Test
    fun pagesWithoutPathsHaveNoChapters() {
        assertTrue(Chapters.of(listOf(null, null)).isEmpty())
        assertTrue(Chapters.of(listOf("Ch 1/01.jpg", null)).isEmpty())
    }

    @Test
    fun findsTheChapterOfAPage() {
        val chapters = listOf(Chapter("A", 0, 3), Chapter("B", 3, 2))
        assertEquals("A", Chapters.at(chapters, 2)?.name)
        assertEquals("B", Chapters.at(chapters, 3)?.name)
        assertEquals(4, chapters[1].last)
        assertNull(Chapters.at(chapters, 5))
    }
}
