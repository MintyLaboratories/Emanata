package minty.emanata.ai

import minty.emanata.reader.Plan

/**
 * The display sizes at which a chain's models run, from the AI setting's two handles. Both are
 * percentages of side length: the page's shown side over its source side, as model scale names
 * count (a 2x model doubles the side), measured by [shownPercent]. Each handle governs its own
 * models, so the two are independent and may cross.
 *
 * - [upscalePct]: upscaling models run while the page is shown above it. Default 100: a page
 *   shown at its input's size or smaller skips the upscale. Never below [UPSCALE_FLOOR].
 * - [cleanupPct]: cleanup (1x) models other than descreen run while the page is shown above it,
 *   or at every size when [ALWAYS]. Default 50. Descreen models run at every size.
 *
 * Thresholds judge each page at the size it is read at ([ReadSize]): the size it is laid out
 * at, or its printed size where reading it takes zooming in; a zoom's detail renders are not
 * processed by AI.
 */
data class AiThresholds(val upscalePct: Int = UPSCALE_DEFAULT, val cleanupPct: Int = CLEANUP_DEFAULT) {

    init {
        require(upscalePct in UPSCALE_FLOOR..MAX_PCT) { "upscale $upscalePct% outside $UPSCALE_FLOOR..$MAX_PCT" }
        require(cleanupPct == ALWAYS || cleanupPct in MIN_PCT..MAX_PCT) { "cleanup $cleanupPct% outside $MIN_PCT..$MAX_PCT" }
    }

    /** True when an upscaling model runs on a page shown at [shownPct] ([shownPercent]); never at exactly [upscalePct]. */
    fun upscales(shownPct: Double): Boolean = shownPct > upscalePct

    /** True when a cleanup model other than descreen runs on a page shown at [shownPct] ([shownPercent]). */
    fun cleansUp(shownPct: Double): Boolean = cleanupPct == ALWAYS || shownPct > cleanupPct

    /** True when a model of [step] runs on a page shown at [shownPct] ([shownPercent]). */
    fun runs(step: AiStep, shownPct: Double): Boolean = when (step) {
        AiStep.UPSCALE -> upscales(shownPct)
        AiStep.ARTIFACTS -> cleansUp(shownPct)
        AiStep.DESCREEN -> true
    }

    companion object {
        /** The upscale handle's default: upscale pages shown larger than their input. */
        const val UPSCALE_DEFAULT = 100

        /** The cleanup handle's default: clean up pages shown above half their side. */
        const val CLEANUP_DEFAULT = 50

        /** The lowest upscale handle, a page shown at half its side: below it an upscale is always resampled away. */
        const val UPSCALE_FLOOR = 50

        /** The cleanup handle at the slider's left end: cleanup runs at every size. */
        const val ALWAYS = 0

        /** The lowest percentage a handle shows, [ALWAYS] aside. */
        const val MIN_PCT = 25

        /** The highest percentage a handle shows. */
        const val MAX_PCT = 200

        /** Thresholds from stored handle values, each moved into its range. */
        fun of(upscalePct: Int, cleanupPct: Int): AiThresholds = AiThresholds(
            upscalePct.coerceIn(UPSCALE_FLOOR, MAX_PCT),
            if (cleanupPct < MIN_PCT) ALWAYS else cleanupPct.coerceAtMost(MAX_PCT)
        )

        /**
         * The one size measure every threshold uses: the side-length percentage at which a page
         * shows as [plan], on its larger axis (100: shown at its own size), for a model whose
         * input is [scale] times the plan's source (the models before it enlarged it). [plan] is in
         * the pixels of the chain's input: the page after its reduction to fit
         * [ReaderAi.MAX_INPUT_PIXELS] ([minty.emanata.reader.Target.detail]), so
         * [AiPages.steps] and [ReaderAi.unneeded] decide alike on pages over that size.
         */
        fun shownPercent(plan: Plan, scale: Int = 1): Double =
            maxOf(plan.width / plan.spanW, plan.height / plan.spanH) * 100.0 / scale
    }
}
