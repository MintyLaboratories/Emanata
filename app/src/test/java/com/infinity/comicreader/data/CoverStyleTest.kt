package com.infinity.comicreader.data

import com.infinity.comicreader.reader.ScaleFilter
import com.infinity.comicreader.reader.Scaling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverStyleTest {

    private val auto = CoverStyle(ThumbFit.AUTO)

    @Test
    fun autoFillsTypicalCoversInATwoByThreeBox() {
        // A typical comic cover (~0.65) and a plain 2:3 one.
        assertTrue(auto.fills(650, 1000, 200, 300))
        assertTrue(auto.fills(2000, 3000, 200, 300))
        // Up to 10% wider or narrower than the box.
        assertTrue(auto.fills(733, 1000, 200, 300))
        assertTrue(auto.fills(600, 1000, 200, 300))
    }

    @Test
    fun autoFitsCoversOfAnotherShape() {
        assertFalse(auto.fills(750, 1000, 200, 300))
        assertFalse(auto.fills(590, 1000, 200, 300))
        // A wraparound, about twice as wide as a cover.
        assertFalse(auto.fills(1400, 1000, 200, 300))
    }

    @Test
    fun fillAndFitIgnoreTheShape() {
        assertTrue(CoverStyle(ThumbFit.FILL).fills(1400, 1000, 200, 300))
        assertFalse(CoverStyle(ThumbFit.FIT).fills(200, 300, 200, 300))
    }

    @Test
    fun eachStyleHasItsOwnKey() {
        val keys = ThumbFit.entries.flatMap { f ->
            CropAnchor.entries.flatMap { a -> listOf(true, false).map { CoverStyle(f, a, it).key } }
        }
        assertEquals(keys.size, keys.toSet().size)
        assertNotEquals(CoverStyle().key, CoverStyle(linear = true).key)
    }

    @Test
    fun coversKeepTheirKeyWhenOnlyEnlargingChanges() {
        assertEquals(CoverStyle().key, CoverStyle(scaling = Scaling(upRing = 40)).key)
        assertNotEquals(CoverStyle().key, CoverStyle(scaling = Scaling(downRing = 20)).key)
        assertNotEquals(CoverStyle().key, CoverStyle(scaling = Scaling(ScaleFilter.HERMITE)).key)
    }

    @Test
    fun defaultsAreAutoFromTheTop() {
        assertEquals(ThumbFit.AUTO, ThumbFit.of(null))
        assertEquals(CropAnchor.TOP, CropAnchor.of(null))
        assertEquals(CropAnchor.TOP, CropAnchor.of("bottom"))
        assertEquals(CropAnchor.SMART, CropAnchor.of("smart"))
    }

    @Test
    fun smartCoversAreKnownByTheirRulesVersion() {
        assertTrue(CoverStyle(anchor = CropAnchor.SMART).key.contains("smart${SmartCrop.RULES}"))
        assertEquals("auto|top", CoverStyle().key)
    }
}
