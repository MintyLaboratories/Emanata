package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.files.FileWork
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each action its own color: the floating button's and every operation bubble's fill, and each running bubble's ring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FabLookTest {

    private fun colors(night: Boolean): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val config = android.content.res.Configuration(app.resources.configuration)
        config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
        return ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_ComicReader)
    }

    private fun Context.color(attr: Int): Int = obtainStyledAttributes(intArrayOf(attr)).use { it.getColor(0, 0) }

    @Test
    fun everyActionHasItsOwnFillAndRing() {
        for (night in listOf(false, true)) {
            val context = colors(night)
            val fab = listOf(SelectionActions.Fab.GRID_LIST, SelectionActions.Fab.OPEN, SelectionActions.Fab.MOVE).map { FabLook.of(it, grid = false, unfavorite = false) }
            val bubbles = FileWork.Kind.entries.filter { it != FileWork.Kind.MOVE }.map { FabLook.of(it) }
            val fills = (fab + bubbles + FabLook.PASTE).map { context.color(it.background) }
            assertEquals("fills, night $night", fills.size, fills.toSet().size)
            val rings = FileWork.Kind.entries.map { context.color(FabLook.ringOf(it)) }
            assertEquals("rings, night $night", rings.size, rings.toSet().size)
        }
    }
}
