package com.infinity.comicreader.ui

import com.infinity.comicreader.reader.Chapter
import com.infinity.comicreader.ui.ChapterDrawer.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChapterDrawerTest {

    private val parts = listOf(Chapter("Part 1", 0, 3), Chapter("Part 2", 3, 3))
    private val a = File("/shelf/Book 01.cbz")
    private val b = File("/shelf/Book 02.cbz")

    @Test
    fun bothSectionsInOrder() {
        val rows = ChapterDrawer.rows(parts, 4, listOf(a, b), a.absolutePath, setOf(b.absolutePath))
        assertEquals(
            listOf(
                Row.Header(inBook = true), Row.Part(parts[0], false), Row.Part(parts[1], true),
                Row.Header(inBook = false), Row.Sibling(a, current = true, read = false), Row.Sibling(b, current = false, read = true)
            ),
            rows
        )
        assertEquals(2, ChapterDrawer.currentRow(rows))
    }

    @Test
    fun aBookAloneInItsFolderHasNoFolderSection() {
        val rows = ChapterDrawer.rows(parts, 0, listOf(a), a.absolutePath, emptySet())
        assertFalse(rows.any { it is Row.Sibling })
        assertTrue(ChapterDrawer.hasEntries(parts, listOf(a)))
    }

    @Test
    fun aFlatBookShowsOnlyItsFolder() {
        val rows = ChapterDrawer.rows(emptyList(), 0, listOf(a, b), b.absolutePath, emptySet())
        assertEquals(Row.Header(inBook = false), rows.first())
        assertEquals(2, ChapterDrawer.currentRow(rows))
    }

    @Test
    fun nothingToOfferHidesTheDrawer() {
        assertFalse(ChapterDrawer.hasEntries(emptyList(), listOf(a)))
        assertEquals(emptyList<Row>(), ChapterDrawer.rows(emptyList(), 0, listOf(a), a.absolutePath, emptySet()))
        assertEquals(0, ChapterDrawer.currentRow(emptyList()))
    }
}
