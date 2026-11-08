package com.infinity.comicreader.reader

/**
 * Which calibration run is current, kept free of threads so it can be tested: [Calibrator]
 * starts a thread for each id [begin] hands out and tells [ended] when it stops. A run is
 * dropped ([dropped]) when a new one replaces it or the user cancels it ([cancel]).
 */
class CalibrationRuns {
    /** How a run ended ([ended]). */
    enum class End {
        /** It ran to its end, finished or not: report it. */
        REPORTED,

        /** The user canceled it. */
        CANCELED,

        /** Dropped for a new run. */
        DROPPED
    }

    /** True while a run is in progress. */
    @Volatile
    var running = false
        private set

    /** The run in progress (0: none); -1 once dropped. */
    @Volatile
    private var current = 0

    private var runs = 0

    /** A new run starts once the dropped one ends. */
    private var again = false

    /** True while the user is stopping the run ([cancel]). */
    @Volatile
    var canceling = false
        private set

    /**
     * A new run's id, or 0 while one is running. With [drop] that one is dropped and a new one
     * starts once it ends; with [restart] likewise when the user is canceling it, so a run asked
     * for right after a cancel is not lost to the one ending.
     */
    @Synchronized
    fun begin(drop: Boolean = false, restart: Boolean = false): Int {
        if (running) {
            if (drop || (restart && canceling)) {
                current = -1
                again = true
                canceling = false
            }
            return 0
        }
        running = true
        current = ++runs
        return current
    }

    /** Stops the run in progress, and any waiting to follow it; false when none runs. */
    @Synchronized
    fun cancel(): Boolean {
        again = false
        if (!running) return false
        canceling = true
        current = -1
        return true
    }

    /** True once run [id] is no longer the current one. */
    fun dropped(id: Int): Boolean = current != id

    /** Run [id] ended: how, and whether a new run is to start now. */
    @Synchronized
    fun ended(id: Int): Pair<End, Boolean> {
        val how = when {
            current == id -> End.REPORTED
            canceling -> End.CANCELED
            else -> End.DROPPED
        }
        running = false
        current = 0
        canceling = false
        val next = again
        again = false
        return how to next
    }
}
