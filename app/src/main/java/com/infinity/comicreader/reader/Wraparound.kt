package com.infinity.comicreader.reader

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * Wraparound covers: back and front side by side, about twice as wide as a cover.
 * The front is the half without the barcode, found from the image alone, so the
 * choice never depends on the reading direction. Free of Android types.
 */
object Wraparound {
    /** Width / height of a wraparound: a cover is about 0.65, two of them about 1.3. */
    const val MIN_RATIO = 1.15
    const val MAX_RATIO = 1.8

    /** Barcodes are looked for at most this tall; larger scans are shrunk first. */
    const val SCAN_HEIGHT = 2000

    enum class Half { LEFT, RIGHT }

    private val HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(
            BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E
        ),
        DecodeHintType.TRY_HARDER to true
    )

    /** True when a [width] x [height] image is shaped like front and back covers side by side. */
    fun isWraparound(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val ratio = width.toDouble() / height
        return ratio in MIN_RATIO..MAX_RATIO
    }

    /**
     * The front half of a wraparound given as 8-bit [luminance] rows ([width] x
     * [height]): the half without an EAN/UPC barcode. Null when neither half or
     * both have one.
     */
    fun front(luminance: ByteArray, width: Int, height: Int): Half? {
        val half = width / 2
        val left = hasBarcode(luminance, width, height, 0, half)
        val right = hasBarcode(luminance, width, height, width - half, half)
        return when {
            left && !right -> Half.RIGHT
            right && !left -> Half.LEFT
            else -> null
        }
    }

    /** True when the [cropWidth]-wide band of [luminance] starting at [left] holds a retail barcode. */
    fun hasBarcode(luminance: ByteArray, width: Int, height: Int, left: Int, cropWidth: Int): Boolean {
        if (cropWidth <= 0 || height <= 0) return false
        val source = PlanarYUVLuminanceSource(luminance, width, height, left, 0, cropWidth, height, false)
        return try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), HINTS)
            true
        } catch (e: ReaderException) {
            false
        }
    }

    /** 8-bit luminance (Rec. 601) of one ARGB row, written into [out] from [offset]. */
    fun luminance(row: IntArray, width: Int, out: ByteArray, offset: Int) {
        for (x in 0 until width) {
            val p = row[x]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[offset + x] = ((r * 299 + g * 587 + b * 114 + 500) / 1000).toByte()
        }
    }
}
