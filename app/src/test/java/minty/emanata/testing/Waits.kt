package minty.emanata.testing

import android.os.Looper
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.robolectric.Shadows.shadowOf

/**
 * Waits for work on other threads by polling a condition: returns as soon as it holds,
 * and throws an [AssertionError] naming what it waited for once [LIVENESS_MS] of real
 * time passes. The bound only tells a hang from slowness; a wait never gives up
 * silently. Prefer waiting on the work itself (join its job, await its latch) where
 * the test can reach it.
 */
object Waits {
    /** Real time after which a wait counts as hung: far beyond any slowness under load. */
    const val LIVENESS_MS = 60_000L

    /** Real time between polls. */
    private const val POLL_MS = 5L

    /** Runs [step] and checks [done] until it holds; throws naming [what] after [boundMs]. Callers use [idleUntil] or [awaitUntil]. */
    @VisibleForTesting
    internal fun poll(what: String, boundMs: Long, step: () -> Unit, done: () -> Boolean) {
        val start = System.nanoTime()
        while (true) {
            step()
            if (done()) return
            val waited = (System.nanoTime() - start) / 1_000_000
            if (waited >= boundMs) throw AssertionError("Gave up waiting for $what after $waited ms")
            Thread.sleep(POLL_MS)
        }
    }

    /** Joins [job]; throws naming [what] after [boundMs]. Callers use [awaitJob]. */
    @VisibleForTesting
    internal fun join(what: String, job: Job, boundMs: Long) {
        runBlocking { withTimeoutOrNull(boundMs) { job.join() } } ?: throw AssertionError("Gave up waiting for $what after $boundMs ms")
    }
}

/**
 * Idles the main looper, then runs [step], until [done] holds; throws naming [what] if
 * it never does. For work that ends on the main thread, such as a result posted back
 * from a background thread. [step] does what else must happen between polls, such as
 * laying out a view or moving the looper's clock on.
 */
fun idleUntil(what: String, step: () -> Unit = {}, done: () -> Boolean) =
    Waits.poll(what, Waits.LIVENESS_MS, { shadowOf(Looper.getMainLooper()).idle(); step() }, done)

/**
 * Waits for [job] to end, never running the main looper; throws naming [what] if it never
 * does. For work on a background dispatcher. A job counts as ended before its other
 * waiters are resumed: wait for what they post to the main thread with [idleUntil], as a
 * single idle may come before they post it.
 */
fun awaitJob(what: String, job: Job) = Waits.join(what, job, Waits.LIVENESS_MS)

/** Polls [done], never running the main looper, until it holds; throws naming [what] if it never does. For work wholly off the main thread. */
fun awaitUntil(what: String, done: () -> Boolean) = Waits.poll(what, Waits.LIVENESS_MS, {}, done)
