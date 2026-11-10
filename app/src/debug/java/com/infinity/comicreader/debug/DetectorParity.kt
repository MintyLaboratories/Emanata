package com.infinity.comicreader.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import com.infinity.comicreader.ai.FileSet
import com.infinity.comicreader.ai.LibNcnn
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelDownloader
import com.infinity.comicreader.ai.ModelStore
import com.infinity.comicreader.ai.ScoreMap
import com.infinity.comicreader.ai.TextDetector
import com.infinity.comicreader.data.SmartCrop
import com.infinity.comicreader.reader.Box
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Checks that the downloaded text detector ([ModelCatalog.TEXT_DETECTOR]) finds what the copy
 * in the app's assets finds: both run as the app runs them on synthetic covers ([PAGES]), and
 * their score maps, lines and Smart crop focuses are compared ([compare]). Downloads the model
 * first ([download]) when it is not in place. Logs one JSON object per page, then, whatever
 * happened, its [verdict]: [OK], [FAIL] (the copies differ, or the check could not run) or
 * [INCONCLUSIVE] (no page held text to judge by). Unless [OK], it deletes the downloaded model
 * again: the app runs a download only once it passed.
 */
internal class DetectorParity(
    private val context: Context,
    private val log: (JSONObject) -> Unit,
    private val say: (String) -> Unit,
    private val download: (ModelStore, FileSet) -> Unit = { store, set -> ModelDownloader(store).download(set) }
) {

    /**
     * One page's comparison: score differences, text-pixel overlap, lines found by each and
     * matched, the lines large enough for a focus ([big]) and matched, the glyphs of the lines
     * left unmatched as shares of the image's shorter side, the tallest glyphs, how far the two
     * focuses' title and text boxes are apart ([focusDiff], a share of the side; 1 when only one
     * has it), and how many of the two focuses have a title ([titles]).
     */
    data class Comparison(
        val meanDiff: Double,
        val maxDiff: Double,
        val textIou: Double,
        val lines: Int,
        val otherLines: Int,
        val matched: Int,
        val big: Int,
        val bigMatched: Int,
        val unmatched: List<Double>,
        val tallest: Double,
        val otherTallest: Double,
        val focusDiff: Double,
        val titles: Int
    ) {
        /** True when the page holds text a focus uses: large lines, and a title in both focuses. */
        val evidence: Boolean get() = big > 0 && titles == 2

        /** True when the two find the same text where the Smart crop looks: close maps, the same large lines, the same focus. */
        val same: Boolean
            get() = meanDiff <= MEAN_DIFF && textIou >= TEXT_IOU && bigMatched == big && focusDiff <= FOCUS_DIFF &&
                abs(tallest - otherTallest) <= GLYPH_DIFF * max(tallest, otherTallest)

        /** True when the two find the same text ([same]) on a page that holds text a focus uses ([evidence]). */
        val ok: Boolean get() = evidence && same
    }

    /** Runs the check; ends with its verdict ([OK], [FAIL] or [INCONCLUSIVE]) on every path. */
    fun run() {
        var verdict = FAIL
        try {
            verdict = check()
        } catch (e: Exception) {
            log(JSONObject().put("failed", "${e.javaClass.simpleName}: ${e.message}"))
        } finally {
            if (verdict != OK) remove()
            say(verdict)
        }
    }

    /** Deletes the downloaded model, so the app runs the assets' copy, not one that did not pass; logs whether it went. */
    private fun remove() {
        val removed = runCatching { ModelStore.of(context).delete(ModelCatalog.TEXT_DETECTOR) }.getOrDefault(false)
        log(JSONObject().put("removed", removed))
    }

    /** The [verdict] on the pages compared; [FAIL] when the check cannot run. */
    private fun check(): String {
        val assets = TextDetector.install(context) ?: return fail("no asset model")
        val store = ModelStore.of(context)
        val model = ModelCatalog.TEXT_DETECTOR
        if (!store.installed(model)) {
            val t = SystemClock.elapsedRealtime()
            try {
                download(store, model)
            } catch (e: IOException) {
                return fail("download: ${e.message}")
            }
            log(JSONObject().put("downloaded", model.bytes).put("ms", SystemClock.elapsedRealtime() - t))
        }
        val downloaded = TextDetector.downloaded(store) ?: return fail("no downloaded model")
        val a = TextDetector(LibNcnn.cpuLane, { assets })
        val b = TextDetector(LibNcnn.cpuLane, { downloaded })
        val compared = ArrayList<Comparison>()
        try {
            for ((i, size) in PAGES.withIndex()) {
                val page = page(size[0], size[1], i)
                try {
                    val (ma, msA) = timed(a, page)
                    val (mb, msB) = timed(b, page)
                    if (ma == null || mb == null || ma === TextDetector.ABSENT || mb === TextDetector.ABSENT) {
                        return fail("page $i: assets ${ma != null}, downloaded ${mb != null}")
                    }
                    val c = compare(ma, mb, SmartCrop.lines(ma, page.width, page.height), SmartCrop.lines(mb, page.width, page.height), page.width, page.height)
                    compared += c
                    log(
                        JSONObject().put("parity", i).put("w", page.width).put("h", page.height)
                            .put("meanDiff", r5(c.meanDiff)).put("maxDiff", r5(c.maxDiff)).put("textIou", r5(c.textIou))
                            .put("lines", JSONArray(listOf(c.lines, c.otherLines))).put("matched", c.matched)
                            .put("big", c.big).put("bigMatched", c.bigMatched).put("unmatched", JSONArray(c.unmatched.map(::r5)))
                            .put("tallest", JSONArray(listOf(r5(c.tallest), r5(c.otherTallest)))).put("focusDiff", r5(c.focusDiff))
                            .put("titles", c.titles).put("same", c.same).put("evidence", c.evidence)
                            .put("ms", JSONArray(listOf(Stats.round1(msA), Stats.round1(msB)))).put("ok", c.ok)
                    )
                } finally {
                    page.recycle()
                }
            }
        } finally {
            a.close()
            b.close()
        }
        return verdict(compared)
    }

    /** Logs why the check could not run; [FAIL]. */
    private fun fail(why: String): String {
        log(JSONObject().put("failed", why))
        return FAIL
    }

    /** [d]'s map of [page] after one warm-up run, and the median time of [REPS] runs. */
    private fun timed(d: TextDetector, page: Bitmap): Pair<ScoreMap?, Double> {
        d.detect(page)
        var map: ScoreMap? = null
        val ms = (1..REPS).map {
            val t = SystemClock.elapsedRealtimeNanos()
            map = d.detect(page)
            (SystemClock.elapsedRealtimeNanos() - t) / 1e6
        }.sorted()
        return map to ms[ms.size / 2]
    }

    /**
     * A synthetic cover: a two-tone ground, a panel, a title large enough for a focus on every
     * shape ([TITLE] of the shorter side, narrowed to fit), lines of text from large to small,
     * and dots.
     */
    private fun page(w: Int, h: Int, seed: Int): Bitmap {
        val r = Random(seed)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(230, 220, 195))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(60, 90, 140)
        c.drawRect(w * 0.1f, h * 0.4f, w * 0.9f, h * 0.75f, p)
        repeat(30) {
            p.color = Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256))
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, 4f + r.nextFloat() * 12, p)
        }
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        p.color = Color.BLACK
        val title = TITLES[seed % TITLES.size]
        p.textSize = TITLE * min(w, h)
        p.textSize *= min(1f, w * 0.88f / p.measureText(title))
        var y = h * 0.04f + p.textSize * 1.1f
        c.drawText(title, w * 0.06f, y, p)
        for ((k, share) in listOf(0.06f, 0.035f, 0.022f).withIndex()) {
            p.textSize = share * h
            p.color = if (k % 2 == 0) Color.rgb(150, 20, 20) else Color.BLACK
            y += p.textSize * 1.3f
            c.drawText(WORDS[(seed + k) % WORDS.size], w * 0.06f, y, p)
        }
        p.color = Color.WHITE
        p.textSize = 0.05f * h
        c.drawText(WORDS[(seed + 4) % WORDS.size], w * 0.15f, h * 0.6f, p)
        return bmp
    }

    private fun r5(v: Double) = Math.round(v * 100_000) / 100_000.0

    companion object {
        /** Detector inputs as the app makes them for covers of 2:3, 3:4, 1:1 and 3:2. */
        val PAGES: List<IntArray> = listOf(
            SmartCrop.inputSize(1000, 1500), SmartCrop.inputSize(1200, 1600),
            SmartCrop.inputSize(1400, 1400), SmartCrop.inputSize(1500, 1000)
        )

        private val WORDS = listOf("THE SAMPLE", "Volume 7", "an invented story", "NORTH WIND", "Issue 12")

        /** Short titles, so one fits large on every page shape. */
        private val TITLES = listOf("ATLAS", "NOVA", "ORBIT", "ECHO")

        /** The title's size as a share of the shorter side: its line well above [SmartCrop.MIN_GLYPH]. */
        private const val TITLE = 0.2f

        private const val REPS = 3

        /** The check's last line: the copies find the same text, differ, or no page held text to judge by. */
        const val OK = "PARITY: OK"
        const val FAIL = "PARITY: FAIL"
        const val INCONCLUSIVE = "PARITY: INCONCLUSIVE"

        /** [FAIL] when a page shows the copies differ ([Comparison.same]), else [OK] when every page holds [Comparison.evidence], else [INCONCLUSIVE]. */
        fun verdict(pages: List<Comparison>): String = when {
            pages.any { !it.same } -> FAIL
            pages.isNotEmpty() && pages.all { it.evidence } -> OK
            else -> INCONCLUSIVE
        }

        /** Most mean difference of the two score maps. */
        const val MEAN_DIFF = 0.005

        /** Least overlap (intersection over union) of the two maps' text pixels ([SmartCrop.TEXT_THRESHOLD]). */
        const val TEXT_IOU = 0.95

        /** Least overlap of two lines that match. */
        const val LINE_IOU = 0.8

        /** Most difference of the tallest glyphs, as a share of the larger. */
        const val GLYPH_DIFF = 0.05

        /** Most distance of the focuses' title and text box edges, as a share of the side. */
        const val FOCUS_DIFF = 0.01

        /** The least glyph, as a share of the shorter side, of a line a focus may hold: a peer ([SmartCrop.SAME_SIZE]) of the least tallest text ([SmartCrop.MIN_GLYPH]). */
        const val BIG_GLYPH = SmartCrop.SAME_SIZE * SmartCrop.MIN_GLYPH

        /**
         * Compares two score maps of one [w] x [h] image, the lines found in each
         * ([SmartCrop.lines], in the image's pixels), and the focuses those lines make.
         */
        fun compare(a: ScoreMap, b: ScoreMap, linesA: List<SmartCrop.TextBox>, linesB: List<SmartCrop.TextBox>, w: Int, h: Int): Comparison {
            require(a.width == b.width && a.height == b.height) { "${a.width}x${a.height} vs ${b.width}x${b.height}" }
            var sum = 0.0
            var most = 0.0
            var both = 0
            var either = 0
            for (i in a.values.indices) {
                val d = abs(a.values[i] - b.values[i]).toDouble()
                sum += d
                most = max(most, d)
                val ta = a.values[i] > SmartCrop.TEXT_THRESHOLD
                val tb = b.values[i] > SmartCrop.TEXT_THRESHOLD
                if (ta && tb) both++
                if (ta || tb) either++
            }
            val side = min(w, h).toDouble()
            val leftA = linesA.toMutableList()
            val leftB = linesB.toMutableList()
            for (l in linesA) {
                val best = leftB.maxByOrNull { iou(l, it) } ?: continue
                if (iou(l, best) >= LINE_IOU) {
                    leftA -= l
                    leftB -= best
                }
            }
            val isBig = { l: SmartCrop.TextBox -> l.glyph >= BIG_GLYPH * side }
            val big = linesA.count(isBig) + linesB.count(isBig)
            val bigLeft = leftA.count(isBig) + leftB.count(isBig)
            val none = FloatArray(SmartCrop.BINS)
            val fa = SmartCrop.focus(linesA, w, h, none, none)
            val fb = SmartCrop.focus(linesB, w, h, none, none)
            return Comparison(
                if (a.values.isEmpty()) 0.0 else sum / a.values.size, most,
                if (either == 0) 1.0 else both.toDouble() / either,
                linesA.size, linesB.size, linesA.size - leftA.size,
                big, big - bigLeft, (leftA + leftB).map { it.glyph / side }.sortedDescending(),
                linesA.maxOfOrNull { it.glyph } ?: 0.0, linesB.maxOfOrNull { it.glyph } ?: 0.0,
                max(apart(fa.title, fb.title), apart(fa.text, fb.text)),
                listOf(fa, fb).count { it.title != null }
            )
        }

        /** The largest distance of [a]'s and [b]'s edges; 0 when neither is there, 1 when only one is. */
        fun apart(a: Box?, b: Box?): Double = when {
            a == null && b == null -> 0.0
            a == null || b == null -> 1.0
            else -> maxOf(abs(a.left - b.left), abs(a.top - b.top), abs(a.right - b.right), abs(a.bottom - b.bottom))
        }

        /** Intersection over union of two lines. */
        fun iou(a: SmartCrop.TextBox, b: SmartCrop.TextBox): Double {
            val w = min(a.right, b.right) - max(a.left, b.left)
            val h = min(a.bottom, b.bottom) - max(a.top, b.top)
            if (w <= 0 || h <= 0) return 0.0
            val inter = w * h
            return inter / (a.width * a.height + b.width * b.height - inter)
        }
    }
}
