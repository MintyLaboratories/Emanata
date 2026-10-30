package com.infinity.comicreader.debug

import com.infinity.comicreader.debug.PageWatch.Ready
import org.junit.Assert.assertEquals
import org.junit.Test

class PageWatchTest {
    private val watch = PageWatch()
    private val left = Any()
    private val right = Any()

    @Test
    fun nothingUntilAViewStandsForAPage() {
        assertEquals(Ready.NOTHING, watch.ready(listOf(left)))
        watch.bound(left, PageWatch.NONE)
        assertEquals(Ready.NOTHING, watch.ready(listOf(left)))
    }

    @Test
    fun aStandInIsVisibleAndTheFinalBitmapSharp() {
        watch.bound(left, 4)
        assertEquals(Ready.NOTHING, watch.ready(listOf(left)))
        watch.shown(left, 4, exact = false)
        assertEquals(Ready.VISIBLE, watch.ready(listOf(left)))
        watch.shown(left, 4, exact = true)
        assertEquals(Ready.SHARP, watch.ready(listOf(left)))
    }

    @Test
    fun theLastPageOfAPairDecides() {
        watch.bound(left, 2)
        watch.bound(right, 3)
        watch.shown(left, 2, exact = true)
        assertEquals(Ready.NOTHING, watch.ready(listOf(left, right)))
        watch.shown(right, 3, exact = false)
        assertEquals(Ready.VISIBLE, watch.ready(listOf(left, right)))
        watch.shown(right, 3, exact = true)
        assertEquals(Ready.SHARP, watch.ready(listOf(left, right)))
    }

    @Test
    fun anEmptySlotBesideAPageIsLeftOut() {
        watch.bound(left, 9)
        watch.shown(left, 9, exact = true)
        watch.bound(right, PageWatch.NONE)
        watch.shown(right, PageWatch.NONE, exact = false)
        assertEquals(Ready.SHARP, watch.ready(listOf(left, right)))
        assertEquals(listOf(9), watch.pages(listOf(left, right)))
    }

    @Test
    fun aViewBoundAnewShowsTheOldPageUntilItsOwnArrives() {
        watch.bound(left, 1)
        watch.shown(left, 1, exact = true)
        watch.bound(left, 2)
        assertEquals(Ready.NOTHING, watch.ready(listOf(left)))
        watch.shown(left, 2, exact = false)
        assertEquals(Ready.VISIBLE, watch.ready(listOf(left)))
    }

    @Test
    fun aViewBoundAgainToThePageItShowsStaysSharp() {
        watch.bound(left, 5)
        watch.shown(left, 5, exact = true)
        watch.bound(left, 5)
        assertEquals(Ready.SHARP, watch.ready(listOf(left)))
    }

    @Test
    fun aClearedViewShowsNothing() {
        watch.bound(left, 7)
        watch.shown(left, 7, exact = true)
        watch.shown(left, PageWatch.NONE, exact = true)
        assertEquals(Ready.NOTHING, watch.ready(listOf(left)))
    }

    @Test
    fun pagesFollowTheViewsOrderOnce() {
        watch.bound(left, 3)
        watch.bound(right, 3)
        assertEquals(listOf(3), watch.pages(listOf(left, right)))
        watch.bound(right, 4)
        assertEquals(listOf(4, 3), watch.pages(listOf(right, left)))
    }

    @Test
    fun forgottenViewsAreUnknown() {
        watch.bound(left, 3)
        watch.forget(left)
        assertEquals(emptySet<Any>(), watch.views)
        assertEquals(Ready.NOTHING, watch.ready(listOf(left)))
    }
}
