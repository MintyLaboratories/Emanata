package com.infinity.comicreader.ui

import android.app.Application
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.TitleDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TitleViewTest {

    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.Theme_ComicReader)

    private val long = "The Extraordinary Adventures of a Very Long Title, Volume 12 (2014).cbz"

    private fun view(text: String, mode: TitleDisplay, reserve: Boolean = false) = TitleView(context).apply {
        this.text = text
        this.mode = mode
        this.reserve = reserve
    }

    private fun TitleView.heightAt(width: Int): Int {
        measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return measuredHeight
    }

    private val line get() = view("A", TitleDisplay.SINGLE).heightAt(300)

    @Test
    fun singleLineModesStayOneLine() {
        assertTrue(line > 0)
        for (mode in listOf(TitleDisplay.SINGLE, TitleDisplay.MIDDLE, TitleDisplay.BOUNCE, TitleDisplay.MARQUEE)) {
            assertEquals(mode.name, line, view(long, mode).heightAt(300))
        }
    }

    @Test
    fun doubleLineWrapsLongTitlesAndReservesInGrids() {
        assertEquals(2 * line, view(long, TitleDisplay.DOUBLE).heightAt(300))
        assertEquals(line, view("Short", TitleDisplay.DOUBLE).heightAt(300))
        assertEquals(2 * line, view("Short", TitleDisplay.DOUBLE, reserve = true).heightAt(300))
    }

    @Test
    fun aHiddenTitleTakesNoSpace() {
        assertEquals(0, view(long, TitleDisplay.HIDDEN).heightAt(300))
    }

    @Test
    fun onlyOverflowingTitlesMove() {
        val clock = TitleClock(0.06f)
        val moving = view(long, TitleDisplay.BOUNCE).apply { this.clock = clock }
        moving.heightAt(300)
        assertTrue(moving.travelMs > 0)
        val still = view("Short", TitleDisplay.MARQUEE).apply { this.clock = clock }
        still.heightAt(300)
        assertEquals(0L, still.travelMs)
    }
}
