package com.infinity.comicreader.ui

import android.content.pm.ActivityInfo

/**
 * "Rotate for spreads": holds the screen in landscape, either way up, from when a wide
 * page shows whole until a page that is not wide shows; then the reader's own
 * orientation ([user]) is back. Wide pages in a row keep it held. An orientation the
 * reader picks meanwhile wins, and the page it was picked on is not turned for again.
 * While held, the layout follows the orientation that comes back ([layoutLandscape]),
 * so a turn made only for one page never re-pairs the pages around it. Free of the
 * activity, so it can be tested alone.
 */
class SpreadRotation {

    /** The orientation the reader picked; unspecified follows the sensor. */
    var user: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        private set

    /** The wide page the screen is held for, or -1. */
    var held = -1
        private set

    /** The page the reader picked an orientation on while it was held, or -1. */
    private var declined = -1

    /** Whether the screen was in landscape when it was turned for [held]. */
    private var heldFromLandscape = false

    /**
     * Page [widePage] shows (a wide page shown whole, or -1 for anything else), with the
     * setting [enabled] or not, the screen in [landscape] or not: the orientation to
     * request now, or null to leave it as is.
     */
    fun show(widePage: Int, enabled: Boolean, landscape: Boolean = false): Int? {
        if (widePage != declined) declined = -1
        val want = if (enabled && widePage >= 0 && widePage != declined) widePage else -1
        val wasHeld = held >= 0
        held = want
        return when {
            want >= 0 && !wasHeld -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE.also { heldFromLandscape = landscape }
            want < 0 && wasHeld -> user
            else -> null
        }
    }

    /**
     * Whether pages are laid out for landscape, the screen being in [landscape] or not:
     * while held, as the orientation that comes back will be ([user]'s, else the one the
     * screen was turned from).
     */
    fun layoutLandscape(landscape: Boolean): Boolean = when {
        held < 0 -> landscape
        user in LANDSCAPES -> true
        user in PORTRAITS -> false
        else -> heldFromLandscape
    }

    /** The reader picks [orientation]: it is requested at once, and a held page is let go. */
    fun choose(orientation: Int): Int {
        user = orientation
        if (held >= 0) {
            declined = held
            held = -1
        }
        return orientation
    }

    private companion object {
        val LANDSCAPES = setOf(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        )
        val PORTRAITS = setOf(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        )
    }
}
