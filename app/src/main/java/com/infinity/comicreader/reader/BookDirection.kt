package com.infinity.comicreader.reader

import androidx.annotation.VisibleForTesting
import com.infinity.comicreader.ai.CoverTextDetector
import com.infinity.comicreader.data.BookFacts
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.CoverArt
import com.infinity.comicreader.data.CoverFront
import com.infinity.comicreader.data.SmartCrop
import com.infinity.comicreader.util.Formats
import java.io.File

/** [DirectionDetection] for an open book, the cover included. */
object BookDirection {
    /** True when [file] is a book whose first page may be a wraparound cover; a loose page is not its folder's cover. */
    fun hasCover(file: File): Boolean = !Formats.isImageName(file.name)

    /** What a book's contents told: [detected]; [complete] is false when its ComicInfo was left unread for its cost. */
    data class Contents(val detected: DirectionDetection.Detected?, val complete: Boolean = true)

    /**
     * What [source], opened from [file], tells without its cover. A single image stands for
     * the folder of [siblings] it is read with, whose names are its pages. A book's [facts],
     * when already read for the library, answer without reading anything; otherwise
     * ComicInfo is read only where that is cheap ([PageSource.comicInfo]). Blocking.
     */
    fun fromContents(
        file: File,
        source: PageSource,
        siblings: List<File> = emptyList(),
        facts: BookFacts? = null
    ): Contents {
        if (!hasCover(file)) return Contents(DirectionDetection.fromContents(null, file.parentFile?.name.orEmpty(), siblings.map { it.name }))
        if (facts != null) return Contents(facts.detected)
        val names = (0 until source.pageCount).mapNotNull { source.pageName(it) }
        return Contents(DirectionDetection.fromContents(source.comicInfo(cheapOnly = true), file.name, names), !source.comicInfoCostly())
    }

    /**
     * The sizes of [source]'s first [Slices.SAMPLE] pages: [known] ones, the rest from their
     * headers when [read], else null; fewer once the first are [Slices.ordinary]. Blocking
     * when [read].
     */
    fun sliceSizes(source: PageSource, known: Array<IntArray?>?, read: Boolean): List<IntArray?> {
        val sizes = ArrayList<IntArray?>()
        for (i in 0 until minOf(source.pageCount, Slices.SAMPLE)) {
            if (Slices.ordinary(sizes)) break
            sizes += known?.getOrNull(i) ?: if (read) runCatching { source.pageSize(i) }.getOrNull() else null
        }
        return sizes
    }

    /**
     * Whether [source]'s pages are slices of one strip ([Slices]): from [sizes] of its first
     * pages, any missing read now (none once the first are [Slices.ordinary]), and, when
     * their shape alone does not settle it, the seams between them, decoded as thin strips.
     * Null when it stays open: sizes that cannot be read, or once [canceled]. Blocking.
     */
    fun slices(source: PageSource, sizes: List<IntArray?>, canceled: () -> Boolean = { false }): Slices.Checked? {
        val all = ArrayList<IntArray?>(sizes.size)
        for ((i, s) in sizes.withIndex()) {
            if (Slices.ordinary(all)) break
            if (canceled()) return null
            all += s ?: runCatching { source.pageSize(i) }.getOrNull()
        }
        Slices.settled(all)?.let { return it }
        if (Slices.shape(all) != Slices.Shape.LIKELY) return null
        val seams = Slices.seamPairs(all).map { k ->
            if (canceled()) return null
            val bottom = runCatching { source.edge(k, bottom = true) }.getOrNull() ?: return@map null
            val top = runCatching { source.edge(k + 1, bottom = false) }.getOrNull() ?: return@map null
            Seams.runsOn(bottom, top)
        }
        return Slices.Checked(Slices.verdict(Slices.Shape.LIKELY, seams))
    }

    /** Books checked for slices this session, by [checkKey], the least recently used dropped first. */
    private val checked = object : LinkedHashMap<String, Slices.Checked>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Slices.Checked>?) = size > KEPT_CHECKS
    }
    private const val KEPT_CHECKS = 512

    /** What [file]'s pages were found to be this session ([rememberSlices]); null when not checked. */
    fun checkedSlices(file: File): Slices.Checked? = synchronized(checked) { checked[checkKey(file)] }

    /** Keeps what [file]'s pages were found to be, so opening it again needs no check. */
    fun rememberSlices(file: File, found: Slices.Checked) {
        synchronized(checked) { checked[checkKey(file)] = found }
    }

    /** Forgets the books checked this session; tests start each case anew. */
    @VisibleForTesting
    fun forgetChecks() = synchronized(checked) { checked.clear() }

    /** The book's ID when known ([BookIds.known]), else its path, size and time. */
    private fun checkKey(file: File) = BookIds.known(file)?.let { "id:${it.hex}" } ?: "${file.absolutePath}|${file.length()}|${file.lastModified()}"

    /** The typical shape of [source]'s first few pages after the cover ([Wraparound.innerRatio]); null without any. */
    fun innerRatio(source: PageSource): Double? = Wraparound.innerRatio(
        (1 until minOf(source.pageCount, 1 + Wraparound.INNER_SAMPLE)).mapNotNull { runCatching { source.pageSize(it) }.getOrNull() }
    )

    /**
     * The front half of a wraparound first page, judged against the book's inner pages, that
     * sets reading direction: the one its barcode and its tallest text both name
     * ([Wraparound.Fronts.agreed]), the text found by [detector] (none without it). Null when it
     * is not one, when either tells nothing, or when they disagree; then [unsure] too when it
     * could not tell this time: the page did not decode, or the detector did not run. Blocking:
     * the page is decoded, and looked at for text only once its barcode tells.
     */
    fun coverFront(source: PageSource, detector: CoverTextDetector?, unsure: () -> Unit = {}): Wraparound.Half? {
        if (source.pageCount == 0 || source.isVector) return null
        val size = source.pageSize(0) ?: return null
        if (!Wraparound.isCandidate(size[0], size[1])) return null
        val ratio = innerRatio(source)
        if (!Wraparound.isWraparound(size[0], size[1], ratio)) return null
        val page = runCatching { source.loadPage(0, Target(0, Wraparound.SCAN_HEIGHT, Fit.SHRINK)) }.getOrNull() ?: run {
            unsure()
            return null
        }
        return try {
            val barcode = CoverArt.frontOf(page) ?: return null
            val text = CoverArt.textFront(page, ratio, { src, region -> CoverArt.focusOf(src, region, detector) }, unsure)
            Wraparound.Fronts(barcode, text).agreed
        } finally {
            page.recycle()
        }
    }

    /**
     * What [source]'s cover, its first page, tells of reading direction ([coverFront]): [known]
     * when looked at before under [detector]'s cover-focus version, else found now and handed
     * to [keep], unless it could not tell this time or the detector switched to a copy of
     * another id meanwhile, which leaves it to be looked at again. Blocking.
     */
    fun coverFrontOnce(source: PageSource, known: CoverFront?, detector: CoverTextDetector, keep: (CoverFront) -> Unit): Wraparound.Half? {
        val id = detector.id
        val version = SmartCrop.version(id)
        if (known != null && known.version == version) return known.half
        var told = true
        val half = coverFront(source, detector) { told = false }
        if (told && detector.id == id) keep(CoverFront(HiddenPages.bookIndex(source, 0), half, version))
        return half
    }
}
