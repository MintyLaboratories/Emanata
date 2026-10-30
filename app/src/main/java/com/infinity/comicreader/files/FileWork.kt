package com.infinity.comicreader.files

import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/**
 * The queue of file operations (copy, move, delete, rename): one at a time, never two
 * at once, each in turn as it was started. They run in the app's process rather than in
 * a screen, so turning the device does not stop them, and [onStart] keeps the app running
 * meanwhile ([FileWorkService]). Whichever library screen is there shows their [operations],
 * and [acknowledge]s each outcome once it has shown it.
 */
object FileWork {
    enum class Kind { COPY, MOVE, DELETE, RENAME }

    sealed class Outcome {
        /** What is no longer where it was: deleted, or moved away. */
        abstract val gone: List<String>

        /**
         * Finished with [count] items: [path] is the item to show, if any; [sourceLeft] when a
         * move could not delete a source.
         */
        data class Done(
            val path: String?,
            val sourceLeft: Boolean = false,
            override val gone: List<String> = emptyList(),
            val count: Int = 1
        ) : Outcome()

        /** Canceled after [done] of [count] items. */
        data class Canceled(val done: Int = 0, val count: Int = 1, override val gone: List<String> = emptyList()) : Outcome()

        /** Failed with [error], the first of its items' failures; [done] of [count] items were done all the same. */
        data class Failed(
            val error: Throwable,
            val done: Int = 0,
            val count: Int = 1,
            override val gone: List<String> = emptyList()
        ) : Outcome()
    }

    /**
     * Operation [id], of [kind] on [count] items, now on [name], item [index] (from 0), [done]
     * of [total] bytes of it (0 when it counts none); putting them [into] the folder so named,
     * if anywhere. Waiting its turn until [started] by [clock]; [stoppable] while canceling can
     * stop the step under way; finished once [outcome] is set. Its [id] is its place in the
     * queue, and among the operation stack's bubbles ([nextOrder]).
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
        val into: String? = null
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
    }

    /** What an operation's work hears from and tells the runner. */
    interface Progress {
        /** False once canceled: the work stops at its next piece. */
        val alive: Boolean

        fun report(done: Long, total: Long)

        /** Tells whether canceling can stop the step under way: not a server copying a file in one request. */
        fun stoppable(can: Boolean)

        /** Starts on item [name], [index] (from 0) of [count]: its progress counts from nothing. */
        fun item(name: String, index: Int, count: Int)
    }

    private class Op(val id: Long, val count: Int, val unrun: () -> Unit, val work: (Progress) -> Outcome) {
        var job: Job? = null
    }

    @Volatile
    private var state = MutableStateFlow<List<Status>>(emptyList())

    /** Operations waiting, the one under way, and those finished and not yet acknowledged, in the order they were started. */
    val operations: StateFlow<List<Status>> get() = state

    /** Where operations run; tests may stand in another. */
    @Volatile
    var dispatcher: CoroutineDispatcher = AppDispatchers.io

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
     * runs in its place when it is canceled before it began. Returns the operation's id.
     */
    @Synchronized
    fun start(
        kind: Kind,
        name: String,
        count: Int = 1,
        into: String? = null,
        unrun: () -> Unit = {},
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
                    edit(flow, op.id) { it.copy(done = done, total = total) }
                }

                override fun stoppable(can: Boolean) {
                    edit(flow, op.id) { it.copy(stoppable = can) }
                }

                override fun item(name: String, index: Int, count: Int) {
                    reported = 0L
                    edit(flow, op.id) { it.copy(name = name, index = index, count = count, done = 0, total = 0, stoppable = true) }
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
            if (!ran && flow === state) op.unrun()
            finish(flow, op, outcome ?: Outcome.Canceled(0, op.count))
        }
    }

    @Synchronized
    private fun finish(flow: MutableStateFlow<List<Status>>, op: Op, outcome: Outcome) {
        if (flow !== state || running !== op) return
        edit(flow, op.id) { it.copy(outcome = outcome) }
        running = null
        pump()
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
            queued.unrun()
            edit(state, id) { it.copy(outcome = Outcome.Canceled(0, queued.count)) }
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
