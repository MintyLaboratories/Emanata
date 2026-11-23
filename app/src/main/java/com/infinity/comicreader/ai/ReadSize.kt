package com.infinity.comicreader.ai

import com.infinity.comicreader.reader.Plan
import com.infinity.comicreader.reader.Spreads
import kotlin.math.roundToInt

/**
 * The printed format a book's pages were made for ([of]), with the width a page of it is
 * printed at ([widthCm]); [ReadSize] reads pages at that size where the screen is too small.
 */
enum class PageClass(val key: Char, val widthCm: Double) {
    /** Manga: B6, 12.8 cm wide. */
    MANGA('m', 12.8),

    /** Made for screens: printed at [ReadSize.WEB_DPI], its own pixels deciding its width. */
    WEB('w', 0.0),

    /** Franco-Belgian album: 21.5 cm wide. */
    FRANCO_BELGIAN('f', 21.5),

    /** US comic book: 16.8 cm wide. */
    US('u', 16.8);

    companion object {
        /** Median page aspects (width / height) below this read as web. */
        const val WEB_BELOW = 0.60

        /** Median page aspects above this read as web. */
        const val WEB_ABOVE = 0.78

        /** The narrowest median aspect of a Franco-Belgian album (0.741). */
        const val ALBUM_FROM = 0.73

        /** The widest median aspect of a Franco-Belgian album. */
        const val ALBUM_TO = 0.755

        /**
         * A book's class: manga when read right to left, web when read top to bottom or its
         * median page [aspect] is outside [WEB_BELOW]..[WEB_ABOVE], Franco-Belgian only within
         * [ALBUM_FROM]..[ALBUM_TO], US for the rest and while [aspect] is unknown (null).
         */
        fun of(rightToLeft: Boolean, topToBottom: Boolean, aspect: Double?): PageClass = when {
            rightToLeft -> MANGA
            topToBottom -> WEB
            aspect == null -> US
            aspect < WEB_BELOW || aspect > WEB_ABOVE -> WEB
            aspect in ALBUM_FROM..ALBUM_TO -> FRANCO_BELGIAN
            else -> US
        }

        /**
         * The median aspect of [analysis]'s pages of known size, covers (and the first page),
         * spreads and wide pages left out; of all but the covers when nothing else is left
         * (a book of wide pages); null when no size is known.
         */
        fun medianAspect(analysis: Spreads.Analysis): Double? {
            val pages = analysis.pages.withIndex().filter { (i, p) -> p.aspect > 0 && !p.cover && (i > 0 || analysis.size == 1) }
            val story = pages.filter { (_, p) -> p.aspect <= 1.0 && p.kind !in SPREADS }
            return median((story.ifEmpty { pages }).map { it.value.aspect })
        }

        private val SPREADS = setOf(Spreads.Kind.SPREAD, Spreads.Kind.HALF_SPREAD, Spreads.Kind.SPREAD_WIDE, Spreads.Kind.WIDE)

        private fun median(xs: List<Double>): Double? {
            if (xs.isEmpty()) return null
            val s = xs.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
        }
    }
}

/**
 * A book's pages as [ReadSize] judges them: their [pageClass] and median [aspect] (width /
 * height, to 0.01; null when unknown), which sets the printed height of a page of a class
 * printed by width.
 */
data class BookShape(val pageClass: PageClass, val aspect: Double? = null) {
    companion object {
        /** A book nothing is known of: a US comic. */
        val UNKNOWN = BookShape(PageClass.US)

        /** The shape of a book read [rightToLeft] or [topToBottom] whose pages [analysis] found; null while not analyzed. */
        fun of(rightToLeft: Boolean, topToBottom: Boolean, analysis: Spreads.Analysis?): BookShape {
            val aspect = analysis?.let { PageClass.medianAspect(it) }
            return BookShape(PageClass.of(rightToLeft, topToBottom, aspect), aspect?.let { (it * 100).roundToInt() / 100.0 })
        }
    }
}

/**
 * The size a page is read at, at which the AI thresholds judge it ([factor]): as it fits the
 * screen, or at its printed size when that is more than [ZOOM_FREE] times as large (the reader
 * zooms in to read it). The printed size comes from the book's [shape] and the screen's
 * physical pixels per inch, [ppi]; DPI stored in image files is never read.
 */
data class ReadSize(val shape: BookShape, val ppi: Double) {

    /**
     * Screen pixels per source pixel at print size for a [srcW] x [srcH] page: [WEB_DPI] for
     * web pages; else the class's printed width, or the printed height of a page of the book's
     * median aspect, whichever makes the page larger, so a spread prints at the height of the
     * book's pages and a tall strip at their width.
     */
    fun printScale(srcW: Double, srcH: Double): Double {
        val c = shape.pageClass
        if (c == PageClass.WEB) return ppi / WEB_DPI
        val width = c.widthCm / CM_PER_INCH * ppi
        val byHeight = shape.aspect?.takeIf { it > 0 }?.let { width / it / srcH } ?: 0.0
        return maxOf(width / srcW, byHeight)
    }

    /**
     * The zoom a [srcW] x [srcH] page shown at [fit] screen pixels per source pixel needs to
     * reach its printed size ([printScale]); below 1 when it already shows larger.
     */
    fun zoom(fit: Double, srcW: Double, srcH: Double): Double = printScale(srcW, srcH) / fit

    /**
     * The factor on a page's fit size at which the thresholds judge it: 1 while the zoom to
     * its printed size ([zoom]) is at most [ZOOM_FREE] (the reader is taken not to zoom), else
     * that zoom: the page is judged at its printed size.
     */
    fun factor(fit: Double, srcW: Double, srcH: Double): Double {
        if (fit <= 0 || srcW <= 0 || srcH <= 0) return 1.0
        val z = zoom(fit, srcW, srcH)
        return if (z <= ZOOM_FREE) 1.0 else z
    }

    /** What decides [factor], as part of a processed page's key: the class, the median aspect and the ppi. */
    val key: String
        get() = buildString {
            append(shape.pageClass.key)
            if (shape.pageClass != PageClass.WEB) shape.aspect?.let { append((it * 100).roundToInt()) }
            append('@').append(ppi.roundToInt())
        }

    companion object {
        /** The zoom up to which a page is taken to be read as it fits. */
        const val ZOOM_FREE = 1.15

        /** The pixels per inch web comics are drawn for. */
        const val WEB_DPI = 96.0

        private const val CM_PER_INCH = 2.54

        /**
         * The screen's physical pixels per inch from its display metrics: the mean of [xdpi]
         * and [ydpi], or [densityDpi] where they are missing or more than twice off it (some
         * devices report placeholders).
         */
        fun ppiOf(xdpi: Float, ydpi: Float, densityDpi: Int): Double {
            val mean = (xdpi + ydpi) / 2.0
            if (densityDpi <= 0) return mean.coerceAtLeast(0.0)
            return if (xdpi > 0 && ydpi > 0 && mean in densityDpi / 2.0..densityDpi * 2.0) mean else densityDpi.toDouble()
        }
    }
}

/**
 * A page's size as the thresholds judge it: shown as [shown], in the pixels of its [inputW] x
 * [inputH] chain input, and read at [factor] times that ([ReadSize.factor]) as [read] says
 * (null: as it shows).
 */
internal class Judged(val inputW: Int, val inputH: Int, val shown: Plan, val factor: Double, val read: ReadSize?) {
    /** The read size as a percentage of the input's side ([AiThresholds.shownPercent]). */
    val percent: Double get() = AiThresholds.shownPercent(shown) * factor

    /** True when the page is judged at its printed size. */
    val printed: Boolean get() = factor > 1.0

    /** The [models] of a chain that run at this size under [thresholds] ([AiPages.steps]). */
    fun steps(models: List<ChainModel>, thresholds: AiThresholds): List<ChainStep> =
        AiPages.steps(models, inputW, inputH, shown, thresholds, factor)

    companion object {
        /** A page whose [inputW] x [inputH] input was made from [srcW] x [srcH] source pixels, shown as [shown]. */
        fun of(inputW: Int, inputH: Int, shown: Plan, srcW: Double, srcH: Double, read: ReadSize?): Judged {
            val factor = read?.let {
                // Screen pixels per source pixel as shown: the plan's span is in input pixels.
                val fit = maxOf(shown.width / (shown.spanW * srcW / inputW), shown.height / (shown.spanH * srcH / inputH))
                it.factor(fit, srcW, srcH)
            } ?: 1.0
            return Judged(inputW, inputH, shown, factor, read)
        }
    }
}

/**
 * What AI last did with a page, for its Page info: it was read at [percent] of its input's side
 * (null: unknown), at its printed size as a [pageClass] page when [printed]; [small] names the
 * models of its chain not run at that size; [why] it kept its plain rendering (null: processed).
 */
data class AiNote(val percent: Double?, val printed: Boolean, val pageClass: PageClass?, val small: List<String>, val why: Fallback?)
