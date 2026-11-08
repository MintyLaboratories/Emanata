package com.infinity.comicreader.files

import com.infinity.comicreader.compact.PageProbe
import com.infinity.comicreader.reader.ImageKind
import com.infinity.comicreader.util.Formats
import kotlin.math.roundToInt

/** A Convert action, named by [key] in the rate record, doing [step] to each page; none copies pages as they are. */
data class ConvertOp(val key: String, val step: Step?) {
    companion object {
        val PACK = ConvertOp("pack", null)
        val PACK_COMPACTED = ConvertOp("pack-compacted", Step.COMPACT)
        val COMPACT = ConvertOp("compact", Step.COMPACT)
        val COMPATIBLE = ConvertOp("compatible", Step.COMPATIBLE)
        val REPACK = ConvertOp("repack", null)

        /** Upscaling with [model]. */
        fun upscale(model: String) = Step.upscale(model).let { ConvertOp(it.key, it) }
    }
}

/**
 * A page as known before the work: its [format], by its name, its [bytes], and [lossy] for a
 * WebP its first bytes say Compact leaves as it is ([WorkRates.secondsPerPage]).
 */
data class PagePlan(val format: ImageKind?, val bytes: Long, val lossy: Boolean = false)

/**
 * A book as known before the work, without decoding anything: its [bytes], where it is
 * ([WorkRates.DEVICE] or a share), how its files are held, its [pages] when listed (null when
 * not: the pages of the books listed stand in), and [done] when its mark says it had this done
 * already (it is skipped); null when its mark was not read (the share of books read done
 * stands in).
 */
data class BookPlan(
    val bytes: Long,
    val location: String = WorkRates.DEVICE,
    val container: Container = Container.ZIP,
    val pages: List<PagePlan>? = null,
    val done: Boolean? = false
)

/**
 * How long Convert work takes, from this device's [rates]: each book reads, converts its
 * pages (reading overlaps converting, so the slower of the two counts), writes its new file
 * and reads it back, plus its action's time per book. A book not listed is taken to hold
 * pages as those listed do, by bytes; with none listed, medium JPEGs. A book whose mark was
 * not read is taken to be done as often as those read were.
 */
class ConvertEstimate(private val rates: WorkRates) {
    /** Seconds each of [books] takes for [op], in order. */
    fun forecast(op: ConvertOp, books: List<BookPlan>): List<Double> {
        val listed = books.filter { it.pages != null && it.done == false }
        val pageBytes = listed.sumOf { b -> b.pages!!.sumOf { it.bytes.coerceAtLeast(0) } }
        val bookBytes = listed.sumOf { it.bytes.coerceAtLeast(0) }
        val step = op.step
        // Seconds converting per byte of book, and the share of a book's bytes that is pages.
        val perByte = when {
            step == null -> 0.0
            pageBytes > 0 -> listed.sumOf { convertSeconds(step, it.pages!!) } / pageBytes
            else -> rates.secondsPerPage(step, ImageKind.JPEG, SizeClass.MEDIUM) / TYPICAL_PAGE_BYTES
        }
        val pageShare = if (bookBytes > 0) (pageBytes.toDouble() / bookBytes).coerceIn(0.0, 1.0) else 1.0
        val read = books.filter { it.done != null }
        val doneShare = if (read.isEmpty()) 0.0 else read.count { it.done == true }.toDouble() / read.size
        return books.map { book ->
            when (book.done) {
                true -> SKIP_SECONDS
                false -> seconds(op, book, perByte, pageShare)
                null -> doneShare * SKIP_SECONDS + (1 - doneShare) * seconds(op, book, perByte, pageShare)
            }
        }
    }

    /** Seconds [book] takes for [op] when not done; [perByte] and [pageShare] stand in for pages not listed. */
    private fun seconds(op: ConvertOp, book: BookPlan, perByte: Double, pageShare: Double): Double {
        val bytes = book.bytes.coerceAtLeast(0).toDouble()
        val read = bytes / rates.readRate(book.location, book.container)
        val step = op.step
        val convert = when {
            step == null -> 0.0
            book.pages != null -> convertSeconds(step, book.pages)
            else -> bytes * pageShare * perByte
        }
        val write = bytes / rates.writeRate(book.location)
        return maxOf(read, convert) + write + rates.bookSeconds(op.key)
    }

    private fun convertSeconds(step: Step, pages: List<PagePlan>): Double =
        pages.sumOf { rates.secondsPerPage(step, it.format, SizeClass.of(it.bytes), it.lossy) }

    companion object {
        /** A book whose mark says it had this done: its mark checked, nothing more. */
        const val SKIP_SECONDS = 0.05

        /** The bytes of a page when no book was listed. */
        const val TYPICAL_PAGE_BYTES = 1.5 * 1024 * 1024

        /** Seconds of planned work the job's own speed must cover before it counts as much as the plan ([remaining]). */
        const val PRIOR_SECONDS = 30.0

        /** How far the job's own speed may stray from the plan: a stalled start or a skipped book does not swing the estimate wildly. */
        private const val MIN_RATIO = 0.1
        private const val MAX_RATIO = 10.0

        /**
         * Seconds left of a job planned as [plan] (seconds per item), now at item [index] with
         * [part] (0 to 1) of it done, [elapsed] seconds after it started: the plan's seconds left,
         * scaled by how fast this job went so far against its plan, that measure counting more
         * the more planned work it covers ([PRIOR_SECONDS]).
         */
        fun remaining(plan: List<Double>, index: Int, part: Double, elapsed: Double): Double {
            if (plan.isEmpty()) return 0.0
            val i = index.coerceIn(0, plan.size)
            val done = plan.subList(0, i).sum() + (plan.getOrNull(i) ?: 0.0) * part.coerceIn(0.0, 1.0)
            val left = (plan.sum() - done).coerceAtLeast(0.0)
            if (done <= 0 || elapsed <= 0) return left
            val ratio = (elapsed / done).coerceIn(MIN_RATIO, MAX_RATIO)
            val weight = done / (done + PRIOR_SECONDS)
            return left * (weight * ratio + (1 - weight))
        }

        /** The bytes [lossyWebp] needs of a WebP. */
        const val WEBP_HEAD = 64

        /** How many WebP pages of a book [lossyWebp] is asked about. */
        const val WEBP_SAMPLES = 3

        /**
         * Whether the WebP pages among [names] are ones Compact leaves as they are (lossy,
         * [com.infinity.comicreader.compact.PageProbe.webpLossless]), by the first bytes ([head])
         * of the first, middle and last of them: as most of those read say; false when none was read.
         */
        fun lossyWebp(names: List<String>, head: (Int) -> ByteArray?): Boolean {
            val webp = names.indices.filter { formatOf(names[it]) == ImageKind.WEBP }
            if (webp.isEmpty()) return false
            val picks = listOf(webp.first(), webp[webp.size / 2], webp.last()).distinct().take(WEBP_SAMPLES)
            val said = picks.mapNotNull { i -> runCatching { head(i) }.getOrNull()?.let { !PageProbe.webpLossless(it) } }
            return said.isNotEmpty() && said.count { it } * 2 >= said.size
        }

        /** The format a page named [name] holds, by its extension; null for one of none known. */
        fun formatOf(name: String): ImageKind? = when (Formats.ext(name)) {
            "jpg", "jpeg" -> ImageKind.JPEG
            "png" -> ImageKind.PNG
            "gif" -> ImageKind.GIF
            "bmp" -> ImageKind.BMP
            "webp" -> ImageKind.WEBP
            "avif" -> ImageKind.AVIF
            "heic", "heif" -> ImageKind.HEIF
            "jxl" -> ImageKind.JXL
            "tif", "tiff" -> ImageKind.TIFF
            else -> null
        }
    }
}

/**
 * A time span as shown, rounded: under a minute ([minutes] 0), whole minutes below
 * [HOURS_FROM] minutes, else [hours] and [minutes] to the nearest ten.
 */
data class Span(val hours: Int, val minutes: Int) {
    val underMinute: Boolean get() = hours == 0 && minutes == 0

    companion object {
        const val HOURS_FROM = 90

        fun of(seconds: Double): Span {
            if (!(seconds >= 60)) return Span(0, 0)
            val minutes = (seconds / 60).roundToInt()
            if (minutes < HOURS_FROM) return Span(0, minutes)
            val tens = (minutes / 10.0).roundToInt() * 10
            return Span(tens / 60, tens % 60)
        }
    }
}
