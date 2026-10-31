package com.infinity.comicreader.ui

import android.content.pm.ActivityInfo

/**
 * "Rotate for spreads": holds the screen in landscape, either way up, from when a wide
 * page shows whole until a page that is not wide shows; then the reader's own
 * orientation ([user]) is back. Wide pages in a row keep it held. An orientation the
 * reader picks meanwhile wins, and the page it was picked on is not turned for again.
 * While held, the layout follows the orientation that comes back ([layoutLandscape]),
 * so a turn made only for one page never re-pairs the pages around it. Free of the
 * activity, so it can be tested alone; [save] and [restore] carry it over a recreation.
 *
 * Following the sensor, the screen turns back by itself only when the sensor can tell
 * which way is up; lying flat, it cannot. So when the sensor is unsure as the hold ends,
 * the orientation the screen was turned from comes back explicitly and is kept
 * ([keeping]) until the sensor settles ([settled]); only then is the sensor followed again.
 */
class SpreadRotation {

    /** The orientation the reader picked; unspecified follows the sensor. */
    var user: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        private set

    /** The wide page the screen is held for, or -1. */
    var held = -1
        private set

    /** True while the orientation the screen was turned from is kept after a hold, until the sensor settles. */
    var keeping = false
        private set

    /** The page the reader picked an orientation on while it was held, or -1. */
    private var declined = -1

    /** Whether the screen was in landscape when it was turned for [held]. */
    private var heldFromLandscape = false

    /** The fixed orientation the screen was in when it was turned for [held]; unspecified when unknown. */
    private var heldFrom = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /** True when [show] would turn the screen for wide page [page], with the setting [enabled]: none the reader declined. */
    fun turnsFor(page: Int, enabled: Boolean): Boolean = enabled && page >= 0 && page != declined

    /** True while the sensor matters: the screen is held, or kept until the sensor settles. */
    val watching: Boolean get() = held >= 0 || keeping

    /**
     * Page [widePage] shows (a wide page shown whole, or -1 for anything else), with the
     * setting [enabled] or not, the screen in [landscape] or not and in the fixed
     * orientation [screen] (unspecified when unknown), the sensor [unsure] of the way up
     * or not: the orientation to request now, or null to leave it as is. A wide page
     * while [keeping] turns from the kept orientation, whatever the screen shows yet.
     */
    fun show(
        widePage: Int,
        enabled: Boolean,
        landscape: Boolean = false,
        screen: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
        unsure: Boolean = false
    ): Int? {
        if (widePage != declined) declined = -1
        val want = if (enabled && widePage >= 0 && widePage != declined) widePage else -1
        val wasHeld = held >= 0
        held = want
        return when {
            want >= 0 && !wasHeld -> {
                // While kept, the screen may still be turning to the kept orientation: it stays the one to come back to.
                if (!keeping) {
                    heldFromLandscape = landscape
                    heldFrom = screen
                }
                keeping = false
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            want < 0 && wasHeld -> {
                keeping = unsure && user == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED &&
                    heldFrom != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                if (keeping) heldFrom else user
            }
            else -> null
        }
    }

    /** The sensor settled on a way up: the orientation to request now, or null to leave it as is. */
    fun settled(): Int? {
        if (!keeping) return null
        keeping = false
        return user
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

    /** All of this, for [restore]. */
    fun save(): IntArray = intArrayOf(user, held, declined, if (heldFromLandscape) 1 else 0, heldFrom, if (keeping) 1 else 0)

    /** Takes back what [save] gave; ignores anything else. */
    fun restore(saved: IntArray?) {
        if (saved == null || saved.size != 6) return
        user = saved[0]
        held = saved[1]
        declined = saved[2]
        heldFromLandscape = saved[3] == 1
        heldFrom = saved[4]
        keeping = saved[5] == 1
    }

    /** The reader picks [orientation]: it is requested at once, and a held or kept orientation is let go. */
    fun choose(orientation: Int): Int {
        user = orientation
        keeping = false
        if (held >= 0) {
            declined = held
            held = -1
        }
        return orientation
    }

    companion object {
        private val LANDSCAPES = setOf(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        )
        private val PORTRAITS = setOf(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        )

        /**
         * The fixed orientation of display [rotation] (`Surface.ROTATION_0` to `_270`, as
         * 0 to 3), the screen being in [landscape] there. A display whose own way up is
         * portrait has its landscape a quarter turn on (`ROTATION_90`); one whose own way up
         * is landscape has its portrait three (`ROTATION_270`); [reversed] (the platform's
         * `config_reverseDefaultRotation`) swaps those quarter turns. Unspecified for no rotation.
         */
        fun fixedOrientation(rotation: Int, landscape: Boolean, reversed: Boolean = false): Int {
            if (rotation !in 0..3) return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val quarter = rotation % 2 == 1
            val naturalPortrait = quarter == landscape
            if (!quarter) {
                val flipped = rotation == 2
                return when {
                    naturalPortrait && !flipped -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    naturalPortrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                    !flipped -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                }
            }
            val turned = if (reversed) 3 else 1
            return if (naturalPortrait) {
                if (rotation == turned) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            } else {
                if (rotation == 4 - turned) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            }
        }
    }
}
