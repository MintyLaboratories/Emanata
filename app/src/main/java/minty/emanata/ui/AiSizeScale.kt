package minty.emanata.ui

import minty.emanata.ai.AiThresholds
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The AI size slider's axis: [AiThresholds.ALWAYS] at the left end, then [AiThresholds.MIN_PCT]
 * to [AiThresholds.MAX_PCT] of the page's side on a log scale, each doubling as wide (25, 50, 100
 * and 200 % evenly apart). Handles stop every [STEP] %; the upscale handle never below
 * [AiThresholds.UPSCALE_FLOOR], the cleanup handle down to [AiThresholds.ALWAYS].
 */
object AiSizeScale {
    /** The distance between stops, in percent. */
    const val STEP = 5

    /** The width of the "always" slot at the left end, in doublings. */
    private const val ALWAYS_SLOT = 0.5

    private val SPAN = ALWAYS_SLOT + log2(AiThresholds.MAX_PCT.toDouble() / AiThresholds.MIN_PCT)

    /** The percentages the axis labels. */
    val LABELS = listOf(AiThresholds.ALWAYS, 25, 50, 100, 200)

    /** Every stop of the cleanup handle, from the left. */
    val CLEANUP_STOPS: List<Int> = listOf(AiThresholds.ALWAYS) + (AiThresholds.MIN_PCT..AiThresholds.MAX_PCT step STEP)

    /** Every stop of the upscale handle, from the left. */
    val UPSCALE_STOPS: List<Int> = CLEANUP_STOPS.filter { it >= AiThresholds.UPSCALE_FLOOR }

    /** Where [pct] sits on the axis, 0 at the left end to 1 at the right. */
    fun fraction(pct: Int): Float =
        if (pct == AiThresholds.ALWAYS) 0f
        else ((ALWAYS_SLOT + log2(pct.coerceIn(AiThresholds.MIN_PCT, AiThresholds.MAX_PCT).toDouble() / AiThresholds.MIN_PCT)) / SPAN).toFloat()

    /** The stop of [stops] nearest [fraction] on the axis. */
    private fun nearest(stops: List<Int>, fraction: Float): Int {
        val at = fraction.coerceIn(0f, 1f) * SPAN
        if (at < ALWAYS_SLOT / 2 && stops.first() == AiThresholds.ALWAYS) return AiThresholds.ALWAYS
        val pct = AiThresholds.MIN_PCT * 2.0.pow(at - ALWAYS_SLOT)
        val snapped = (pct / STEP).roundToInt() * STEP
        return stops.minBy { if (it == AiThresholds.ALWAYS) Int.MAX_VALUE else abs(it - snapped) }
    }

    /** The upscale handle's stop nearest [fraction]: never left of the floor. */
    fun upscaleAt(fraction: Float): Int = nearest(UPSCALE_STOPS, fraction)

    /** The cleanup handle's stop nearest [fraction]: [AiThresholds.ALWAYS] in the left half of its slot. */
    fun cleanupAt(fraction: Float): Int = nearest(CLEANUP_STOPS, fraction)

    /** The stop [by] stops right (left when negative) of [pct] in [stops], held at either end. */
    fun step(stops: List<Int>, pct: Int, by: Int): Int {
        val i = stops.indexOf(pct).takeIf { it >= 0 } ?: stops.indexOfFirst { it >= pct }.takeIf { it >= 0 } ?: stops.lastIndex
        return stops[(i + by).coerceIn(0, stops.lastIndex)]
    }
}
