package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.MoreAction.BOOK_INFO
import com.infinity.comicreader.data.MoreAction.COMPACT
import com.infinity.comicreader.data.MoreAction.CONTINUE_READING
import com.infinity.comicreader.data.MoreAction.MAKE_COMPATIBLE
import com.infinity.comicreader.data.MoreAction.MARK_ALL_READ
import com.infinity.comicreader.data.MoreAction.MARK_ALL_UNREAD
import com.infinity.comicreader.data.MoreAction.MARK_READ
import com.infinity.comicreader.data.MoreAction.MARK_UNREAD
import com.infinity.comicreader.data.MoreAction.NAME_CHARSET
import com.infinity.comicreader.data.MoreAction.PACK
import com.infinity.comicreader.data.MoreAction.PACK_COMPACTED
import com.infinity.comicreader.data.MoreAction.PEEK
import com.infinity.comicreader.data.MoreAction.REMOVE_BOOKMARK
import com.infinity.comicreader.data.MoreAction.REMOVE_FROM_HISTORY
import com.infinity.comicreader.data.MoreAction.REPACK_CBZ
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
    fun defaultOffersEveryItemButPeekInTheAgreedOrder() {
        assertEquals(
            listOf(CONTINUE_READING, MARK_READ, MARK_UNREAD, MARK_ALL_READ, MARK_ALL_UNREAD, SET_STARTUP, SHOW_IN_FOLDER, BOOK_INFO, NAME_CHARSET, COMPACT, MAKE_COMPATIBLE, REPACK_CBZ, PACK, PACK_COMPACTED, REMOVE_FROM_HISTORY, REMOVE_BOOKMARK),
            MoreActions.DEFAULT.visible
        )
        // Peek is listed, off.
        assertEquals(PEEK, MoreActions.DEFAULT.offered.last())
        assertEquals(MoreActions.DEFAULT, MoreActions.decode(null))
        assertEquals(MoreActions.DEFAULT, MoreActions.decode(""))
    }

    @Test
    fun encodingRoundTripsOrderAndHiddenItems() {
        val actions = MoreActions.DEFAULT.with(PEEK, true).with(MARK_READ, false).moved(MoreActions.DEFAULT.order.indexOf(PEEK), 0)
        assertEquals(PEEK, actions.visible.first())
        assertFalse(MARK_READ in actions.visible)
        assertEquals(actions, MoreActions.decode(actions.encode()))
    }

    @Test
    fun movingAnItemClosesUpTheOthers() {
        val moved = MoreActions.DEFAULT.moved(8, MoreActions.DEFAULT.order.lastIndex)
        assertEquals(listOf(REMOVE_FROM_HISTORY, REMOVE_BOOKMARK, PEEK, NAME_CHARSET), moved.offered.takeLast(4))
        assertEquals(MoreActions.DEFAULT, MoreActions.DEFAULT.moved(0, 99))
    }

    @Test
    fun decodingDropsUnknownAndRepeatedNamesAndPlacesMissingOnesAsByDefault() {
        val actions = MoreActions.decode("peek,bogus,-peek,-mark_read")
        assertEquals(listOf(CONTINUE_READING, PEEK, MARK_READ, MARK_UNREAD), actions.order.take(4))
        assertEquals(MoreAction.entries.size, actions.order.size)
        assertEquals(MoreAction.SHOWN_BY_DEFAULT - MARK_READ + PEEK, actions.shown)
        // An order saved before an item existed takes it where it goes by default.
        val saved = MoreActions.DEFAULT.order - CONTINUE_READING - COMPACT
        val decoded = MoreActions.decode(MoreActions.DEFAULT.copy(order = saved).encode())
        assertEquals(MoreActions.DEFAULT, decoded)
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
