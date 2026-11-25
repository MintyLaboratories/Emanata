package minty.emanata.ui

import kotlin.math.roundToInt

/** Where the end-of-chapter card sits: low on the screen, easy to reach, clear of the last page's art. */
internal object EndCard {
    /** Height of the card's center, as a share of the screen's. */
    const val CENTER = 0.75f

    /**
     * Top of a card [cardHeight] tall in a parent [parentHeight] tall: centered at [CENTER],
     * then moved up to end [margin] above [bottomLimit] (the bottom bar's top, or the
     * parent's bottom) and down to start at least [margin] from the top.
     */
    fun top(parentHeight: Int, cardHeight: Int, margin: Int, bottomLimit: Int = parentHeight): Int {
        val centered = (parentHeight * CENTER - cardHeight / 2f).roundToInt()
        return centered.coerceAtMost(bottomLimit - margin - cardHeight).coerceAtLeast(margin)
    }
}
