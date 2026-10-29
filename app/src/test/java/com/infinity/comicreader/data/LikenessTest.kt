package com.infinity.comicreader.data

import com.infinity.comicreader.reader.ComicInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A re-encoded copy is like its book; another book is not. */
class LikenessTest {

    private fun info(pages: String = "", extra: String = "") = ComicInfo.parse(
        """<?xml version="1.0"?><ComicInfo><Series>Night  Owls</Series><Number>3</Number>
          |<Writer>A. Writer</Writer>$extra<Pages>$pages</Pages></ComicInfo>""".trimMargin()
    )

    private val jpgs = listOf("p01.jpg", "p02.jpg", "p03.jpg")
    private val jxls = listOf("p01.jxl", "p02.jxl", "p03.jxl")

    @Test
    fun comicInfoNamingTheBookMakesAStrongLikenessThatIgnoresPageDetails() {
        val original = Likeness.of(info("""<Page Image="0" ImageSize="91234"/>"""), jpgs, "Night Owls 003.cbr")
        val copy = Likeness.of(info("""<Page Image="0" ImageSize="70001"/>"""), listOf("a.jxl", "b.jxl", "c.jxl"), "renamed.cbz")
        assertTrue(original.strong)
        assertEquals(original.print, copy.print)
        // Another issue, or another page count, is another book.
        assertNotEquals(original.print, Likeness.of(info(extra = "<Title>Other</Title>"), jpgs, "x.cbz").print)
        assertNotEquals(original.print, Likeness.of(info(), jpgs.take(2), "x.cbz").print)
    }

    @Test
    fun comicInfoPageCountIsLeftOutAsThePagesThemselvesAreCounted() {
        val tagged = Likeness.of(info(extra = "<PageCount>3</PageCount>"), jpgs, "a.cbz")
        assertEquals(tagged.print, Likeness.of(info(extra = "<pagecount>4</pagecount>"), jxls, "b.cbz").print)
        assertEquals(tagged.print, Likeness.of(info(), jxls, "b.cbz").print)
    }

    @Test
    fun valuesAreComparedWithSpacesCollapsedAndNamesInAnyCase() {
        val a = ComicInfo.parse("<ComicInfo><Title> One  Shot </Title><Year>2001</Year></ComicInfo>")
        val b = ComicInfo.parse("<ComicInfo><year>2001</year><title>One Shot</title></ComicInfo>")
        assertEquals(Likeness.of(a, jpgs, "a.cbz").print, Likeness.of(b, jpgs, "b.cbz").print)
    }

    @Test
    fun withoutComicInfoNamingTheBookPageAndFileNamesDecide() {
        val vague = ComicInfo.parse("<ComicInfo><Series>Night Owls</Series></ComicInfo>")
        val original = Likeness.of(vague, jpgs, "Night Owls 003.cbr")
        assertFalse(original.strong)
        assertEquals(original.print, Likeness.of(null, jxls, "night owls 003.cbz").print)
        assertNotEquals(original.print, Likeness.of(null, jxls, "Night Owls 004.cbz").print)
        assertNotEquals(original.print, Likeness.of(null, listOf("p01.jxl", "p02.jxl", "p04.jxl"), "Night Owls 003.cbz").print)
        // Folders inside the archive do not count: a flattened copy is alike.
        assertEquals(original.print, Likeness.of(null, jpgs.map { "Scans/$it" }, "Night Owls 003.cbz").print)
    }

    @Test
    fun pagesMoveByNameWhereTheyCanElseByPlace() {
        assertArrayEquals(intArrayOf(0, 1, 2), Likeness.moves(listOf("a", "b", "c"), listOf("a", "b", "c")))
        assertArrayEquals(intArrayOf(1, 0, 2), Likeness.moves(listOf("b", "a", "c"), listOf("a", "b", "c")))
        assertArrayEquals(intArrayOf(0, 1, 2), Likeness.moves(listOf("x", "y", "z"), listOf("a", "b", "c")))
        // "x" takes its free place 1; "y" finds its place 2 taken by "c".
        assertArrayEquals(intArrayOf(2, 1, -1), Likeness.moves(listOf("c", "x", "y"), listOf("a", "b", "c")))
    }
}
