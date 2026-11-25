package minty.emanata.ai

import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import minty.emanata.net.NetPolicy
import minty.emanata.net.NetWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.CancellationException

/** A model's download as shown: running or waiting ([Running]), or stopped by a failure ([Failed]). */
sealed interface DownloadState {
    data class Running(val done: Long, val total: Long) : DownloadState {
        /** Percent done, 0..100. */
        val percent: Int get() = if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 100)
    }

    data class Failed(val failure: DownloadFailure) : DownloadState
}

/**
 * What [ModelDownloads] is doing: the [states] of downloads running, waiting or failed, by
 * model id, and a [generation] that grows whenever a model is installed or deleted, imported
 * ones too.
 */
data class Downloads(val states: Map<String, DownloadState> = emptyMap(), val generation: Int = 0)

/**
 * Model downloads asked for in the app, one at a time in the order asked, in the app's own
 * process: they go on when the screen that asked is left. A download cut short keeps its parts
 * and resumes when asked again. A finished one leaves [state], its model installed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object ModelDownloads {
    private const val TAG = "ModelDownloads"

    /** Least time between progress updates. */
    private const val PROGRESS_EVERY_MS = 250L

    private val _state = MutableStateFlow(Downloads())
    val state: StateFlow<Downloads> = _state

    /** Runs downloads one at a time; replaced by [reset]. */
    @Volatile
    private var scope = worker()
    private val jobs = HashMap<String, Job>()

    private fun worker() = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** The client downloads use unless given one; JVM tests stand in one that never reaches the network. */
    @Volatile
    internal var client: () -> OkHttpClient = { ModelDownloader.defaultClient }

    /**
     * Downloads [model] (a catalog model or another [FileSet]) into [store] unless it is
     * installed or already on its way. One nobody
     * asked for ([optional]: started or resumed by the app itself) is not started on a metered
     * connection when the user skips downloads there ([NetPolicy]): it shows as failed for that
     * ([DownloadFailure.METERED]) instead. Returns the download's job, the one on its way
     * included; null when none runs.
     */
    fun start(store: ModelStore, model: FileSet, client: OkHttpClient = this.client(), optional: Boolean = false): Job? {
        if (store.installed(model)) return null
        synchronized(jobs) {
            jobs[model.id]?.takeIf { it.isActive }?.let { return it }
            if (optional && !NetPolicy.may(NetWork.DOWNLOAD)) {
                set(model.id, DownloadState.Failed(DownloadFailure.METERED))
                return null
            }
            set(model.id, DownloadState.Running(store.bytesHeld(model), model.bytes))
            // The job's own entry is checked under the lock, which this block holds until it is stored.
            return scope.launch {
                val job = coroutineContext[Job]
                var last = 0L
                try {
                    ModelDownloader(store, client).download(model, canceled = { job?.isActive == false }) { done, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - last >= PROGRESS_EVERY_MS || done == total) {
                            last = now
                            synchronized(jobs) { if (jobs[model.id] === job) set(model.id, DownloadState.Running(done, total)) }
                        }
                    }
                    finish(model.id, job, null)
                } catch (e: CancellationException) {
                    finish(model.id, job, null, changed = false)
                } catch (e: DownloadException) {
                    Log.w(TAG, "${model.id}: ${e.message}")
                    finish(model.id, job, DownloadState.Failed(e.failure), changed = false)
                } catch (e: Exception) {
                    Log.w(TAG, "${model.id}: ${e.message}", e)
                    finish(model.id, job, DownloadState.Failed(DownloadFailure.NETWORK), changed = false)
                }
            }.also { jobs[model.id] = it }
        }
    }

    /** [id]'s download running or waiting; null when none is. */
    internal fun job(id: String): Job? = synchronized(jobs) { jobs[id]?.takeIf { it.isActive } }

    /**
     * Stops every download and forgets their states, and runs later ones on a worker of their
     * own, so none waits behind one stopped here that has yet to notice; for JVM tests, each
     * starting from none.
     */
    @VisibleForTesting
    fun reset() {
        synchronized(jobs) {
            scope.cancel()
            scope = worker()
            jobs.clear()
            _state.update { it.copy(states = emptyMap()) }
        }
    }

    /** Stops [id]'s download, keeping what arrived. */
    fun cancel(id: String) {
        synchronized(jobs) {
            jobs.remove(id)?.cancel()
            _state.update { it.copy(states = it.states - id) }
        }
    }

    /**
     * Stops [model]'s download at once (one still waiting never starts; one running stops at
     * its next chunk) and deletes it from [store] once it has stopped.
     */
    fun delete(store: ModelStore, model: FileSet) {
        val job = synchronized(jobs) {
            _state.update { it.copy(states = it.states - model.id) }
            jobs.remove(model.id)?.also { it.cancel() }
        }
        scope.launch {
            job?.join()
            store.delete(model)
            // A download asked for since then owns the state.
            _state.update { it.copy(generation = it.generation + 1) }
        }
    }

    /** True when [state] leaves the app free to start [id]'s download by itself: none failed, or one skipped as [DownloadFailure.METERED]. */
    fun mayStartBySelf(state: Downloads, id: String): Boolean = when (val s = state.states[id]) {
        null -> true
        is DownloadState.Failed -> s.failure == DownloadFailure.METERED
        else -> false
    }

    /** Says a model was installed or deleted some other way (imported ones): [Downloads.generation] grows. */
    fun changed() = _state.update { it.copy(generation = it.generation + 1) }

    private fun set(id: String, s: DownloadState) = _state.update { it.copy(states = it.states + (id to s)) }

    /**
     * Ends [job], [id]'s download: when it is still the one asked for, drops it and shows
     * [failed] (null: nothing); a job canceled or replaced since leaves the state to its
     * successor. [changed]: a model was installed.
     */
    private fun finish(id: String, job: Job?, failed: DownloadState?, changed: Boolean = true) = synchronized(jobs) {
        val own = jobs[id] === job
        if (own) jobs.remove(id)
        _state.update {
            Downloads(
                when {
                    !own -> it.states
                    failed == null -> it.states - id
                    else -> it.states + (id to failed)
                },
                if (changed) it.generation + 1 else it.generation
            )
        }
    }
}
