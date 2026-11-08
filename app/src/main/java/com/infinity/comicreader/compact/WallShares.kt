package com.infinity.comicreader.compact

/**
 * Splits wall time among tasks running together: while `n` run, each is charged `1/n` of
 * the time passing, so their charges add up to the time any ran. A page converting alone
 * (one too large to share memory) is charged all of its time; four converting side by side
 * a quarter each. Thread-safe.
 */
class WallShares(private val clock: () -> Long = { System.nanoTime() }) {
    private val charged = HashMap<Any, Double>()
    private var mark = 0L

    /** Task [key] starts. */
    @Synchronized
    fun start(key: Any) {
        tick()
        charged[key] = 0.0
    }

    /** Task [key] ends: its share of the wall time it ran, in the clock's units; 0 for one never started. */
    @Synchronized
    fun end(key: Any): Double {
        tick()
        return charged.remove(key) ?: 0.0
    }

    /** Charges the time since the last event to the tasks running, in equal parts. */
    private fun tick() {
        val now = clock()
        val n = charged.size
        if (n > 0) {
            val each = (now - mark).coerceAtLeast(0).toDouble() / n
            for (k in charged.keys) charged[k] = charged.getValue(k) + each
        }
        mark = now
    }
}
