package minty.emanata.reader

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import kotlin.math.roundToInt

/**
 * Wraparound covers: back and front side by side, about twice as wide as one of
 * the book's inner pages. The front is the half without the barcode, else the half
 * with the taller title ([Fronts]); reading direction never picks it. Free of
 * Android types.
 */
object Wraparound {
    /** Width / height of a wraparound when the book's inner pages are unknown: a cover is about 0.65, two of them about 1.3. */
    const val MIN_RATIO = 1.15
    const val MAX_RATIO = 1.8

    /** At an inner page's height, a wraparound is twice its width give or take this share. */
    const val PAGE_TOLERANCE = 0.15

    /** Narrower covers are never wraparounds, even of very tall pages, so their books' inner pages need no look. */
    const val CANDIDATE_RATIO = 0.85

    /** Inner pages after the cover whose shapes make up the book's typical page. */
    const val INNER_SAMPLE = 4

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
     * True when a [width] x [height] cover is two of its book's pages side by side:
     * at the height of a typical inner page of shape [innerRatio] (width / height), it
     * is twice as wide within [PAGE_TOLERANCE]. Without inner pages (null), shape alone
     * decides ([isWraparound]).
     */
    fun isWraparound(width: Int, height: Int, innerRatio: Double?): Boolean {
        if (innerRatio == null || innerRatio <= 0.0) return isWraparound(width, height)
        if (width <= 0 || height <= 0) return false
        val pages = width.toDouble() / height / innerRatio
        return pages in 2 * (1 - PAGE_TOLERANCE)..2 * (1 + PAGE_TOLERANCE)
    }

    /** True when a [width] x [height] cover is wide enough that its book's inner pages are worth a look. */
    fun isCandidate(width: Int, height: Int): Boolean = width > 0 && height > 0 && width.toDouble() / height >= CANDIDATE_RATIO

    /** The typical shape (width / height) of inner pages of [sizes] (`[width, height]` each): their median; null without any. */
    fun innerRatio(sizes: List<IntArray>): Double? {
        val ratios = sizes.filter { it.size >= 2 && it[0] > 0 && it[1] > 0 }.map { it[0].toDouble() / it[1] }.sorted()
        if (ratios.isEmpty()) return null
        val mid = ratios.size / 2
        return if (ratios.size % 2 == 1) ratios[mid] else (ratios[mid - 1] + ratios[mid]) / 2
    }

    /**
     * What names a wraparound's front: the half without the barcode ([barcode]) and the half
     * with the taller title ([text]), each null when it tells nothing.
     */
    data class Fronts(val barcode: Half?, val text: Half?) {
        /** The half a cover shows: the barcode's, else the text's; null when neither tells, so it shows whole. */
        val shown: Half? get() = barcode ?: text

        /** The half that sets reading direction: only one both name. */
        val agreed: Half? get() = barcode?.takeIf { it == text }
    }

    /**
     * `[left, right]` columns of [half] of a [width] x [height] wraparound: from the
     * outer edge, as wide as one inner page of [innerRatio] at this height, so a spine
     * between the halves is left out; never more than half the width.
     */
    fun frontColumns(width: Int, height: Int, half: Half, innerRatio: Double?): IntArray {
        val halfWidth = width / 2
        val page = if (innerRatio == null || innerRatio <= 0.0) halfWidth else (innerRatio * height).roundToInt()
        val w = page.coerceIn(1, halfWidth.coerceAtLeast(1))
        return if (half == Half.LEFT) intArrayOf(0, w) else intArrayOf(width - w, width)
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
