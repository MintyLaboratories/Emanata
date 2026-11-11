package com.infinity.comicreader.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.infinity.comicreader.ai.CoverTextDetector
import com.infinity.comicreader.data.CoverArt
import com.infinity.comicreader.data.CoverFocus
import com.infinity.comicreader.data.CoverStyle
import com.infinity.comicreader.data.CropAnchor
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.SmartCrop
import com.infinity.comicreader.data.ThumbFit
import com.infinity.comicreader.reader.BookDirection
import com.infinity.comicreader.reader.Box
import com.infinity.comicreader.reader.Decoders
import com.infinity.comicreader.reader.PageSources
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.reader.Wraparound
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Where Top, Center and Smart crop every cover under a folder, for [CoverFocusCheckService]'s
 * `mode crops`. Books are archives, PDFs and folders holding pages directly, in path order,
 * named by index only. Each cover is made as the library makes it: a wraparound (by its
 * book's inner pages) shows the front its barcode tells, else its text
 * ([CoverArt.textFront]), else all of it (Fit on blur), and says whether barcode and text
 * agree, which lets it set reading direction ([Wraparound.Fronts.agreed]); a wraparound shown
 * whole is not measured further.
 * One JSON object per book ([log]): the process's cpuset (timings hold only within one), the focus of the part shown and its time, and for each box
 * shape the axis cropped, the share kept and where each anchor starts, as shares of that axis,
 * whether each cuts the tallest text line, and whether the default fit (Auto) crops at all.
 * With [cropsDir], that book's covers by Top and by Smart (filled) as PNGs [CROP_H] tall: in a
 * 2:3 box always, in the other boxes where Smart moves. Each object also holds the detector's
 * own time (`detMs`, null when it gave no answer); with [withLines], the size it looked at (`in`) and each line it found
 * (`raw`: left, top, right, bottom, glyph as shares of that size, whether the glyph is the
 * line's thickness, score). Only the books whose indices are in [pick] when given.
 */
internal class CoverCropSurvey(
    private val context: Context,
    inner: CoverTextDetector,
    private val cropsDir: File?,
    private val log: (JSONObject) -> Unit,
    private val pick: Set<Int>? = null,
    private val withLines: Boolean = false
) {
    /** Keeps the last lines found, the size they were found on and the time it took (NaN: none since the last reset). */
    private class Recording(val inner: CoverTextDetector) : CoverTextDetector {
        override val id: String get() = inner.id
        override fun inputSize(w: Int, h: Int): IntArray = inner.inputSize(w, h)
        var last: List<SmartCrop.TextBox>? = null
        var lw = 0
        var lh = 0
        var ms = Double.NaN
        override fun lines(src: Bitmap): List<SmartCrop.TextBox>? {
            val t = SystemClock.elapsedRealtimeNanos()
            return inner.lines(src).also { last = it; lw = src.width; lh = src.height; ms = (SystemClock.elapsedRealtimeNanos() - t) / 1e6 }
        }
    }

    private val detector = Recording(inner)

    fun run(dir: File) {
        val books = books(dir)
        log(JSONObject().put("survey", books.size).put("detector", detector.id))
        // The first run loads the model: kept out of every cover's time.
        Bitmap.createBitmap(SmartCrop.STRIDE * 4, SmartCrop.STRIDE * 6, Bitmap.Config.ARGB_8888).let { runCatching { detector.lines(it) }; it.recycle() }
        for ((index, book) in books.withIndex()) {
            if (pick != null && index !in pick) continue
            val o = JSONObject().put("book", index).put("kind", kind(book)).put("cpuset", cpuset())
            try {
                cover(book, o)
            } catch (t: Throwable) {
                o.put("failed", t.javaClass.simpleName)
            }
            log(o)
        }
    }

    /** The cpuset this process runs in, as `top-app`; the system moves a process without a window shown to fewer, slower cores. */
    private fun cpuset(): String = runCatching { File("/proc/self/cpuset").readText().trim().trimStart('/') }.getOrDefault("?")

    private fun books(dir: File): List<File> = dir.walkTopDown().filter { f ->
        if (f.isDirectory) pages(f).size >= 2
        else Formats.isOpenable(f.name) && Formats.typeOf(f.name) != EntryType.IMAGE
    }.sortedWith(compareBy(NaturalOrder) { it.absolutePath }).toList()

    private fun pages(dir: File): List<File> = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && Formats.typeOf(it.name) == EntryType.IMAGE }
        ?.sortedWith(compareBy(NaturalOrder) { it.name }).orEmpty()

    private fun kind(f: File) = when {
        f.isDirectory -> "folder"
        Formats.typeOf(f.name) == EntryType.PDF -> "pdf"
        else -> "archive"
    }

    private class Loaded(val bitmap: Bitmap, val innerRatio: Double?)

    private fun load(book: File): Loaded? {
        if (book.isDirectory) {
            val first = pages(book).firstOrNull() ?: return null
            return Loaded(Decoders.decode(first, Target(capPixels = CAP)), null)
        }
        val cover = PageSources.openCover(book, "UTF-8", context.cacheDir, 0).use { it.loadPage(0, Target(capPixels = CAP)) }
        var ratio: Double? = null
        if (Wraparound.isCandidate(cover.width, cover.height)) runCatching {
            PageSources.open(book, "UTF-8", context.cacheDir, copyNested = false).use { ratio = BookDirection.innerRatio(it) }
        }
        return Loaded(cover, ratio)
    }

    private fun cover(book: File, o: JSONObject) {
        val loaded = load(book) ?: run { o.put("failed", "no cover"); return }
        val src = loaded.bitmap
        try {
            o.put("w", src.width).put("h", src.height)
            val region = if (Wraparound.isWraparound(src.width, src.height, loaded.innerRatio)) {
                val t = SystemClock.elapsedRealtimeNanos()
                val fronts = fronts(src, loaded.innerRatio)
                val half = fronts.shown
                val rule = if (fronts.barcode != null) "barcode" else if (half != null) "text" else "blur"
                o.put("wrap", JSONObject().put("rule", rule).put("half", half?.name ?: JSONObject.NULL)
                    .put("direction", fronts.agreed != null).put("ms", Stats.round1((SystemClock.elapsedRealtimeNanos() - t) / 1e6)))
                // Fit on blur: never cropped, so there is nothing more to measure.
                if (half == null) return
                columns(src, half, loaded.innerRatio)
            } else {
                Rect(0, 0, src.width, src.height)
            }
            o.put("rw", region.width()).put("rh", region.height())
            detector.last = null
            detector.ms = Double.NaN
            val t = SystemClock.elapsedRealtimeNanos()
            val focus = CoverArt.focusOf(src, region, detector)
            o.put("focusMs", Stats.round1((SystemClock.elapsedRealtimeNanos() - t) / 1e6)).put("kept", focus.keep)
            o.put("detMs", if (detector.ms.isNaN()) JSONObject.NULL else Stats.round1(detector.ms))
            if (withLines) {
                val lw = detector.lw.toDouble()
                val lh = detector.lh.toDouble()
                o.put("in", JSONArray(listOf(detector.lw, detector.lh)))
                o.put("raw", detector.last?.let { ls -> JSONArray(ls.map { b -> JSONArray(listOf(r3(b.left / lw), r3(b.top / lh), r3(b.right / lw), r3(b.bottom / lh), r3(b.glyph / lh), if (b.thickness != null) 1 else 0, r3(b.score.toDouble()))) }) } ?: JSONObject.NULL)
            }
            o.put("title", box(focus.title)).put("text", box(focus.text)).put("lines", focus.lines.size)
            for ((label, ratio) in SHAPES) {
                val s = shape(focus, region, ratio)
                o.put(label, s)
                val moved = s.has("smart") && Math.abs(s.getDouble("smart") - s.getDouble("top")) >= MOVED
                if (cropsDir != null && (ratio == SHAPES[0].second || moved)) crops(src, region, focus, ratio, cropsDir, "%03d-%s".format(o.getInt("book"), label.replace(':', 'x')))
            }
        } finally {
            src.recycle()
        }
    }

    /** What its barcode and its text each tell of wraparound [src]'s front; text is looked at even after a barcode, for agreement. */
    private fun fronts(src: Bitmap, innerRatio: Double?): Wraparound.Fronts =
        Wraparound.Fronts(CoverArt.frontOf(src), CoverArt.textFront(src, innerRatio, { s, region -> CoverArt.focusOf(s, region, detector) }))

    private fun columns(src: Bitmap, half: Wraparound.Half, innerRatio: Double?): Rect =
        Wraparound.frontColumns(src.width, src.height, half, innerRatio).let { Rect(it[0], 0, it[1], src.height) }

    /**
     * A box of width / height [ratio] filled by [region]: the axis cropped ("v", "h" or "none"),
     * the share of it kept, where Top, Center and Smart start (shares of that axis), whether each
     * cuts the tallest text line, and whether Auto fit crops.
     */
    private fun shape(focus: CoverFocus, region: Rect, ratio: Double): JSONObject {
        val w = region.width().toDouble()
        val h = region.height().toDouble()
        val auto = CoverStyle(fit = ThumbFit.AUTO).fills(region.width(), region.height(), (ratio * 1000).toInt(), 1000)
        val o = JSONObject().put("auto", auto)
        val vertical = w / h < ratio
        val span = if (vertical) w / ratio / h else h * ratio / w
        if (span >= 1 - 1e-9) return o.put("axis", "none")
        val length = if (vertical) h else w
        // Top anchors only down; across, every anchor's plain crop is centered.
        val center = (1 - span) / 2
        val top = if (vertical) 0.0 else center
        val smart = SmartCrop.offset(focus, length, span * length, vertical) / length
        o.put("axis", if (vertical) "v" else "h").put("span", r3(span))
            .put("top", r3(top)).put("center", r3(center)).put("smart", r3(smart))
        focus.title?.let { b ->
            val lo = if (vertical) b.top else b.left
            val hi = if (vertical) b.bottom else b.right
            fun cuts(at: Double) = lo < at - EPS || hi > at + span + EPS
            o.put("cut", JSONObject().put("top", cuts(top)).put("center", cuts(center)).put("smart", cuts(smart)))
        }
        return o
    }

    private fun crops(src: Bitmap, region: Rect, focus: CoverFocus, ratio: Double, dir: File, name: String) {
        dir.mkdirs()
        for (anchor in listOf(CropAnchor.TOP, CropAnchor.SMART)) {
            val out = CoverArt.compose(src, Math.round(ratio * CROP_H).toInt(), CROP_H, CoverStyle(fit = ThumbFit.FILL, anchor = anchor), region) { _, _ -> focus }
            File(dir, "$name-${anchor.key}.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out.recycle()
        }
    }

    private fun box(b: Box?): Any = b?.let { JSONArray(listOf(r3(it.left), r3(it.top), r3(it.right), r3(it.bottom))) } ?: JSONObject.NULL

    private fun r3(v: Double) = Math.round(v * 1000) / 1000.0

    private companion object {
        /** Covers decode as the library's do, at most this many pixels. */
        const val CAP = 16_000_000L

        /** The app's cover shapes and a square cell, as width / height. */
        val SHAPES = listOf("2:3" to 2.0 / 3, "3:4" to 0.75, "1:1" to 1.0)

        /** A line within this share of a crop's edge counts as kept. */
        const val EPS = 0.002

        /** Smart moved from Top when their starts differ by at least this share of the axis. */
        const val MOVED = 0.0015

        const val CROP_H = 600
    }
}
