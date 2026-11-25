package minty.emanata.util

/** Lets at most [limit] pass at once, the limit read anew as each one comes. Free of Android types. */
class LimitGate(private val limit: () -> Int) {
    private val lock = Object()
    private var inside = 0

    fun <T> pass(block: () -> T): T {
        synchronized(lock) {
            while (inside >= limit().coerceAtLeast(1)) lock.wait(WAIT_MS)
            inside++
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                inside--
                lock.notifyAll()
            }
        }
    }

    private companion object {
        /** Longest wait before the limit is read again, as when the device cools down. */
        const val WAIT_MS = 200L
    }
}
