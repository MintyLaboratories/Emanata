package minty.emanata.ui

import minty.emanata.data.DetailDisplay
import minty.emanata.data.ListDensity
import minty.emanata.data.Prefs
import minty.emanata.data.RingOption
import minty.emanata.data.RingOptions
import minty.emanata.data.TabView
import minty.emanata.data.TitleDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class ViewRingTest {

    @Test
    fun byDefaultEveryTabGetsTheCommonFourPlusItsOwnButNoLayout() {
        val common = listOf(RingOption.SMALLER, RingOption.LARGER, RingOption.DETAIL, RingOption.SORT)
        assertEquals(common + RingOption.FOLDERS_FIRST, ViewRing.options(Prefs.TAB_BROWSE))
        assertEquals(common + RingOption.SECTIONS, ViewRing.options(Prefs.TAB_FAVORITES))
        assertEquals(common, ViewRing.options(Prefs.TAB_HISTORY))
        assertEquals(common, ViewRing.options(Prefs.TAB_BOOKMARKS))
    }

    @Test
    fun theRingFollowsTheChosenOptionsInTheirOrder() {
        // Three in History, Browse's and Favorites' own added in their tab.
        val three = RingOptions.DEFAULT.moved(3, 0).with(RingOption.LARGER, false)
        assertEquals(listOf(RingOption.SORT, RingOption.SMALLER, RingOption.DETAIL), ViewRing.options(Prefs.TAB_HISTORY, three))
        assertEquals(listOf(RingOption.SORT, RingOption.SMALLER, RingOption.DETAIL, RingOption.FOLDERS_FIRST), ViewRing.options(Prefs.TAB_BROWSE, three))
        // Six in Browse and in Favorites with Layout and Title lines: each tab's own counts there only.
        val six = RingOptions.DEFAULT.with(RingOption.DETAIL, false).with(RingOption.LAYOUT, true).with(RingOption.TITLE_LINES, true)
        assertEquals(7, six.visible.size)
        for (tab in listOf(Prefs.TAB_BROWSE, Prefs.TAB_FAVORITES)) assertEquals(tab, 6, ViewRing.options(tab, six).size)
        // No seventh in a tab, and no fewer than three.
        assertEquals(six, six.with(RingOption.DETAIL, true))
        assertEquals(three, three.with(RingOption.SORT, false))
        assertEquals(
            listOf(RingOption.SMALLER, RingOption.LARGER, RingOption.SORT, RingOption.SECTIONS, RingOption.LAYOUT, RingOption.TITLE_LINES),
            ViewRing.options(Prefs.TAB_FAVORITES, six)
        )
        assertEquals(listOf(RingOption.SMALLER, RingOption.LARGER, RingOption.SORT, RingOption.LAYOUT, RingOption.TITLE_LINES), ViewRing.options(Prefs.TAB_BOOKMARKS, six))
    }

    @Test
    fun theButtonsTapRunsItsActionWhereItApplies() {
        assertEquals(RingOption.SMALLER, ViewRing.tapAction(Prefs.TAB_HISTORY, RingOption.SMALLER))
        assertEquals(RingOption.FOLDERS_FIRST, ViewRing.tapAction(Prefs.TAB_BROWSE, RingOption.FOLDERS_FIRST))
        assertEquals(RingOption.LAYOUT, ViewRing.tapAction(Prefs.TAB_FAVORITES, RingOption.FOLDERS_FIRST))
        assertEquals(RingOption.LAYOUT, ViewRing.tapAction(Prefs.TAB_BROWSE, RingOption.SECTIONS))
    }

    @Test
    fun titleLinesGoRound() {
        assertEquals(TitleDisplay.BOUNCE, ViewRing.nextTitle(TitleDisplay.DOUBLE))
        assertEquals(TitleDisplay.HIDDEN, ViewRing.nextTitle(TitleDisplay.MARQUEE))
    }

    @Test
    fun sharedWordsAreSaidOnce() {
        assertEquals("Dir. LTR" to "RTL", ViewRing.compact("Dir. LTR", "Dir. RTL"))
        assertEquals("4" to "5 columns", ViewRing.compact("4 columns", "5 columns"))
        assertEquals("Grid" to "List", ViewRing.compact("Grid", "List"))
        assertEquals("On" to "On", ViewRing.compact("On", "On"))
        // A word in the middle alone is no shared start or end.
        assertEquals("a b c" to "d b e", ViewRing.compact("a b c", "d b e"))
    }

    @Test
    fun formsPairFullestFirstThenSayShortestOnce() {
        assertEquals(
            listOf(
                RingValue("Reading direction left-to-right first", "Reading direction right-to-left first"),
                RingValue("Reading direction LTR", "Reading direction RTL"),
                RingValue("Dir. LTR", "Dir. RTL"),
                RingValue("Dir. LTR", "RTL")
            ),
            ringForms(
                listOf("Reading direction left-to-right first", "Reading direction LTR", "Dir. LTR"),
                listOf("Reading direction right-to-left first", "Reading direction RTL", "Dir. RTL")
            )
        )
        // The shorter side's last form repeats.
        assertEquals(listOf(RingValue("Comfortable", "Dense"), RingValue("Comfy", "Dense")), ringForms(listOf("Comfortable", "Comfy"), listOf("Dense")))
        // At the end: the current value alone.
        assertEquals(listOf(RingValue("6 × 4", null), RingValue("6×4", null)), ringForms(listOf("6 × 4", "6×4"), null))
    }

    @Test
    fun fitShortensOnlyClashingLabelsTillClear() {
        // Label 1 clashes at its full form and clears at its second; label 0 never clashes.
        val forms = listOf(listOf(10f to 10f, 5f to 10f), listOf(30f to 10f, 20f to 10f, 10f to 10f))
        val (placed, pick) = RingGeometry.fit(forms) { sizes ->
            val clash = sizes[1].first > 20f
            RingGeometry.Placement(1f, emptyList(), if (clash) 3f else 0f, 0, if (clash) setOf(1) else emptySet())
        }
        assertEquals(listOf(0, 1), pick)
        assertEquals(0f, placed.overlap, 0f)
        // Never clear: the least overlap, with every clashing label at its shortest.
        val (worst, last) = RingGeometry.fit(forms) { sizes -> RingGeometry.Placement(1f, emptyList(), sizes[0].first + sizes[1].first, 0, setOf(0, 1)) }
        assertEquals(listOf(1, 2), last)
        assertEquals(15f, worst.overlap, 0f)
        // A clashing label at its shortest: with all, the others give way.
        val stuck = { sizes: List<Pair<Float, Float>> ->
            val clash = sizes[0].first > 5f
            RingGeometry.Placement(1f, emptyList(), if (clash) 1f else 0f, 0, if (clash) setOf(1) else emptySet())
        }
        assertEquals(listOf(0, 0), RingGeometry.fit(forms, lay = stuck).second)
        val (room, way) = RingGeometry.fit(forms, all = true, lay = stuck)
        assertEquals(listOf(1, 2), way)
        assertEquals(0f, room.overlap, 0f)
    }

    @Test
    fun gridStepsFromTheColumnsShownAndRowsWhenSet() {
        val auto = TabView(grid = true)
        assertEquals(auto.copy(columns = 4), ViewRing.larger(auto, columns = 5))
        assertEquals(auto.copy(columns = 6), ViewRing.smaller(auto, columns = 5))
        val set = TabView(grid = true, columns = 4, rows = 3)
        assertEquals(set.copy(columns = 3, rows = 2), ViewRing.larger(set, 4))
        assertEquals(set.copy(columns = 5, rows = 4), ViewRing.smaller(set, 4))
    }

    @Test
    fun gridStopsAtItsEnds() {
        assertNull(ViewRing.larger(TabView(grid = true, columns = 1), 1))
        assertNull(ViewRing.smaller(TabView(grid = true), 6))
        assertNull(ViewRing.smaller(TabView(grid = true, columns = 6, rows = 6), 6))
        // One side at its end stops both, keeping the shape.
        assertNull(ViewRing.larger(TabView(grid = true, columns = 1, rows = 3), 1))
        assertNull(ViewRing.smaller(TabView(grid = true, columns = 6, rows = 3), 6))
        assertNull(ViewRing.larger(TabView(grid = true, columns = 3, rows = 1), 3))
        assertEquals(TabView(grid = true, columns = 2, rows = 2), ViewRing.larger(TabView(grid = true, columns = 3, rows = 3), 3))
    }

    @Test
    fun listStepsDensity() {
        val normal = TabView(grid = false, density = ListDensity.NORMAL)
        assertEquals(ListDensity.LARGE, ViewRing.larger(normal, 3)?.density)
        assertEquals(ListDensity.COMPACT, ViewRing.smaller(normal, 3)?.density)
        assertNull(ViewRing.smaller(normal.copy(density = ListDensity.TINY), 3))
        assertNull(ViewRing.larger(normal.copy(density = ListDensity.HUGE), 3))
    }

    @Test
    fun detailGoesRound() {
        assertEquals(DetailDisplay.DENSE, ViewRing.nextDetail(DetailDisplay.COMFORTABLE))
        assertEquals(DetailDisplay.HIDDEN, ViewRing.nextDetail(DetailDisplay.DENSE))
        assertEquals(DetailDisplay.COMFORTABLE, ViewRing.nextDetail(DetailDisplay.HIDDEN))
    }

    @Test
    fun anglesRunFromUpTowardTheInside() {
        assertEquals(listOf(90.0, 112.5, 135.0, 157.5, 180.0), RingGeometry.angles(5, left = true))
        assertEquals(listOf(90.0, 67.5, 45.0, 22.5, 0.0), RingGeometry.angles(5, left = false))
        assertEquals(listOf(135.0), RingGeometry.angles(1, left = true))
    }

    @Test
    fun radiusKeepsNeighborsApart() {
        for (n in 2..6) {
            val r = RingGeometry.radius(n, spacing = 84f, min = 0f)
            val (x0, y0) = RingGeometry.spot(90.0, r)
            val (x1, y1) = RingGeometry.spot(90.0 + 90.0 / (n - 1), r)
            assertEquals(84.0, hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()), 0.01)
        }
        assertEquals(136f, RingGeometry.radius(2, 84f, 136f))
    }

    @Test
    fun spotsGoUpAndLeft() {
        val (ux, uy) = RingGeometry.spot(90.0, 100f)
        assertEquals(0f, ux, 0.001f)
        assertEquals(-100f, uy, 0.001f)
        val (lx, ly) = RingGeometry.spot(180.0, 100f)
        assertEquals(-100f, lx, 0.001f)
        assertEquals(0f, ly, 0.001f)
    }

    @Test
    fun labelsSitOutwardWithTheirNearSideAtReach() {
        // Straight up: centered above, its bottom edge at reach.
        val (ux, uy) = RingGeometry.labelCenter(90.0, 36f, 100f, 40f)
        assertEquals(0f, ux, 0.001f)
        assertEquals(-56f, uy, 0.001f)
        // Straight left: its right edge at reach.
        val (lx, ly) = RingGeometry.labelCenter(180.0, 36f, 100f, 40f)
        assertEquals(-86f, lx, 0.001f)
        assertEquals(0f, ly, 0.001f)
        // Diagonal: its near corner on the diagonal, no nearer than reach.
        val (dx, dy) = RingGeometry.labelCenter(135.0, 36f, 100f, 40f)
        val cornerX = dx + 50f
        val cornerY = dy + 20f
        assertEquals(cornerX, cornerY, 0.001f)
        assertTrue(hypot(cornerX.toDouble(), cornerY.toDouble()) >= 36.0 - 0.01)
        assertTrue(abs(cornerX) > 0f)
    }

    @Test
    fun labelSidesRunOutwardNearestFirstInwardLast() {
        assertEquals(listOf(162.0, 180.0, 90.0, 270.0, 0.0, 342.0), RingGeometry.sides(162.0))
        assertEquals(listOf(90.0, 0.0, 180.0, 270.0), RingGeometry.sides(90.0))
    }

    @Test
    fun aRoundButtonOverlapsABoxByDepth() {
        val box = RingGeometry.Box(0f, 0f, 100f, 40f)
        assertEquals(0f, box.overlap(150f, 20f, 28f), 0f)
        assertEquals(6f, box.overlap(122f, 20f, 28f), 0.001f)
        // Off the corner: clear along the diagonal though its bounding square would touch.
        assertEquals(0f, box.overlap(120f, 60f, 28f), 0f)
        assertTrue(box.overlap(50f, 20f, 28f) > 28f)
    }

    @Test
    fun roomStopsAtTheBoundsOnTheSideTheRingGrowsTo() {
        val bounds = RingGeometry.Box(8f, 32f, 392f, 792f)
        assertEquals(264f, RingGeometry.room(180.0, 300f, 500f, bounds, 0f, 0f, 56f, 56f), 0.01f)
        // A label 100 wide whose center sits 86 left of the spot.
        assertEquals(156f, RingGeometry.room(180.0, 300f, 500f, bounds, -86f, 0f, 100f, 40f), 0.01f)
        // Straight up, the top inset counts; the sides do not.
        assertEquals(440f, RingGeometry.room(90.0, 300f, 500f, bounds, 0f, 0f, 56f, 56f), 0.01f)
    }

    /** Six labels of typical widths and one at the label's widest, all two lines tall (dp). */
    private val labels = listOf(96f, 132f, 132f, 140f, 150f, 200f).map { it to 44f }

    private fun arrange(width: Float, height: Float, hubX: Float, hubY: Float, top: Float = 0f, left: Boolean = hubX > width / 2) =
        RingGeometry.Box(8f, top + 8f, width - 8f, height - 8f).let { bounds ->
            val angles = RingGeometry.angles(6, left)
            Triple(angles, bounds, RingGeometry.arrange(angles, hubX, hubY, bounds, 56f, RingGeometry.radius(6, 112f, 136f), 64f, 36f, 4f, labels))
        }

    /** Every button and label of [p] lies within [bounds], and no label covers a round button, the hub or another label. */
    private fun assertClear(angles: List<Double>, hubX: Float, hubY: Float, bounds: RingGeometry.Box, p: RingGeometry.Placement) {
        val buttons = listOf(hubX to hubY) + angles.map { a -> RingGeometry.spot(a, p.radius).let { (dx, dy) -> (hubX + dx) to (hubY + dy) } }
        for ((x, y) in buttons) assertTrue("$x, $y", RingGeometry.Box.around(x, y, 56f, 56f).within(bounds))
        for ((i, l) in p.boxes.withIndex()) {
            assertTrue("label $i $l", l.within(bounds))
            for ((x, y) in buttons) assertEquals("label $i on $x, $y", 0f, l.overlap(x, y, 28f), 0f)
            for (o in p.boxes.drop(i + 1)) assertEquals("label $i on $o", 0f, l.overlap(o), 0f)
        }
        assertEquals(0f, p.overlap, 0f)
    }

    @Test
    fun aRoomyWindowKeepsTheFullRingWithEveryLabelOutward() {
        val (angles, bounds, p) = arrange(800f, 1280f, 744f, 1180f)
        assertEquals(RingGeometry.radius(6, 112f, 136f), p.radius, 0.01f)
        assertEquals(0, p.aside)
        assertClear(angles, 744f, 1180f, bounds, p)
    }

    @Test
    fun aNarrowWindowShrinksTheRingSoLabelsClearTheButtons() {
        for (left in listOf(true, false)) {
            val hubX = if (left) 356f else 44f
            val (angles, bounds, p) = arrange(400f, 800f, hubX, 700f, left = left)
            assertTrue("$left ${p.radius}", p.radius < RingGeometry.radius(6, 112f, 136f))
            // Neighbors stay apart.
            assertTrue("$left ${p.radius}", p.radius >= RingGeometry.radius(6, 64f, 0f) - 0.01f)
            assertClear(angles, hubX, 700f, bounds, p)
        }
    }

    @Test
    fun landscapeKeepsEverythingBelowTheStatusBar() {
        val (angles, bounds, p) = arrange(1280f, 400f, 1224f, 344f, top = 24f)
        assertClear(angles, 1224f, 344f, bounds, p)
    }

    @Test
    fun placingAgainKeepsTheRadiusAndMovesAGrownLabelAside() {
        val (angles, bounds, p) = arrange(400f, 800f, 356f, 700f)
        val wider = labels.toMutableList().apply { this[5] = 200f to 66f }
        val again = RingGeometry.place(angles, 356f, 700f, bounds, 56f, p.radius, 36f, 4f, wider)
        assertEquals(p.radius, again.radius, 0f)
        assertTrue(again.boxes.all { it.within(bounds) })
    }

    /**
     * Two buttons, up and right of the hub, in bounds too tight above and right of them for
     * labels outward: placed in order, the first label takes the right of its button, where
     * the second's only free place (above its button) overlaps it.
     */
    private fun tight(labels: List<Pair<Float, Float>>, right: Float = 500f, exhaustive: Boolean) =
        RingGeometry.place(RingGeometry.angles(2, left = false), 300f, 500f, RingGeometry.Box(0f, 320f, right, 530f), 56f, 150f, 36f, 4f, labels, exhaustive)

    @Test
    fun anExhaustivePlacementTriesEverySideTogetherOnlyWhereInOrderLeavesAnOverlap() {
        val labels = listOf(160f to 110f, 200f to 40f)
        val greedy = tight(labels, exhaustive = false)
        assertTrue("${greedy.overlap}", greedy.overlap > 0f)
        val p = tight(labels, exhaustive = true)
        assertEquals(0f, p.overlap, 0f)
        assertEquals(150f, p.radius, 0f)
        // The first label left of its button, the second above its own: both aside.
        assertEquals(RingGeometry.Box(104f, 320f, 264f, 430f), p.boxes[0])
        assertEquals(RingGeometry.Box(300f, 424f, 500f, 464f), p.boxes[1])
        assertEquals(2, p.aside)
        assertTrue(p.clashing.isEmpty())
        val buttons = listOf(300f to 500f, 300f to 350f, 450f to 500f)
        for ((i, l) in p.boxes.withIndex()) for ((j, b) in buttons.withIndex()) assertEquals("$i on $j", 0f, l.overlap(b.first, b.second, 28f, if (j == i + 1) 0f else 4f), 0f)
        assertEquals(0f, p.boxes[0].overlap(p.boxes[1], 4f), 0f)
        // Where placing in order clears them, nothing changes.
        val roomy = tight(labels, right = 800f, exhaustive = false)
        assertEquals(0f, roomy.overlap, 0f)
        assertEquals(roomy.boxes, tight(labels, right = 800f, exhaustive = true).boxes)
        // Where no layout clears them, the one placed in order stays.
        val wide = listOf(160f to 110f, 400f to 40f)
        val stuck = tight(wide, exhaustive = false)
        val still = tight(wide, exhaustive = true)
        assertTrue("${still.overlap}", still.overlap > 0f)
        assertEquals(stuck.boxes, still.boxes)
        assertEquals(stuck.overlap, still.overlap, 0f)
    }

    @Test
    fun growingTakesTheSmallestRadiusThatClearsTheLabels() {
        val bounds = RingGeometry.Box(8f, 8f, 1840f, 2950f)
        val angles = RingGeometry.angles(6, left = true)
        val want = RingGeometry.radius(6, 75f, 91f)
        val tall = List(6) { 200f to 160f }
        fun arrange(grow: Float) = RingGeometry.arrange(angles, 1780f, 2880f, bounds, 56f, want, 64f, 36f, 4f, tall, grow)
        assertTrue(arrange(1f).overlap > 0f)
        val grown = arrange(3f)
        assertTrue("${grown.radius}", grown.radius > want)
        assertClear(angles, 1780f, 2880f, bounds, grown)
        // One step smaller, the labels overlap.
        val top = minOf(want * 3f, angles.minOf { RingGeometry.room(it, 1780f, 2880f, bounds, 0f, 0f, 56f, 56f) })
        assertTrue(RingGeometry.place(angles, 1780f, 2880f, bounds, 56f, grown.radius - (top - want) / 32, 36f, 4f, tall).overlap > 0f)
    }

    @Test
    fun theNearestAngleWinsAtAnyDistanceAndAroundTheCircle() {
        val right = RingGeometry.angles(5, left = false)
        assertEquals(listOf(90.0, 67.5, 45.0, 22.5, 0.0), right)
        // Straight up, near and far.
        assertEquals(0, RingGeometry.nearest(right, 0f, -10f))
        assertEquals(0, RingGeometry.nearest(right, 0.5f, -5000f))
        assertEquals(2, RingGeometry.nearest(right, 100f, -100f))
        // Straight down is nearer 0 than 90; down-left is nearer 90 across 180.
        assertEquals(4, RingGeometry.nearest(right, 1f, 100f))
        assertEquals(0, RingGeometry.nearest(right, -100f, -10f))
        val left = RingGeometry.angles(5, left = true)
        assertEquals(4, RingGeometry.nearest(left, -100f, 1f))
        assertEquals(4, RingGeometry.nearest(left, -100f, 30f))
        assertEquals(-1, RingGeometry.nearest(emptyList(), 1f, 1f))
    }

    private val all = { _: Int -> true }

    @Test
    fun aFingerThatStaysOnTheHubOrWithinTheSlopIsATap() {
        val g = RingGesture(RingGeometry.angles(5, left = true), hub = 28f, slop = 8f, x0 = 0f, y0 = 0f)
        assertFalse(g.move(20f, 0f, all))
        assertFalse(g.move(-27f, 0f, all))
        assertFalse(g.dragging)
        // Pressed at the hub's edge: a few pixels out is still within the slop.
        val edge = RingGesture(RingGeometry.angles(5, left = true), hub = 28f, slop = 8f, x0 = -26f, y0 = 0f)
        assertFalse(edge.move(-31f, 0f, all))
        assertFalse(edge.dragging)
        assertEquals(-1, edge.picked)
    }

    @Test
    fun draggingPicksByAngleAndBackOnTheHubPicksNone() {
        val g = RingGesture(RingGeometry.angles(5, left = true), hub = 28f, slop = 8f, x0 = 0f, y0 = 0f)
        assertTrue(g.move(0f, -100f, all))
        assertTrue(g.dragging)
        assertEquals(0, g.picked)
        // The same button again changes nothing.
        assertFalse(g.move(3f, -300f, all))
        assertTrue(g.move(-100f, -100f, all))
        assertEquals(2, g.picked)
        assertTrue(g.move(-5000f, 10f, all))
        assertEquals(4, g.picked)
        assertTrue(g.move(5f, 5f, all))
        assertEquals(-1, g.picked)
        // Dragging stays dragging back on the hub.
        assertTrue(g.dragging)
    }

    @Test
    fun aGrayedButtonIsNeverPicked() {
        val g = RingGesture(RingGeometry.angles(5, left = false), hub = 28f, slop = 8f, x0 = 0f, y0 = 0f)
        val notTwo = { i: Int -> i != 2 }
        assertFalse(g.move(100f, -100f, notTwo))
        assertTrue(g.dragging)
        assertEquals(-1, g.picked)
        assertTrue(g.move(0f, -100f, notTwo))
        assertEquals(0, g.picked)
        assertTrue(g.move(100f, -100f, notTwo))
        assertEquals(-1, g.picked)
    }
}
