package com.infinity.comicreader.reader

import com.infinity.comicreader.reader.TapZones.Action.MENU
import com.infinity.comicreader.reader.TapZones.Action.NEXT
import com.infinity.comicreader.reader.TapZones.Action.PREVIOUS
import org.junit.Assert.assertEquals
import org.junit.Test

class TapZonesTest {

    /** Actions for taps at the left, middle and right (or top, middle and bottom) thirds. */
    private fun along(zones: TapZones, rtl: Boolean = false, mirror: Boolean = true): List<TapZones.Action> {
        val vertical = zones == TapZones.TOP_BOTTOM || zones == TapZones.BOTTOM_TOP
        return listOf(0.1f, 0.5f, 0.9f).map { f ->
            if (vertical) zones.action(0.5f, f, rtl, mirror) else zones.action(f, 0.5f, rtl, mirror)
        }
    }

    @Test
    fun eachLayoutTurnsFromItsOuterThirds() {
        assertEquals(listOf(PREVIOUS, MENU, NEXT), along(TapZones.LEFT_RIGHT))
        assertEquals(listOf(NEXT, MENU, PREVIOUS), along(TapZones.RIGHT_LEFT))
        assertEquals(listOf(PREVIOUS, MENU, NEXT), along(TapZones.TOP_BOTTOM))
        assertEquals(listOf(NEXT, MENU, PREVIOUS), along(TapZones.BOTTOM_TOP))
    }

    @Test
    fun disabledOnlyTogglesTheMenu() {
        for (x in listOf(0f, 0.2f, 0.5f, 0.8f, 1f)) for (y in listOf(0f, 0.5f, 1f)) {
            assertEquals(MENU, TapZones.DISABLED.action(x, y, rtl = false, mirror = true))
        }
    }

    @Test
    fun zonesSplitTheAxisIntoThirds() {
        val z = TapZones.LEFT_RIGHT
        assertEquals(PREVIOUS, z.action(0.33f, 0.5f, false, true))
        assertEquals(MENU, z.action(0.34f, 0.5f, false, true))
        assertEquals(MENU, z.action(0.66f, 0.5f, false, true))
        assertEquals(NEXT, z.action(0.67f, 0.5f, false, true))
        // Only the zone axis counts.
        assertEquals(PREVIOUS, z.action(0.1f, 0.01f, false, true))
        assertEquals(MENU, TapZones.TOP_BOTTOM.action(0.01f, 0.5f, false, true))
    }

    @Test
    fun rightToLeftMirrorsLeftAndRightZones() {
        assertEquals(listOf(NEXT, MENU, PREVIOUS), along(TapZones.LEFT_RIGHT, rtl = true))
        assertEquals(listOf(PREVIOUS, MENU, NEXT), along(TapZones.RIGHT_LEFT, rtl = true))
    }

    @Test
    fun mirroringCanBeTurnedOff() {
        assertEquals(listOf(PREVIOUS, MENU, NEXT), along(TapZones.LEFT_RIGHT, rtl = true, mirror = false))
        assertEquals(listOf(NEXT, MENU, PREVIOUS), along(TapZones.RIGHT_LEFT, rtl = true, mirror = false))
    }

    @Test
    fun topAndBottomNeverMirror() {
        assertEquals(listOf(PREVIOUS, MENU, NEXT), along(TapZones.TOP_BOTTOM, rtl = true))
        assertEquals(listOf(NEXT, MENU, PREVIOUS), along(TapZones.BOTTOM_TOP, rtl = true))
    }

    @Test
    fun storedValuesRoundTripAndUnknownOnesFallBack() {
        for (z in TapZones.entries) assertEquals(z, TapZones.of(z.value))
        assertEquals(TapZones.LEFT_RIGHT, TapZones.of(null))
        assertEquals(TapZones.LEFT_RIGHT, TapZones.of("diagonal"))
    }

    @Test
    fun topToBottomTakesTopAndBottomInPlaceOfTheDefault() {
        assertEquals(TapZones.TOP_BOTTOM, TapZones.forBook(null, topToBottom = true))
        assertEquals(TapZones.TOP_BOTTOM, TapZones.forBook("bogus", topToBottom = true))
        assertEquals(TapZones.TOP_BOTTOM, TapZones.forBook("left_right", topToBottom = true))
        assertEquals(TapZones.LEFT_RIGHT, TapZones.forBook(null, topToBottom = false))
        assertEquals(TapZones.LEFT_RIGHT, TapZones.forBook("left_right", topToBottom = false))
        // Other zones stay as set.
        assertEquals(TapZones.RIGHT_LEFT, TapZones.forBook("right_left", topToBottom = true))
        assertEquals(TapZones.DISABLED, TapZones.forBook("disabled", topToBottom = true))
        assertEquals(TapZones.BOTTOM_TOP, TapZones.forBook("bottom_top", topToBottom = true))
    }
}
