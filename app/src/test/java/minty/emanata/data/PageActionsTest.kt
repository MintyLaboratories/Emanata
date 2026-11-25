package minty.emanata.data

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import minty.emanata.data.PageAction.HIDE
import minty.emanata.data.PageAction.PAGE_INFO
import minty.emanata.data.PageAction.SET_COVER
import minty.emanata.data.PageAction.SPREAD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageActionsTest {

    @Test
    fun defaultOffersEveryRowInTheAgreedOrder() {
        assertEquals(listOf(HIDE, SET_COVER, PAGE_INFO, SPREAD), PageActions.DEFAULT.visible)
        assertEquals(PageActions.DEFAULT, PageActions.decode(null))
        assertEquals(PageActions.DEFAULT, PageActions.decode(" "))
    }

    @Test
    fun encodingRoundTripsOrderAndHiddenRows() {
        val actions = PageActions.DEFAULT.with(SET_COVER, false).moved(3, 0)
        assertEquals(listOf(SPREAD, HIDE, PAGE_INFO), actions.visible)
        assertFalse(SET_COVER in actions.visible)
        assertEquals("spread,hide,-set_cover,page_info", actions.encode())
        assertEquals(actions, PageActions.decode(actions.encode()))
        assertEquals(actions, actions.moved(0, 9))
    }

    @Test
    fun decodingDropsUnknownAndRepeatedNamesAndAddsMissingOnesLastShown() {
        val actions = PageActions.decode("page_info,bogus,-page_info,-hide")
        assertEquals(listOf(PAGE_INFO, HIDE, SET_COVER, SPREAD), actions.order)
        assertEquals(listOf(PAGE_INFO, SET_COVER, SPREAD), actions.visible)
    }

    @Test
    fun prefsKeepTheRows() {
        val context: Context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        assertEquals(PageActions.DEFAULT, Prefs(context).pageActions)
        Prefs(context).pageActions = PageActions.DEFAULT.with(HIDE, false)
        assertEquals(listOf(SET_COVER, PAGE_INFO, SPREAD), Prefs(context).pageActions.visible)
    }
}
