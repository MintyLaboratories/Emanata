package minty.emanata.debug

import minty.emanata.ai.Fallback
import minty.emanata.ai.GrayPages
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pure parts of [AiCheckService]'s `gray` run: a black-and-white screentone page, how much
 * color a model's output on it holds before and after the collapse to gray ([GrayPages]), and
 * what voids or fails a run.
 */
internal object GrayRuns {
    /** Catalog ids of the models a `gray` run checks by default: the descreen models. */
    val DESCREEN_IDS = listOf("opencomic-ai-descreen-hard-compact-v2", "opencomic-ai-descreen-hard-fast-v3-256")

    /**
     * A [w] x [h] black-and-white page as a scanner gives it, opaque ARGB with red, green and blue
     * equal: white paper with faint grain, black panel borders and ink lines, and halftone screens
     * of round dots with soft edges, in three panels: a fine 45° screen graded from light to dark,
     * a flat mid-gray screen under solid ink and hatching, and a coarse 15° screen graded top to
     * bottom. The same for the same size.
     */
    fun screentone(w: Int, h: Int): IntArray {
        val px = IntArray(maxOf(w, 0) * maxOf(h, 0))
        if (px.isEmpty()) return px
        val grain = Random(w * 31 + h)
        val margin = maxOf(1, w / 25)
        val border = maxOf(1, w / 300)
        val panelH = h / 3.0
        val fine = Screen(period = maxOf(3.0, w / 160.0), degrees = 45.0)
        val coarse = Screen(period = maxOf(4.0, w / 90.0), degrees = 15.0)
        for (y in 0 until h) {
            val panel = minOf(2, (y / panelH).toInt())
            val top = (panel * panelH).toInt() + margin
            val bottom = ((panel + 1) * panelH).toInt() - margin
            for (x in 0 until w) {
                val inside = x in margin until w - margin && y in top until bottom
                val ink = when {
                    !inside -> 0.0
                    x < margin + border || x >= w - margin - border || y < top + border || y >= bottom - border -> 1.0
                    else -> {
                        val u = (x - margin).toDouble() / maxOf(1, w - 2 * margin)
                        val v = (y - top).toDouble() / maxOf(1, bottom - top)
                        when (panel) {
                            0 -> fine.ink(x, y, 0.05 + 0.9 * u)
                            1 -> when {
                                u in 0.55..0.75 && v in 0.2..0.8 -> 1.0
                                u > 0.8 && ((x + y) % maxOf(3, w / 200)) == 0 -> 1.0
                                else -> fine.ink(x, y, 0.4)
                            }
                            else -> coarse.ink(x, y, 0.1 + 0.8 * v)
                        }
                    }
                }
                val paper = 255 - grain.nextInt(4)
                val g = (paper * (1 - ink)).roundToInt().coerceIn(0, 255)
                px[y * w + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return px
    }

    /** A halftone screen of round dots, [period] pixels apart along lines at [degrees]. */
    private class Screen(private val period: Double, degrees: Double) {
        private val c = cos(degrees * PI / 180)
        private val s = sin(degrees * PI / 180)

        /** Ink at ([x], [y]), 0 to 1, for a [tone] (0 white, 1 black): round dots that merge past half. */
        fun ink(x: Int, y: Int, tone: Double): Double {
            val u = (x * c + y * s) * 2 * PI / period
            val v = (-x * s + y * c) * 2 * PI / period
            // The round-dot spot function, 1 at a dot's center, -1 between dots.
            val spot = (cos(u) + cos(v)) / 2
            val threshold = 1 - 2 * tone
            // A soft edge about a pixel wide, as a scan blurs it.
            val edge = 2 * PI / period
            return ((spot - threshold) / edge + 0.5).coerceIn(0.0, 1.0)
        }
    }

    /**
     * Pixel spreads over a page: the largest, the mean, the pixels over 2 and over 16, and where
     * the largest first is (-1 on a gray page).
     */
    data class Spread(val max: Int, val mean: Double, val over2: Int, val over16: Int, val atX: Int, val atY: Int)

    /**
     * How colored [px] (ARGB, rows [width] wide) is, from each pixel's spread: the difference
     * between its largest and smallest of red, green and blue ([GrayPages.spreadOf]). On a
     * model's output this is the 8-bit page as the next step reads it: each channel rounded and
     * clamped to 0..255, so a tint where the model overshoots (paper past white, ink past black)
     * is clipped away and the largest spread can be under the model's own.
     */
    fun spread(px: IntArray, width: Int): Spread {
        var most = 0
        var at = -1
        var sum = 0L
        var over2 = 0
        var over16 = 0
        for (i in px.indices) {
            val s = GrayPages.spreadOf(px[i])
            sum += s
            if (s > 2) over2++
            if (s > 16) over16++
            if (s > most) {
                most = s
                at = i
            }
        }
        val w = maxOf(width, 1)
        return Spread(
            max = most,
            mean = if (px.isEmpty()) 0.0 else sum.toDouble() / px.size,
            over2 = over2,
            over16 = over16,
            atX = if (at < 0) -1 else at % w,
            atY = if (at < 0) -1 else at / w,
        )
    }

    /**
     * Why a model's `gray` line is void, else null: [askedGpu] but it opened on the CPU
     * ([onGpu] false), so it says nothing about the GPU.
     */
    fun engineProblem(askedGpu: Boolean, onGpu: Boolean): String? =
        if (askedGpu && !onGpu) "engineMismatch: asked gpu, opened on cpu" else null

    /**
     * Why a model's `gray` line fails, else null: its raw run, meant to keep the model's color,
     * was collapsed ([rawCollapsed]) or fell back ([rawFallback]); or the reader's chain at the
     * check's display size did not run it ([chainRan] 0, [chainFallback]), so the page shown
     * says nothing of the collapse. Descreen models run at every display size.
     */
    fun runProblem(rawCollapsed: Boolean, rawFallback: Fallback?, chainRan: Int, chainFallback: Fallback?): String? = when {
        rawCollapsed -> "raw run collapsed: its color is gone"
        rawFallback != null -> "raw run fell back: ${rawFallback.name}"
        chainFallback == Fallback.UNNEEDED || (chainRan == 0 && chainFallback == null) -> "chain skipped the model"
        chainFallback != null -> "chain fell back: ${chainFallback.name}"
        else -> null
    }
}
