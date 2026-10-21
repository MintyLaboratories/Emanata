package com.infinity.comicreader.ui

import com.infinity.comicreader.data.PeekMode
import com.infinity.comicreader.ui.PeekGesture.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeekGestureTest {

    private val slop = 20f
    private val full = 400f

    private fun spread(vararg spans: Float) = PeekGesture(slop, full).apply {
        begin(spans.first())
        for (s in spans.drop(1)) move(s)
    }

    @Test
    fun startsOnlyPastTheSlop() {
        val g = PeekGesture(slop, full)
        g.begin(100f)
        assertFalse(g.move(115f))
        assertFalse(g.peeking)
        assertTrue(g.move(125f))
        assertTrue(g.peeking)
        // Growth counts from the slop, so the grid starts at the tile.
        assertEquals(5f / full, g.progress, 1e-6f)
        assertFalse(g.move(200f))
    }

    @Test
    fun progressFollowsTheSpreadAndPinchingBack() {
        val g = spread(100f, 130f, 320f)
        assertEquals(0.5f, g.progress, 1e-6f)
        g.move(1000f)
        assertEquals(1f, g.progress, 0f)
        g.move(50f)
        assertEquals(0f, g.progress, 0f)
    }

    @Test
    fun releaseCollapsesKeepsOpenOrOpens() {
        assertEquals(Release.COLLAPSE, spread(100f, 130f, 150f).release(PeekMode.PEEK_OPEN))
        assertEquals(Release.KEEP_OPEN, spread(100f, 130f, 320f).release(PeekMode.PEEK_OPEN))
        assertEquals(Release.OPEN_BOOK, spread(100f, 130f, 600f).release(PeekMode.PEEK_OPEN))
        // Plain Peek never opens the book.
        assertEquals(Release.KEEP_OPEN, spread(100f, 130f, 600f).release(PeekMode.PEEK))
        // Spread fully, then pinched back: collapses.
        assertEquals(Release.COLLAPSE, spread(100f, 130f, 600f, 110f).release(PeekMode.PEEK_OPEN))
    }

    @Test
    fun outcomeThresholds() {
        assertEquals(Release.COLLAPSE, PeekGesture.outcome(PeekGesture.COLLAPSE_BELOW - 0.01f, PeekMode.PEEK_OPEN))
        assertEquals(Release.KEEP_OPEN, PeekGesture.outcome(PeekGesture.COLLAPSE_BELOW, PeekMode.PEEK_OPEN))
        assertEquals(Release.KEEP_OPEN, PeekGesture.outcome(0.99f, PeekMode.PEEK_OPEN))
        assertEquals(Release.OPEN_BOOK, PeekGesture.outcome(1f, PeekMode.PEEK_OPEN))
    }

    @Test
    fun theCardGrowsFromTheTileWithoutStretching() {
        val tile = Box(100f, 200f, 100f, 150f)
        val full = Box(0f, 0f, 800f, 1200f)
        val start = PeekFrame.of(Box.lerp(tile, full, 0f), full)
        assertEquals(0.125f, start.scale, 1e-6f)
        assertEquals(100f, start.dx, 1e-4f)
        assertEquals(200f, start.dy, 1e-4f)
        // The tile's height in the card's own pixels: 150 / 0.125.
        assertEquals(1200f, start.clipHeight, 1e-3f)

        val wideTile = Box(0f, 0f, 200f, 100f)
        val early = PeekFrame.of(wideTile, full)
        assertEquals(400f, early.clipHeight, 1e-3f)

        val end = PeekFrame.of(Box.lerp(tile, full, 1f), full)
        assertEquals(1f, end.scale, 1e-6f)
        assertEquals(0f, end.dx, 1e-4f)
        assertEquals(1200f, end.clipHeight, 1e-3f)
    }

    @Test
    fun boxesInterpolate() {
        val mid = Box.lerp(Box(0f, 0f, 10f, 10f), Box(100f, 200f, 110f, 210f), 0.5f)
        assertEquals(Box(50f, 100f, 60f, 110f), mid)
    }
}
