package minty.emanata.ui

import android.content.pm.ActivityInfo

/**
 * "Rotate image": a wide page shown whole in a portrait layout is drawn turned a quarter
 * to fill the screen ([turns]), and the screen is locked as it is while such a page shows
 * ([show]), so turning the device to read it does not turn the screen as well. A page that
 * is not turned hands the screen back to the reader's own orientation: the system's, unless
 * the reader picked one. An orientation the reader picks meanwhile wins ([choose]), and the
 * page it was picked on is not locked for again. Free of the activity, so it can be tested
 * alone; [save] and [restore] carry it over a recreation.
 */
class ImageTurn {

    /** The page the screen is locked for, or -1. */
    var locked = -1
        private set

    /** The page the reader picked an orientation on while locked, or -1. */
    private var declined = -1

    /**
     * True when wide page [page] (-1 for none) is drawn turned, with the setting [enabled], the
     * screen [sideways] or not: a landscape window or display, or a window sharing the screen,
     * where no lock holds.
     */
    fun turns(page: Int, enabled: Boolean, sideways: Boolean): Boolean = enabled && !sideways && page >= 0

    /**
     * Page [widePage] shows (a wide page shown whole, or -1 for anything else), with the
     * setting [enabled] or not, the screen [sideways] or not ([turns]), the reader's own
     * orientation [user]: the orientation to request now, or null to leave it as is.
     */
    fun show(widePage: Int, enabled: Boolean, sideways: Boolean, user: Int): Int? {
        if (widePage != declined) declined = -1
        val want = if (turns(widePage, enabled, sideways) && widePage != declined) widePage else -1
        val wasLocked = locked >= 0
        locked = want
        return when {
            want >= 0 && !wasLocked -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            want < 0 && wasLocked -> user
            else -> null
        }
    }

    /** The reader picked an orientation, requested by the caller: a lock is let go, and its page not locked for again. */
    fun choose() {
        if (locked < 0) return
        declined = locked
        locked = -1
    }

    /**
     * The book closed: a lock is let go, and a declined page forgotten. Returns [user], the
     * orientation to request, when a lock was held; null otherwise.
     */
    fun release(user: Int): Int? {
        declined = -1
        val wasLocked = locked >= 0
        locked = -1
        return if (wasLocked) user else null
    }

    /** All of this, for [restore]. */
    fun save(): IntArray = intArrayOf(locked, declined)

    /** Takes back what [save] gave; ignores anything else. */
    fun restore(saved: IntArray?) {
        if (saved == null || saved.size != 2) return
        locked = saved[0]
        declined = saved[1]
    }

    companion object {
        /** Quarter turns a picture is drawn at ([TurnFrame.turn]): its top to the right, or to the left when [leftHanded]. */
        fun quarter(leftHanded: Boolean): Int = if (leftHanded) -1 else 1
    }
}
