package minty.emanata.files

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import minty.emanata.util.AppDispatchers
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/**
 * The queue of file operations (copy, move, delete, rename, compact, make compatible, pack, repack): one at a time, never two
 * at once, each in turn as it was started. They run in the app's process rather than in
 * a screen, so turning the device does not stop them, and [onStart] keeps the app running
 * meanwhile ([FileWorkService]). Whichever library screen is there shows their [operations],
 * and [acknowledge]s each outcome once it has shown it.
 */
object FileWork {
    enum class Kind { COPY, MOVE, DELETE, RENAME, COMPACT, COMPATIBLE, PACK, REPACK }

    /**
     * What rebuilding books did beyond their count: bytes [saved] (less than 0 when they
     * grew), books left [unchanged] ([nested] of them for holding other archives), and by
     * book the pages [reencoded] (their pixels changed slightly) and those [failed] (kept
     * as they were, as they could not be made anew); [already] of those unchanged had it done
     * before, as their mark says. For Pack, the new files [keptBoth]: named anew, as their
     * name was taken.
     */
    data class Summary(
        val saved: Long,
        val unchanged: Int = 0,
        val reencoded: Map<String, List<String>> = emptyMap(),
        val failed: Map<String, List<String>> = emptyMap(),
        val nested: Int = 0,
        val keptBoth: List<String> = emptyList(),
        val already: Int = 0
    ) {
        /** How many pages [failed]. */
        val failedPages: Int get() = failed.values.sumOf { it.size }
    }

    sealed class Outcome {
        /** What is no longer where it was: deleted, or moved away. */
        abstract val gone: List<String>

        /** What the books rebuilt came to, if any were. */
        val rebuilt: Summary?
            get() = when (this) {
                is Done -> summary
                is Canceled -> summary
                is Failed -> summary
            }

        /**
         * Finished with [count] items: [path] is the item to show, if any; [sourceLeft] when a
         * move could not delete a source; [summary] for books rebuilt.
         */
        data class Done(
            val path: String?,
            val sourceLeft: Boolean = false,
            override val gone: List<String> = emptyList(),
            val count: Int = 1,
            val summary: Summary? = null
        ) : Outcome()

        /**
         * Canceled after [done] of [count] items; [summary] for books rebuilt before; [givenBack]
         * items a move did not reach picked up again as a Paste.
         */
        data class Canceled(
            val done: Int = 0,
            val count: Int = 1,
            override val gone: List<String> = emptyList(),
            val summary: Summary? = null,
            val givenBack: Int = 0
        ) : Outcome()

        /**
         * Failed with [error], the first of its items' failures; [done] of [count] items were
         * done all the same, [summary] for books rebuilt among them.
         */
        data class Failed(
            val error: Throwable,
            val done: Int = 0,
            val count: Int = 1,
            override val gone: List<String> = emptyList(),
            val summary: Summary? = null
        ) : Outcome()
    }

    /**
     * Operation [id], of [kind] on [count] items, now on [name], item [index] (from 0), [done]
     * of [total] bytes of it (0 when it counts none); putting them [into] the folder so named,
     * if anywhere. Waiting its turn until [started] by [clock]; [stoppable] while canceling can
     * stop the step under way; finished once [outcome] is set. Its [id] is its place in the
     * queue, and among the operation stack's bubbles ([nextOrder]). A Convert job's [plan] is
     * the seconds each item is expected to take with the app in front ([ConvertEstimate]), and
     * its own speed is measured [since] the plan was set or the app came or went.
     */
    data class Status(
        val kind: Kind,
        val name: String,
        val started: Long?,
        val done: Long = 0,
        val total: Long = 0,
        val stoppable: Boolean = true,
        val outcome: Outcome? = null,
        val index: Int = 0,
        val count: Int = 1,
        val id: Long = 0,
        val into: String? = null,
        val plan: List<Double>? = null,
        val since: Since? = null
    ) {
        /** True until its turn comes. */
        val waiting: Boolean get() = started == null && outcome == null

        /** True while under way. */
        val running: Boolean get() = started != null && outcome == null

        /**
         * How far along it is, from 0 to 1: the items before the current one whole, and of
         * this one its bytes; null for a single item that counts none.
         */
        val fraction: Float?
            get() {
                val part = if (total > 0) done.coerceIn(0, total).toFloat() / total else null
                if (count <= 1) return part
                return ((index.coerceIn(0, count) + (part ?: 0f)) / count).coerceIn(0f, 1f)
            }

        /** The item its time left counts from: the current one, the only one when it counts none. */
        private val at: Int get() = if (count <= 1) 0 else index

        /** How far into the current item it is, from 0 to 1. */
        private val part: Double get() = if (total > 0) done.coerceIn(0, total).toDouble() / total else 0.0

        /** Where it stands at [now], the app [front] or not. */
        fun here(now: Long, front: Boolean) = Since(now, at, part, front)

        /**
         * Seconds left at [now] (by [clock]) while it runs to a [plan], the app [front] or away
         * (work then takes [slowdown] times as long), refined by how fast it went [since]
         * ([ConvertEstimate.remaining]); else null.
         */
        fun secondsLeft(now: Long, front: Boolean = FileWork.inFront(), slowdown: Double = FileWork.slowdown()): Double? {
            val planned = plan?.takeIf { it.isNotEmpty() } ?: return null
            val start = started?.takeIf { outcome == null } ?: return null
            val from = since ?: Since(start, 0, 0.0, front)
            fun pace(here: Boolean) = if (here) 1.0 else slowdown.coerceAtLeast(1.0)
            val elapsed = (now - from.at).coerceAtLeast(0) / 1000.0
            return ConvertEstimate.remaining(planned, at, part, elapsed, pace(front), from.index, from.part, pace(from.front))
        }
    }

    /** Where a job stood [at] (by [clock]): item [index], [part] of it done, the app [front] or not. */
    data class Since(val at: Long, val index: Int, val part: Double, val front: Boolean)

    /** What an operation's work hears from and tells the runner. */
    interface Progress {
        /** False once canceled: the work stops at its next piece. */
        val alive: Boolean

        fun report(done: Long, total: Long)

        /** Tells whether canceling can stop the step under way: not a server copying a file in one request. */
        fun stoppable(can: Boolean)

        /** Starts on item [name], [index] (from 0) of [count]: its progress counts from nothing. */
        fun item(name: String, index: Int, count: Int)

        /** Tells the seconds each item is expected to take ([Status.plan]). */
        fun plan(seconds: List<Double>) {}
    }

    private class Op(val id: Long, val count: Int, val unrun: () -> Int, val work: (Progress) -> Outcome) {
        var job: Job? = null
    }

    @Volatile
    private var state = MutableStateFlow<List<Status>>(emptyList())

    /** Operations waiting, the one under way, and those finished and not yet acknowledged, in the order they were started. */
    val operations: StateFlow<List<Status>> get() = state

    /** Where operations run; tests may stand in another. */
    @Volatile
    var dispatcher: CoroutineDispatcher = AppDispatchers.io

    /** True while the app is in front; work runs slower once it is not ([slowdown]). */
    @Volatile
    var inFront: () -> Boolean = { true }

    /** How many times longer work takes with the app away ([WorkRates.slowdown]). */
    @Volatile
    var slowdown: () -> Double = { 1.0 }

    /** Hears each operation start, to keep the app running while it does. */
    @Volatile
    var onStart: () -> Unit = {}

    /** Milliseconds for [Status.started] and for spacing progress reports. */
    @Volatile
    var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    private val waiting = ArrayDeque<Op>()
    private var running: Op? = null
    private val counter = AtomicLong()

    /** A number larger than any given before: ids of operations, and the order of bubbles. */
    fun nextOrder(): Long = counter.incrementAndGet()

    /** True while an operation waits or runs. */
    val busy: Boolean get() = state.value.any { it.outcome == null }

    /** The operation under way, if any. */
    val current: Status? get() = state.value.firstOrNull { it.running }

    /**
     * Queues [work] of [kind] on [count] items, the first [name], putting them [into] a folder
     * so named if anywhere, as the newest; it starts once those before it have ended. Work
     * that throws ends [Outcome.Canceled] or [Outcome.Failed] with nothing counted; [unrun]
     * runs in its place when it is canceled before it began, telling how many items it gave
     * back ([Outcome.Canceled.givenBack]). Returns the operation's id.
     */
    @Synchronized
    fun start(
        kind: Kind,
        name: String,
        count: Int = 1,
        into: String? = null,
        unrun: () -> Int = { 0 },
        work: (Progress) -> Outcome
    ): Long {
        val id = nextOrder()
        state.update { it + Status(kind, name, null, count = count, id = id, into = into) }
        waiting.addLast(Op(id, count, unrun, work))
        pump()
        onStart()
        return id
    }

    /** Starts the next operation waiting, unless one runs. */
    @Synchronized
    private fun pump() {
        if (running != null) return
        val op = waiting.removeFirstOrNull() ?: return
        running = op
        val flow = state
        edit(flow, op.id) { it.copy(started = clock()) }
        var outcome: Outcome? = null
        var ran = false
        val job = CoroutineScope(SupervisorJob() + dispatcher).launch {
            ran = true
            val context = coroutineContext
            var reported = 0L
            val progress = object : Progress {
                override val alive: Boolean get() = context.isActive

                override fun report(done: Long, total: Long) {
                    val now = clock()
                    if (now - reported < REPORT_MS && done < total) return
                    reported = now
                    edit(flow, op.id) { paced(it.copy(done = done, total = total), now) }
                }

                override fun stoppable(can: Boolean) {
                    edit(flow, op.id) { it.copy(stoppable = can) }
                }

                override fun item(name: String, index: Int, count: Int) {
                    reported = 0L
                    edit(flow, op.id) { paced(it.copy(name = name, index = index, count = count, done = 0, total = 0, stoppable = true), clock()) }
                }

                override fun plan(seconds: List<Double>) {
                    edit(flow, op.id) { it.copy(plan = seconds, since = it.here(clock(), inFront())) }
                }
            }
            outcome = try {
                op.work(progress)
            } catch (e: CancellationException) {
                Outcome.Canceled(0, op.count)
            } catch (e: Exception) {
                Outcome.Failed(e, 0, op.count)
            }
        }
        op.job = job
        // Also when canceled before it ever ran.
        job.invokeOnCompletion {
            val back = if (!ran && flow === state) op.unrun() else 0
            finish(flow, op, outcome ?: Outcome.Canceled(0, op.count, givenBack = back))
        }
    }

    @Synchronized
    private fun finish(flow: MutableStateFlow<List<Status>>, op: Op, outcome: Outcome) {
        if (flow !== state || running !== op) return
        edit(flow, op.id) { it.copy(outcome = outcome) }
        running = null
        pump()
    }

    /** [status] measuring its speed anew from [now] when the app came or went since it last did. */
    private fun paced(status: Status, now: Long): Status {
        val since = status.since ?: return status
        val front = inFront()
        return if (since.front == front) status else status.copy(since = status.here(now, front))
    }

    private fun edit(flow: MutableStateFlow<List<Status>>, id: Long, change: (Status) -> Status) {
        flow.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    /** Cancels operation [id]: one waiting ends at once, the one under way once it has cleaned up. */
    @Synchronized
    fun cancel(id: Long) {
        val queued = waiting.firstOrNull { it.id == id }
        if (queued != null) {
            waiting.remove(queued)
            val back = queued.unrun()
            edit(state, id) { it.copy(outcome = Outcome.Canceled(0, queued.count, givenBack = back)) }
            return
        }
        running?.takeIf { it.id == id }?.job?.cancel()
    }

    /** Cancels the operation under way; those waiting go on after it. */
    @Synchronized
    fun cancel() {
        running?.job?.cancel()
    }

    /** Forgets operation [id] once its outcome was shown; one not finished stays. */
    fun acknowledge(id: Long) {
        state.update { list -> list.filterNot { it.id == id && it.outcome != null } }
    }

    /** Starts afresh, leaving screens of earlier tests watching the old state; for tests. */
    @Synchronized
    internal fun reset() {
        val job = running?.job
        running = null
        waiting.clear()
        state = MutableStateFlow(emptyList())
        job?.cancel()
    }

    /** Thrown when the name an item would take is taken. */
    class NameTaken(name: String) : IOException("Already there: $name")

    private const val REPORT_MS = 100L
}
