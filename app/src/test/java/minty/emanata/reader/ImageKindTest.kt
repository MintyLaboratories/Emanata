package minty.emanata.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageKindTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    /** An ISO-BMFF `ftyp` box with [major] and [compatible] brands. */
    private fun ftyp(major: String, vararg compatible: String): ByteArray {
        val size = 16 + 4 * compatible.size
        return bytes(0, 0, 0, size) + ascii("ftyp") + ascii(major) + bytes(0, 0, 0, 0) +
            compatible.fold(ByteArray(0)) { acc, b -> acc + ascii(b) }
    }

    @Test
    fun commonSignatures() {
        assertEquals(ImageKind.JPEG, ImageKind.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(ImageKind.PNG, ImageKind.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(ImageKind.GIF, ImageKind.sniff(ascii("GIF87a")))
        assertEquals(ImageKind.GIF, ImageKind.sniff(ascii("GIF89a")))
        assertEquals(ImageKind.BMP, ImageKind.sniff(ascii("BM") + bytes(0x36, 0, 0, 0)))
        assertEquals(ImageKind.WEBP, ImageKind.sniff(ascii("RIFF") + bytes(0x24, 0, 0, 0) + ascii("WEBPVP8 ")))
    }

    @Test
    fun jxlCodestreamAndContainer() {
        assertEquals(ImageKind.JXL, ImageKind.sniff(bytes(0xFF, 0x0A, 0xB0, 0x00)))
        assertEquals(
            ImageKind.JXL,
            ImageKind.sniff(bytes(0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87, 0x0A))
        )
        // A container signature cut short is not enough.
        assertNull(ImageKind.sniff(bytes(0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20)))
    }

    @Test
    fun isoBrands() {
        assertEquals(ImageKind.AVIF, ImageKind.sniff(ftyp("avif", "mif1", "miaf")))
        assertEquals(ImageKind.AVIF, ImageKind.sniff(ftyp("avis")))
        assertEquals(ImageKind.AVIF, ImageKind.sniff(ftyp("mif1", "miaf", "avif")))
        assertEquals(ImageKind.HEIF, ImageKind.sniff(ftyp("heic", "mif1")))
        assertEquals(ImageKind.HEIF, ImageKind.sniff(ftyp("mif1", "heic")))
        assertNull(ImageKind.sniff(ftyp("isom", "mp41")))
    }

    @Test
    fun brandsPastTheBoxAreIgnored() {
        // An 'avif' after the end of a 16-byte ftyp box belongs to the next box.
        val box = bytes(0, 0, 0, 16) + ascii("ftyp") + ascii("mif1") + bytes(0, 0, 0, 0) + ascii("avif")
        assertEquals(ImageKind.HEIF, ImageKind.sniff(box))
    }

    @Test
    fun shortOrUnknownHeaders() {
        assertNull(ImageKind.sniff(ByteArray(0)))
        assertNull(ImageKind.sniff(bytes(0xFF, 0xD8)))
        assertNull(ImageKind.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0), length = 2))
        assertNull(ImageKind.sniff(ascii("RIFF") + bytes(0x24, 0, 0, 0) + ascii("WAVE")))
        assertNull(ImageKind.sniff(ascii("PK") + bytes(0x03, 0x04)))
        assertNull(ImageKind.sniff(ascii("%PDF-1.7")))
    }
}
