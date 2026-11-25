package minty.emanata.ui

/**
 * "Previous chapter on repeated back": counts the "previous page" actions made while a
 * chapter's first page is shown. [required] of them in a row, each within [windowMs] of
 * the one before, open the previous chapter; 0 turns this off. The caller [reset]s it
 * whenever the page changes, so only actions made on the first page count.
 */
class RepeatedBack(private val windowMs: Long = WINDOW_MS) {

    enum class Result {
        /** Off: the action does nothing. */
        IGNORED,

        /** Counted; another one is needed, so the reader hints at it. */
        HINT,

        /** The last one needed: open the previous chapter. */
        OPEN
    }

    private var count = 0
    private var last = 0L

    /** A "previous page" action at time [now] (ms), with [required] actions needed in all. */
    fun back(now: Long, required: Int): Result {
        if (required <= 0) {
            reset()
            return Result.IGNORED
        }
        if (count > 0 && now - last > windowMs) count = 0
        count++
        last = now
        if (count < required) return Result.HINT
        reset()
        return Result.OPEN
    }

    fun reset() {
        count = 0
    }

    companion object {
        const val WINDOW_MS = 1500L
    }
}
