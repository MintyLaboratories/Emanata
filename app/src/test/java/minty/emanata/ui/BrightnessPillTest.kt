package minty.emanata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightnessPillTest {

    @Test
    fun valuesAreSignedWithATypographicMinus() {
        assertEquals("+12", BrightnessPill.valueText(12))
        assertEquals("−20", BrightnessPill.valueText(-20))
        assertEquals("0", BrightnessPill.valueText(0))
    }

    @Test
    fun theBarRunsFromMinusToPlusAHundredWithZeroInTheMiddle() {
        assertEquals(0f, BrightnessPill.fraction(-100), 0f)
        assertEquals(0.5f, BrightnessPill.fraction(0), 0f)
        assertEquals(1f, BrightnessPill.fraction(100), 0f)
        assertEquals(0.75f, BrightnessPill.fraction(50), 0f)
        assertEquals(1f, BrightnessPill.fraction(150), 0f)
    }

    @Test
    fun onlyLandingOnZeroSnaps() {
        assertTrue(BrightnessPill.snapped(3, 0))
        assertTrue(BrightnessPill.snapped(-1, 0))
        assertFalse(BrightnessPill.snapped(0, 0))
        assertFalse(BrightnessPill.snapped(0, 1))
        assertFalse(BrightnessPill.snapped(5, 4))
    }
}
