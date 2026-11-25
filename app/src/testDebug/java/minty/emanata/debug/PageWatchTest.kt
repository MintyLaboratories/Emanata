package minty.emanata.debug

import minty.emanata.debug.PageWatch.Ready
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun changedWhenTheLastViewOnScreenChanged() {
        var now = 100L
        val timed = PageWatch { now }
        timed.bound(left, 1)
        now = 200
        timed.bound(right, 2)
        now = 300
        timed.shown(left, 1, exact = true)
        // Bound again to the same page: no change.
        now = 400
        timed.bound(left, 1)
        assertEquals(300L, timed.changedAt(listOf(left, right)))
        assertEquals(200L, timed.changedAt(listOf(right)))
        timed.bound(right, PageWatch.NONE)
        assertEquals(300L, timed.changedAt(listOf(left, right)))
        assertEquals(0L, timed.changedAt(emptyList()))
    }

    @Test
    fun showingLeavesOutViewsStandingForNoPage() {
        watch.bound(left, 6)
        watch.bound(right, PageWatch.NONE)
        assertEquals(PageWatch.Showing(listOf(6), setOf(left), 11), watch.showing(listOf(left, right), 11))
    }

    @Test
    fun theOtherHalfOfASplitPageIsATurn() {
        // Item 8 shows page 5's first half in one view, item 9 its second half in another.
        watch.bound(left, 5)
        watch.bound(right, 5)
        val first = watch.showing(listOf(left), 8)
        val second = watch.showing(listOf(right), 9)
        assertTrue(first.changedTo(second))
        assertFalse(first.backTo(second))
        assertTrue(second.backTo(first))
        // Mid-slide both halves show, the reader already at the next item.
        assertTrue(first.changedTo(watch.showing(listOf(left, right), 9)))
    }

    @Test
    fun theSamePageInAnotherViewWithoutAMoveIsNoTurn() {
        watch.bound(left, 5)
        watch.bound(right, 5)
        val before = watch.showing(listOf(left), 8)
        // A view bound anew at the same item, as after a relayout.
        assertFalse(before.changedTo(watch.showing(listOf(right), 8)))
        assertFalse(before.changedTo(watch.showing(listOf(left), 9)))
        assertFalse(before.changedTo(watch.showing(emptyList(), 9)))
    }

    @Test
    fun theSamePageReboundFarOffIsNoTurn() {
        watch.bound(left, 5)
        watch.bound(right, 5)
        val before = watch.showing(listOf(left), 8)
        // A relayout splitting pages renumbers items: the same page now at item 12, in a new view.
        assertFalse(before.changedTo(watch.showing(listOf(right), 12)))
        assertFalse(before.changedTo(watch.showing(listOf(right), 6)))
        // An item unknown on either side orders nothing.
        assertFalse(watch.showing(listOf(left), -1).changedTo(watch.showing(listOf(right), 0)))
        assertFalse(before.changedTo(watch.showing(listOf(right), -1)))
        // One item back is the first half again.
        assertTrue(before.changedTo(watch.showing(listOf(right), 7)))
    }

    @Test
    fun otherPagesAreATurnWhicheverItemTheReaderTells() {
        watch.bound(left, 2)
        watch.bound(right, 3)
        val before = watch.showing(listOf(left), -1)
        val after = watch.showing(listOf(right), -1)
        assertTrue(before.changedTo(after))
        assertFalse(before.backTo(after))
        assertTrue(after.backTo(before))
        // Unknown items order nothing.
        assertFalse(before.backTo(before.copy(views = setOf(right))))
    }

    @Test
    fun forgottenViewsAreUnknown() {
        watch.bound(left, 3)
        watch.forget(left)
        assertEquals(emptySet<Any>(), watch.views)
        assertEquals(Ready.NOTHING, watch.ready(listOf(left)))
    }
}
