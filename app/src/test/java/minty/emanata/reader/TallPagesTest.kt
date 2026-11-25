package minty.emanata.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TallPagesTest {

    /** A portrait screen 1848 x 2960. */
    private val screen = TallPages.portraitAspect(1848, 2960)

    @Test
    fun theScreenIsJudgedUpright() {
        assertEquals(screen, TallPages.portraitAspect(2960, 1848), 0.0)
        assertEquals(1.92, TallPages.minAspect(TallPages.DEFAULT_THRESHOLD, screen), 0.005)
    }

    @Test
    fun aPageIsTallFromTheThresholdTimesTheScreensShape() {
        assertTrue(TallPages.isTall(intArrayOf(1000, 8500), 120, screen))
        assertFalse(TallPages.isTall(intArrayOf(1000, 1900), 120, screen))
        assertTrue(TallPages.isTall(intArrayOf(1000, 1930), 120, screen))
        // The lowest threshold takes pages barely taller than the screen.
        assertTrue(TallPages.isTall(intArrayOf(1000, 1620), 101, screen))
        assertFalse(TallPages.isTall(intArrayOf(1000, 1620), 102, screen))
        // Thresholds outside 1.01..2.00 are held to them.
        assertTrue(TallPages.isTall(intArrayOf(1000, 3204), 500, screen))
        assertFalse(TallPages.isTall(null, 120, screen))
        assertFalse(TallPages.isTall(intArrayOf(0, 100), 120, screen))
    }

    @Test
    fun aBookIsTallWhenHalfItsKnownPagesAre() {
        val tall = intArrayOf(1000, 5000)
        val normal = intArrayOf(1000, 1500)
        assertEquals(true, TallPages.mostlyTall(listOf(tall, normal, null, null), 120, screen))
        assertEquals(false, TallPages.mostlyTall(listOf(tall, normal, normal), 120, screen))
        assertNull(TallPages.mostlyTall(listOf(null, null), 120, screen))
        assertEquals(setOf(0, 3), TallPages.of(listOf(tall, normal, null, tall), 120, screen))
    }

    @Test
    fun aFolderIsJudgedByTheImagesNearestTheOneOpened() {
        assertEquals(listOf(5, 6, 4, 7, 3), TallPages.nearest(10, 5, 5))
        assertEquals(listOf(0, 1, 2), TallPages.nearest(3, 0, 16))
        assertEquals(listOf(9, 8, 7), TallPages.nearest(10, 9, 3))
        assertEquals(16, TallPages.nearest(100, 0).size)
        assertTrue(TallPages.nearest(0, 0).isEmpty())
    }
}
