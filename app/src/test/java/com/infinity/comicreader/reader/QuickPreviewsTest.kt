package com.infinity.comicreader.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPreviewsTest {

    @Test
    fun formatsWithoutQuickPreviewAreNotRead() {
        val quick = QuickPreviews()
        assertFalse(quick.worthReading("pages/01.png"))
        assertFalse(quick.worthReading("01.JXL"))
        assertFalse(quick.worthReading("01.tiff"))
        assertTrue(quick.worthReading("pages/01.JPG"))
        assertTrue(quick.worthReading("01.webp"))
    }

    @Test
    fun unknownExtensionsAreRead() {
        val quick = QuickPreviews()
        assertTrue(quick.worthReading("page"))
        assertTrue(quick.worthReading(null))
        assertTrue(quick.worthReading("01.img"))
    }

    @Test
    fun extensionThatKeepsMissingIsDropped() {
        val quick = QuickPreviews()
        quick.found("01.jpg", false)
        assertFalse(quick.worthReading("02.jpg"))
        assertTrue(quick.worthReading("02.webp"))
    }

    @Test
    fun oneMissAfterAHitIsTolerated() {
        val quick = QuickPreviews()
        quick.found("01.jpg", true)
        quick.found("02.jpg", false)
        assertTrue(quick.worthReading("03.jpg"))
        quick.found("03.jpg", false)
        assertFalse(quick.worthReading("04.jpg"))
    }
}
