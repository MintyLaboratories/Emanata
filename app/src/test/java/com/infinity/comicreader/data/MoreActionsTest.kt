package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.MoreAction.BOOK_INFO
import com.infinity.comicreader.data.MoreAction.COMPACT
import com.infinity.comicreader.data.MoreAction.MAKE_COMPATIBLE
import com.infinity.comicreader.data.MoreAction.MARK_ALL_READ
import com.infinity.comicreader.data.MoreAction.MARK_ALL_UNREAD
import com.infinity.comicreader.data.MoreAction.MARK_READ
import com.infinity.comicreader.data.MoreAction.MARK_UNREAD
import com.infinity.comicreader.data.MoreAction.NAME_CHARSET
import com.infinity.comicreader.data.MoreAction.PEEK
import com.infinity.comicreader.data.MoreAction.SET_STARTUP
import com.infinity.comicreader.data.MoreAction.SHOW_IN_FOLDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MoreActionsTest {

    @Test
    fun defaultOffersAllReadyItemsButPeekInTheAgreedOrder() {
        assertEquals(
            listOf(MARK_READ, MARK_UNREAD, MARK_ALL_READ, MARK_ALL_UNREAD, SET_STARTUP, SHOW_IN_FOLDER, BOOK_INFO, NAME_CHARSET),
            MoreActions.DEFAULT.visible
        )
        // Compaction waits for its work; Peek is listed, off.
        assertFalse(COMPACT in MoreActions.DEFAULT.offered)
        assertFalse(MAKE_COMPATIBLE in MoreActions.DEFAULT.offered)
        assertEquals(PEEK, MoreActions.DEFAULT.offered.last())
        assertEquals(MoreActions.DEFAULT, MoreActions.decode(null))
        assertEquals(MoreActions.DEFAULT, MoreActions.decode(""))
    }

    @Test
    fun encodingRoundTripsOrderAndHiddenItems() {
        val actions = MoreActions.DEFAULT.with(PEEK, true).with(MARK_READ, false).moved(8, 0)
        assertEquals(PEEK, actions.visible.first())
        assertFalse(MARK_READ in actions.visible)
        assertEquals(actions, MoreActions.decode(actions.encode()))
    }

    @Test
    fun movingSkipsItemsNotReadyAndKeepsTheirPlaces() {
        val moved = MoreActions.DEFAULT.moved(7, 8)
        assertEquals(listOf(NAME_CHARSET, PEEK), moved.offered.takeLast(2).reversed())
        assertEquals(MoreActions.DEFAULT.order.indexOf(COMPACT), moved.order.indexOf(COMPACT))
        assertEquals(MoreActions.DEFAULT, MoreActions.DEFAULT.moved(0, 99))
    }

    @Test
    fun decodingDropsUnknownAndRepeatedNamesAndAppendsMissingOnesAsByDefault() {
        val actions = MoreActions.decode("peek,bogus,-peek,-mark_read")
        assertEquals(listOf(PEEK, MARK_READ), actions.order.take(2))
        assertEquals(MoreAction.entries.size, actions.order.size)
        assertEquals(MoreAction.SHOWN_BY_DEFAULT - MARK_READ + PEEK, actions.shown)
    }

    @Test
    fun theButtonOpensOneAndMovesSeveralByDefaultAndNeverOpensSeveral() {
        val context: Context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val prefs = Prefs(context)
        assertEquals(FabAction.OPEN, prefs.fabOne)
        assertEquals(FabAction.MOVE, prefs.fabMany)
        // The settings screen writes the same defaults.
        PreferenceManager.setDefaultValues(context, R.xml.settings, true)
        assertEquals(FabAction.OPEN, Prefs(context).fabOne)
        assertEquals(FabAction.MOVE, Prefs(context).fabMany)
        prefs.fabMany = FabAction.OPEN
        assertEquals(FabAction.MOVE, prefs.fabMany)
        prefs.fabOne = FabAction.NONE
        assertEquals(FabAction.NONE, prefs.fabOne)
        prefs.moreActions = MoreActions.DEFAULT.with(PEEK, true)
        assertEquals(PEEK, Prefs(context).moreActions.visible.last())
    }
}
