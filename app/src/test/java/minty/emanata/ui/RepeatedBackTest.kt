package minty.emanata.ui

import minty.emanata.ui.RepeatedBack.Result.HINT
import minty.emanata.ui.RepeatedBack.Result.IGNORED
import minty.emanata.ui.RepeatedBack.Result.OPEN
import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatedBackTest {

    private val back = RepeatedBack(windowMs = 1500)

    @Test
    fun twoInTimeOpen() {
        assertEquals(HINT, back.back(1000, 2))
        assertEquals(OPEN, back.back(2400, 2))
    }

    @Test
    fun threeInTimeOpen() {
        assertEquals(HINT, back.back(0, 3))
        assertEquals(HINT, back.back(1000, 3))
        assertEquals(OPEN, back.back(2000, 3))
    }

    @Test
    fun aLateOneStartsOver() {
        assertEquals(HINT, back.back(0, 2))
        assertEquals(HINT, back.back(1501, 2))
        assertEquals(OPEN, back.back(2000, 2))
    }

    @Test
    fun theWindowIsInclusive() {
        assertEquals(HINT, back.back(0, 2))
        assertEquals(OPEN, back.back(1500, 2))
    }

    @Test
    fun openingStartsOver() {
        assertEquals(HINT, back.back(0, 2))
        assertEquals(OPEN, back.back(100, 2))
        assertEquals(HINT, back.back(200, 2))
    }

    @Test
    fun aPageChangeStartsOver() {
        assertEquals(HINT, back.back(0, 2))
        back.reset()
        assertEquals(HINT, back.back(100, 2))
        assertEquals(OPEN, back.back(200, 2))
    }

    @Test
    fun disabledIgnoresAndForgets() {
        assertEquals(HINT, back.back(0, 2))
        assertEquals(IGNORED, back.back(100, 0))
        assertEquals(HINT, back.back(200, 2))
    }

    @Test
    fun oneIsEnoughWhenOneIsRequired() {
        assertEquals(OPEN, back.back(0, 1))
    }
}
