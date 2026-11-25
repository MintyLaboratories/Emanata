package minty.emanata.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The reader's AI indicator: shown a moment after models start while the setting is on, hidden at once, kept in the corner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AiDotTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun dot(): Pair<AiDot, View> {
        val view = View(context).apply { visibility = View.GONE }
        FrameLayout(context).addView(view, FrameLayout.LayoutParams(8, 8))
        return AiDot(view, margin = 6, showDelayMs = 300) to view
    }

    private fun after(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun withTheSettingOffItNeverShows() {
        val (dot, view) = dot()
        dot.busy = true
        after(1_000)
        assertEquals(View.GONE, view.visibility)
    }

    @Test
    fun itShowsOnceModelsHaveWorkedAMomentAndHidesAtOnce() {
        val (dot, view) = dot()
        dot.enabled = true
        assertEquals("laid out, unseen", View.INVISIBLE, view.visibility)
        dot.busy = true
        after(200)
        assertEquals(View.INVISIBLE, view.visibility)
        after(150)
        assertEquals(View.VISIBLE, view.visibility)
        dot.busy = false
        assertEquals(View.INVISIBLE, view.visibility)
    }

    @Test
    fun workEndingWithinTheMomentNeverShowsIt() {
        val (dot, view) = dot()
        dot.enabled = true
        dot.busy = true
        after(100)
        dot.busy = false
        after(1_000)
        assertEquals(View.INVISIBLE, view.visibility)
    }

    @Test
    fun turningTheSettingOffHidesItAndOnShowsItAgainWhileBusy() {
        val (dot, view) = dot()
        dot.enabled = true
        dot.busy = true
        after(400)
        assertEquals(View.VISIBLE, view.visibility)
        dot.enabled = false
        assertEquals(View.GONE, view.visibility)
        after(400)
        assertEquals(View.GONE, view.visibility)
        dot.enabled = true
        after(400)
        assertEquals(View.VISIBLE, view.visibility)
    }

    @Test
    fun itKeepsInsideTheInsetsAndClearOfARoundedCorner() {
        assertEquals(6 to 6, AiDot.offsets(0, 0, 0, 6))
        assertEquals(10 to 54, AiDot.offsets(4, 48, 0, 6))
        // A corner rounded at 100 px: the curve is 29.3 px in from each edge at 45 degrees.
        assertEquals(30, AiDot.cornerGap(100))
        assertEquals(36 to 36, AiDot.offsets(0, 0, 100, 6))
        assertEquals(36 to 36, AiDot.offsets(20, 0, 100, 6))
        // A navigation bar taller than the curve leaves the corner behind it.
        assertEquals(6 to 126, AiDot.offsets(0, 120, 100, 6))
    }

    @Test
    fun itSitsAboveABar() {
        assertEquals(0, AiDot.lift(dotBottom = 900, barTop = null, margin = 6))
        assertEquals(-106, AiDot.lift(dotBottom = 900, barTop = 800, margin = 6))
        assertEquals("already clear of it", 0, AiDot.lift(dotBottom = 700, barTop = 800, margin = 6))
    }

    @Test
    fun itSitsBelowABar() {
        assertEquals(0, AiDot.drop(dotTop = 36, barBottom = null, margin = 6))
        assertEquals(134, AiDot.drop(dotTop = 36, barBottom = 164, margin = 6))
        assertEquals("already clear of it", 0, AiDot.drop(dotTop = 200, barBottom = 164, margin = 6))
    }

    @Test
    fun thePagesBottomRightIsTheWindowsCornerItsTurnBrings() {
        assertEquals(AiDot.Corner.BOTTOM_RIGHT, AiDot.Corner.of(0))
        // Turned clockwise (its top to the right): the page's bottom right is the window's bottom left.
        assertEquals(AiDot.Corner.BOTTOM_LEFT, AiDot.Corner.of(1))
        // Turned counter-clockwise (left-handed): the window's top right.
        assertEquals(AiDot.Corner.TOP_RIGHT, AiDot.Corner.of(-1))
    }

    @Test
    fun placingMovesItFromTheWindowsEdgesWithoutALayout() {
        val (dot, view) = dot()
        // Laid out in the bottom right corner of a parent 1000 px square.
        view.layout(992, 992, 1000, 1000)
        dot.place(AiDot.Corner.BOTTOM_RIGHT, awayX = 0, awayY = 0, radius = 100, bar = null)
        assertEquals(-36f, view.translationX)
        assertEquals(-36f, view.translationY)
        assertFalse("nothing laid out again", view.isLayoutRequested)
        // Its parent ends above a navigation bar 120 px tall: the corner is behind it.
        dot.place(AiDot.Corner.BOTTOM_RIGHT, awayX = 0, awayY = 120, radius = 100, bar = null)
        assertEquals(-6f, view.translationX)
        assertEquals(-6f, view.translationY)
        // A bar with its top at 900: lifted from 964 to 6 px above it.
        dot.place(AiDot.Corner.BOTTOM_RIGHT, awayX = 0, awayY = 0, radius = 100, bar = 900)
        assertEquals(-106f, view.translationY)
        assertFalse(view.isLayoutRequested)
    }

    @Test
    fun aPageTurnedClockwisePutsItInTheBottomLeftCorner() {
        val (dot, view) = dot()
        view.layout(992, 992, 1000, 1000)
        dot.place(AiDot.Corner.BOTTOM_LEFT, awayX = 0, awayY = 0, radius = 100, bar = null)
        assertEquals("its left 36 px from the left edge", 36f, view.left + view.translationX)
        assertEquals(-36f, view.translationY)
        // Its parent starts right of a cutout 50 px wide, past the curve there: the margin from its edge; above a bar.
        dot.place(AiDot.Corner.BOTTOM_LEFT, awayX = 50, awayY = 0, radius = 100, bar = 900)
        assertEquals(6f, view.left + view.translationX)
        assertEquals(-106f, view.translationY)
        assertFalse(view.isLayoutRequested)
    }

    @Test
    fun aPageTurnedCounterClockwisePutsItInTheTopRightCorner() {
        val (dot, view) = dot()
        view.layout(992, 992, 1000, 1000)
        dot.place(AiDot.Corner.TOP_RIGHT, awayX = 0, awayY = 0, radius = 100, bar = null)
        assertEquals(-36f, view.translationX)
        assertEquals("its top 36 px from the top edge", 36f, view.top + view.translationY)
        // Its parent starts below a status bar 80 px tall: only the margin; then below a toolbar ending at 164.
        dot.place(AiDot.Corner.TOP_RIGHT, awayX = 0, awayY = 80, radius = 100, bar = null)
        assertEquals(6f, view.top + view.translationY)
        dot.place(AiDot.Corner.TOP_RIGHT, awayX = 0, awayY = 80, radius = 100, bar = 164)
        assertEquals(170f, view.top + view.translationY)
        assertFalse(view.isLayoutRequested)
    }
}
