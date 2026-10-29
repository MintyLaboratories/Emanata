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

/**
 * The one file operation under way, run in the app's process rather than in a screen,
 * so turning the device does not stop it, and [onStart] keeps the app running meanwhile
 * ([FileWorkService]). Whichever library screen is there shows its [status], and
 * [acknowledge]s the outcome once it has shown it.
 */
object FileWork {
    enum class Kind { COPY, MOVE, DELETE, RENAME }

    sealed class Outcome {
        /**
         * Finished with [count] items: [path] is the item to show, if any; [gone] what is no
         * longer where it was (deleted, or moved away); [sourceLeft] when a move could not
         * delete a source.
         */
        data class Done(
            val path: String?,
            val sourceLeft: Boolean = false,
            val gone: List<String> = emptyList(),
            val count: Int = 1
        ) : Outcome()

        object Canceled : Outcome()

        data class Failed(val error: Throwable) : Outcome()
    }

    /**
     * An operation on [name], item [index] (from 0) of [count], [done] of [total] bytes of
     * it (0 when it counts none), [started] by [clock]; [stoppable] while canceling can stop
     * the step under way; finished once [outcome] is set.
     */
    data class Status(
        val kind: Kind,
        val name: String,
        val started: Long,
        val done: Long = 0,
        val total: Long = 0,
        val stoppable: Boolean = true,
        val outcome: Outcome? = null,
        val index: Int = 0,
        val count: Int = 1
    )

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

    @Volatile
    private var state = MutableStateFlow<Status?>(null)

    /** The operation under way, or finished and not yet acknowledged; null when there is none. */
    val status: StateFlow<Status?> get() = state

    /** Where operations run; tests may stand in another. */
    @Volatile
    var dispatcher: CoroutineDispatcher = AppDispatchers.io

    /** Hears each operation start, to keep the app running while it does. */
    @Volatile
    var onStart: () -> Unit = {}

    /** Milliseconds for [Status.started] and for spacing progress reports. */
    @Volatile
    var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    private var job: Job? = null

    /** True while an operation runs. */
    val busy: Boolean get() = state.value?.let { it.outcome == null } == true

    /** Starts [work] on [name]; false, starting nothing, while another runs. */
    @Synchronized
    fun start(kind: Kind, name: String, work: (Progress) -> Outcome.Done): Boolean {
        if (busy) return false
        val flow = state
        flow.value = Status(kind, name, clock())
        job = CoroutineScope(SupervisorJob() + dispatcher).launch {
            val context = coroutineContext
            var reported = 0L
            val progress = object : Progress {
                override val alive: Boolean get() = context.isActive

                override fun report(done: Long, total: Long) {
                    val now = clock()
                    if (now - reported < REPORT_MS && done < total) return
                    reported = now
                    flow.update { it?.copy(done = done, total = total) }
                }

                override fun stoppable(can: Boolean) {
                    flow.update { it?.copy(stoppable = can) }
                }

                override fun item(name: String, index: Int, count: Int) {
                    reported = 0L
                    flow.update { it?.copy(name = name, index = index, count = count, done = 0, total = 0, stoppable = true) }
                }
            }
            val outcome = try {
                work(progress)
            } catch (e: CancellationException) {
                Outcome.Canceled
            } catch (e: Exception) {
                Outcome.Failed(e)
            }
            flow.update { it?.copy(outcome = outcome) }
        }
        onStart()
        return true
    }

    /** Cancels the operation under way; its outcome follows once it has cleaned up. */
    fun cancel() {
        job?.cancel()
    }

    /** Forgets a finished operation once its outcome was shown. */
    fun acknowledge() {
        state.update { if (it?.outcome != null) null else it }
    }

    /** Starts afresh, leaving screens of earlier tests watching the old state; for tests. */
    @Synchronized
    internal fun reset() {
        job?.cancel()
        job = null
        state = MutableStateFlow(null)
    }

    /** Thrown when the name an item would take is taken. */
    class NameTaken(name: String) : IOException("Already there: $name")

    private const val REPORT_MS = 100L
}
