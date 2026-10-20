package com.infinity.comicreader.reader

/**
 * Where tapping the page turns it. Each layout splits one axis into thirds: the first
 * third goes one way, the last third the other, and the middle third toggles the UI.
 */
enum class TapZones(val value: String) {
    DISABLED("disabled"),
    LEFT_RIGHT("left_right"),
    RIGHT_LEFT("right_left"),
    TOP_BOTTOM("top_bottom"),
    BOTTOM_TOP("bottom_top");

    enum class Action { PREVIOUS, NEXT, MENU }

    /**
     * What a tap at ([x], [y]), as fractions of the view's width and height, does. With
     * [mirror] a right-to-left ([rtl]) book swaps the left and right zones; top and
     * bottom zones never swap.
     */
    fun action(x: Float, y: Float, rtl: Boolean, mirror: Boolean): Action {
        val along = when (this) {
            DISABLED -> return Action.MENU
            LEFT_RIGHT, RIGHT_LEFT -> x
            TOP_BOTTOM, BOTTOM_TOP -> y
        }
        val third = when {
            along < 1f / 3f -> 0
            along > 2f / 3f -> 2
            else -> return Action.MENU
        }
        var firstIsPrevious = this == LEFT_RIGHT || this == TOP_BOTTOM
        if (rtl && mirror && (this == LEFT_RIGHT || this == RIGHT_LEFT)) firstIsPrevious = !firstIsPrevious
        return if ((third == 0) == firstIsPrevious) Action.PREVIOUS else Action.NEXT
    }

    companion object {
        val DEFAULT = LEFT_RIGHT

        fun of(value: String?): TapZones = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}
