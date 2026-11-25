package minty.emanata.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameSkeletonTest {

    @Test
    fun numbersAreMaskedAndCaseFolded() {
        assertEquals("page #", NameSkeleton.of("Page 007.jpg"))
        assertEquals("page #", NameSkeleton.of("page 12.png"))
        assertEquals("book # - p#", NameSkeleton.of("Book 01 - p0123.webp"))
    }

    @Test
    fun whitespaceCollapsesAndExtensionGoes() {
        assertEquals("a # b", NameSkeleton.of("A  01   B.jpeg"))
        assertEquals("#", NameSkeleton.of("001.jpg"))
        assertEquals(".hidden", NameSkeleton.of(".hidden"))
    }

    @Test
    fun numberedOnlyWithDigits() {
        assertTrue(NameSkeleton.isNumbered(NameSkeleton.of("p1.jpg")))
        assertFalse(NameSkeleton.isNumbered(NameSkeleton.of("cover.jpg")))
    }

    @Test
    fun dominantNeedsAMajority() {
        val pages = (1..6).map { "p%03d.jpg".format(it) }
        assertEquals("p#", NameSkeleton.dominant(pages + "cover.jpg"))
        assertNull(NameSkeleton.dominant(listOf("a1.jpg", "b1.jpg", "c1.jpg")))
        assertNull(NameSkeleton.dominant(listOf("cover.jpg", "promotional.jpg")))
        assertNull(NameSkeleton.dominant(emptyList()))
    }

    @Test
    fun halfIsNotAMajority() {
        assertNull(NameSkeleton.dominant(listOf("p1.jpg", "p2.jpg", "x1.jpg", "y1.jpg")))
    }
}
