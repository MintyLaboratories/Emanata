package com.infinity.comicreader.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import com.infinity.comicreader.ai.LibNcnn
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelDownloader
import com.infinity.comicreader.ai.ModelStore
import com.infinity.comicreader.ai.ScoreMap
import com.infinity.comicreader.ai.TextDetector
import com.infinity.comicreader.data.SmartCrop
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Checks that the downloaded text detector ([ModelCatalog.TEXT_DETECTOR]) finds what the copy
 * in the app's assets finds: both run as the app runs them on synthetic covers ([PAGES]), and
 * their score maps and lines are compared ([compare]). Downloads the model first when it is
 * not in place. Logs one JSON object per page, then "PARITY: OK" or "PARITY: FAIL".
 */
internal class DetectorParity(private val context: Context, private val log: (JSONObject) -> Unit, private val say: (String) -> Unit) {

    /** One page's comparison: score differences, text-pixel overlap, and lines found by each and matched. */
    data class Comparison(
        val meanDiff: Double,
        val maxDiff: Double,
        val textIou: Double,
        val lines: Int,
        val otherLines: Int,
        val matched: Int,
        val tallest: Double,
        val otherTallest: Double
    ) {
        /** True when the two find the same text: close maps, the same lines, the same tallest glyph. */
        val ok: Boolean
            get() = meanDiff <= MEAN_DIFF && textIou >= TEXT_IOU && lines == otherLines && matched == lines &&
                abs(tallest - otherTallest) <= GLYPH_DIFF * max(tallest, otherTallest)
    }

    fun run() {
        val assets = TextDetector.install(context) ?: return fail("no asset model")
        val store = ModelStore.of(context)
        val model = ModelCatalog.TEXT_DETECTOR
        if (!store.installed(model)) {
            val t = SystemClock.elapsedRealtime()
            ModelDownloader(store).download(model)
            log(JSONObject().put("downloaded", model.bytes).put("ms", SystemClock.elapsedRealtime() - t))
        }
        val downloaded = TextDetector.downloaded(store) ?: return fail("no downloaded model")
        val a = TextDetector(LibNcnn.cpuLane, { assets })
        val b = TextDetector(LibNcnn.cpuLane, { downloaded })
        var ok = true
        try {
            for ((i, size) in PAGES.withIndex()) {
                val page = page(size[0], size[1], i)
                try {
                    val (ma, msA) = timed(a, page)
                    val (mb, msB) = timed(b, page)
                    if (ma == null || mb == null || ma === TextDetector.ABSENT || mb === TextDetector.ABSENT) {
                        return fail("page $i: assets ${ma != null}, downloaded ${mb != null}")
                    }
                    val c = compare(ma, mb, SmartCrop.lines(ma, page.width, page.height), SmartCrop.lines(mb, page.width, page.height))
                    ok = ok && c.ok
                    log(
                        JSONObject().put("parity", i).put("w", page.width).put("h", page.height)
                            .put("meanDiff", r5(c.meanDiff)).put("maxDiff", r5(c.maxDiff)).put("textIou", r5(c.textIou))
                            .put("lines", JSONArray(listOf(c.lines, c.otherLines))).put("matched", c.matched)
                            .put("tallest", JSONArray(listOf(r5(c.tallest), r5(c.otherTallest))))
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
        say(if (ok) "PARITY: OK" else "PARITY: FAIL")
    }

    private fun fail(why: String) {
        log(JSONObject().put("failed", why))
        say("PARITY: FAIL")
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

    /** A synthetic cover: a two-tone ground, a panel, lines of text from large to small, and dots. */
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
        var y = h * 0.04f
        for ((k, share) in listOf(0.11f, 0.06f, 0.035f, 0.022f).withIndex()) {
            p.textSize = share * h
            p.color = if (k % 2 == 0) Color.BLACK else Color.rgb(150, 20, 20)
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

        private const val REPS = 3

        /** Most mean difference of the two score maps. */
        const val MEAN_DIFF = 0.005

        /** Least overlap (intersection over union) of the two maps' text pixels ([SmartCrop.TEXT_THRESHOLD]). */
        const val TEXT_IOU = 0.95

        /** Least overlap of two lines that match. */
        const val LINE_IOU = 0.8

        /** Most difference of the tallest glyphs, as a share of the larger. */
        const val GLYPH_DIFF = 0.05

        /** Compares two score maps of one image, and the lines found in each ([SmartCrop.lines]). */
        fun compare(a: ScoreMap, b: ScoreMap, linesA: List<SmartCrop.TextBox>, linesB: List<SmartCrop.TextBox>): Comparison {
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
            val left = linesB.toMutableList()
            var matched = 0
            for (l in linesA) {
                val best = left.maxByOrNull { iou(l, it) } ?: continue
                if (iou(l, best) >= LINE_IOU) {
                    matched++
                    left -= best
                }
            }
            return Comparison(
                if (a.values.isEmpty()) 0.0 else sum / a.values.size, most,
                if (either == 0) 1.0 else both.toDouble() / either,
                linesA.size, linesB.size, matched,
                linesA.maxOfOrNull { it.glyph } ?: 0.0, linesB.maxOfOrNull { it.glyph } ?: 0.0
            )
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
