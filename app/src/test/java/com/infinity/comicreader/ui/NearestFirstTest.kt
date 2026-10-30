package com.infinity.comicreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearestFirstTest {

    @Test
    fun closestToTheMiddleFirst() {
        assertEquals(7, NearestFirst.pick(listOf(0, 3, 7, 20), center = 8))
        assertEquals(20, NearestFirst.pick(listOf(0, 20), center = 19))
    }

    @Test
    fun tiesGoToTheEarlier() = assertEquals(4, NearestFirst.pick(listOf(6, 4), center = 5))

    @Test
    fun nothingPending() = assertNull(NearestFirst.pick(emptyList(), center = 3))

    @Test
    fun orderedFromTheMiddleOutThoseNotShownLast() {
        val positions = mapOf("a" to 0, "b" to 4, "c" to 6, "d" to 9)
        assertEquals(listOf("b", "c", "d", "a", "x"), NearestFirst.order(listOf("x", "a", "d", "c", "b"), center = 5) { positions[it] })
    }

    @Test
    fun middleOfTheView() {
        assertEquals(5, NearestFirst.middle(2, 8))
        assertEquals(0, NearestFirst.middle(-1, -1))
        assertEquals(0, NearestFirst.middle(4, 2))
    }
}
