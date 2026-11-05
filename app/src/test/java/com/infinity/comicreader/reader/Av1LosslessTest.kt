package com.infinity.comicreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Av1Lossless] through [AvifPreview.codedLossless] and Page info, on small AVIF files of a
 * synthetic noisy gradient: avifenc's reduced still-picture headers (lossless of 8 and 10
 * bits, with alpha, in gray, as a 2 x 2 grid; lossy, also 4:4:4 with the identity matrix,
 * with alpha, as a grid, and lossless color over lossy alpha), and full headers from
 * ffmpeg's libaom (lossless, lossy identity) and SVT-AV1 (lossy 4:2:0).
 */
class Av1LosslessTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("avif/$name")!!.use { it.readBytes() }

    private val lossless = listOf(
        "lossless.avif", "lossless-10bit.avif", "lossless-alpha.avif", "lossless-gray.avif", "lossless-grid.avif",
        "full-header-lossless.avif"
    )

    private val lossy = listOf(
        "lossy.avif", "lossy-identity.avif", "lossy-identity-alpha.avif", "lossy-identity-grid.avif",
        "lossless-color-lossy-alpha.avif", "full-header-lossy-identity.avif", "full-header-lossy.avif", "single.avif"
    )

    @Test
    fun framesCodedLosslessAreToldFromLossyOnes() {
        for (name in lossless) assertEquals(name, true, AvifPreview.codedLossless(fixture(name)))
        for (name in lossy) assertEquals(name, false, AvifPreview.codedLossless(fixture(name)))
    }

    @Test
    fun pageInfoCallsLossyAnIdentityMatrixCodedLossy() {
        for (name in lossless) assertEquals(name, true, PageFormat.of(fixture(name)).lossless)
        for (name in lossy) assertEquals(name, false, PageFormat.of(fixture(name)).lossless)
    }

    @Test
    fun aThumbnailIsLeftOut() {
        assertEquals(false, AvifPreview.codedLossless(fixture("with-thumbnail.avif")))
    }

    @Test
    fun dataThatDoesNotTellGivesNull() {
        val whole = fixture("lossless.avif")
        // Cut before its image data, or inside its frame header.
        assertNull(AvifPreview.codedLossless(whole.copyOf(64)))
        assertNull(AvifPreview.codedLossless(whole.copyOf(whole.size - 2500)))
        assertNull(Av1Lossless.of(ByteArray(0), 0, 0))
        // A frame OBU before any sequence header.
        assertNull(Av1Lossless.of(byteArrayOf(0x32, 0x01, 0x10), 0, 3))
        // Sizes past the end.
        assertNull(Av1Lossless.of(byteArrayOf(0x0A, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F), 0, 5))
    }
}
