package minty.emanata.data

import minty.emanata.data.ToolbarButton.FAVORITE
import minty.emanata.data.ToolbarButton.FILTER
import minty.emanata.data.ToolbarButton.SEARCH
import minty.emanata.data.ToolbarButton.SORT
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarButtonsTest {

    @Test
    fun defaultShowsSortFilterFavoriteSearch() {
        assertEquals(listOf(SORT, FILTER, FAVORITE, SEARCH), ToolbarButtons.DEFAULT.visible)
        assertEquals(ToolbarButtons.DEFAULT, ToolbarButtons.decode(null))
        assertEquals(ToolbarButtons.DEFAULT, ToolbarButtons.decode(""))
    }

    @Test
    fun encodingRoundTripsOrderAndHiddenButtons() {
        val buttons = ToolbarButtons(listOf(SEARCH, SORT, FAVORITE, FILTER), setOf(SEARCH, FILTER))
        assertEquals("search,-sort,-favorite,filter", buttons.encode())
        assertEquals(buttons, ToolbarButtons.decode(buttons.encode()))
        assertEquals(listOf(SEARCH, FILTER), buttons.visible)
    }

    @Test
    fun decodingDropsUnknownAndRepeatedNamesAndAppendsMissingOnes() {
        val buttons = ToolbarButtons.decode("filter,bogus,-filter,-sort")
        assertEquals(listOf(FILTER, SORT, FAVORITE, SEARCH), buttons.order)
        assertEquals(setOf(FILTER, FAVORITE, SEARCH), buttons.shown)
    }

    @Test
    fun movingAndTogglingKeepTheRest() {
        val moved = ToolbarButtons.DEFAULT.moved(3, 0)
        assertEquals(listOf(SEARCH, SORT, FILTER, FAVORITE), moved.order)
        assertEquals(listOf(SORT, FAVORITE, FILTER, SEARCH), ToolbarButtons.DEFAULT.moved(1, 2).order)
        assertEquals(ToolbarButtons.DEFAULT, ToolbarButtons.DEFAULT.moved(0, 9))
        val hidden = moved.with(FILTER, false)
        assertEquals(listOf(SEARCH, SORT, FAVORITE), hidden.visible)
        assertEquals(moved, hidden.with(FILTER, true))
    }
}
