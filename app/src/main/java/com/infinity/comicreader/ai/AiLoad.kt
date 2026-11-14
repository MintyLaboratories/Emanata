package com.infinity.comicreader.ai

/**
 * The load estimate of a chain, shown as a plain bar with no unit or numbers: its models' page
 * times summed ([pageMs]), on a scale from 0 to [FULL_MS] ([fraction]), read as a [Level] by
 * color and in words.
 */
object AiLoad {
    /** A full bar; a chain past it shows full. */
    const val FULL_MS = 30_000L

    /** From here a chain reads [Level.MODERATE] (yellow); below it [Level.LIGHT] (green). */
    const val MODERATE_MS = 10_000L

    /** Past this a chain reads [Level.HEAVY] (red). */
    const val HEAVY_MS = 20_000L

    enum class Level { LIGHT, MODERATE, HEAVY }

    /** [models]' page times together: each one's on this device ([learned], as [PageTimes.estimate] gives) where known, else its [AiModel.pageMs]. */
    fun pageMs(models: List<AiModel>, learned: (AiModel) -> Long? = { null }): Long = models.sumOf { learned(it) ?: it.pageMs }

    /** How full the bar is for [ms], from 0 to 1. */
    fun fraction(ms: Long): Float = (ms.toDouble() / FULL_MS).coerceIn(0.0, 1.0).toFloat()

    /** What [ms] reads as: light below [MODERATE_MS], moderate up to [HEAVY_MS], heavy past it. */
    fun level(ms: Long): Level = when {
        ms < MODERATE_MS -> Level.LIGHT
        ms <= HEAVY_MS -> Level.MODERATE
        else -> Level.HEAVY
    }
}
