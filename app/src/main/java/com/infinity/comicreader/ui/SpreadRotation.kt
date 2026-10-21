package com.infinity.comicreader.ui

import android.content.pm.ActivityInfo

/**
 * "Rotate for spreads": holds the screen in landscape, either way up, from when a wide
 * page shows whole until a page that is not wide shows; then the reader's own
 * orientation ([user]) is back. Wide pages in a row keep it held. An orientation the
 * reader picks meanwhile wins, and the page it was picked on is not turned for again.
 * Free of the activity, so it can be tested alone.
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

    /**
     * Page [widePage] shows (a wide page shown whole, or -1 for anything else), with the
     * setting [enabled] or not: the orientation to request now, or null to leave it as is.
     */
    fun show(widePage: Int, enabled: Boolean): Int? {
        if (widePage != declined) declined = -1
        val want = if (enabled && widePage >= 0 && widePage != declined) widePage else -1
        val wasHeld = held >= 0
        held = want
        return when {
            want >= 0 && !wasHeld -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            want < 0 && wasHeld -> user
            else -> null
        }
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
}
