package com.infinity.comicreader.ai

import android.content.Context
import android.os.SystemClock

/**
 * The covers' text detector's model ([ModelCatalog.TEXT_DETECTOR]), downloaded when first
 * needed. [ready] asks for it while it is missing, as optional work the network policy may hold
 * back ([start] with `optional`): never while a download is on its way, at most once per
 * [RETRY_MS], and not again this session after [TRIES] downloads that did not get it. [fetch]
 * asks for it as the user's own request, whatever the policy and the tries. Once it is in place
 * after a cover went without it, [arrived] is told, once.
 */
class DetectorModel(
    /** True once the model's files are in place; reads storage. */
    private val installed: () -> Boolean,
    /** Starts the download, [optional] or asked for, and calls `done` with whether the model is then in place. */
    private val start: (optional: Boolean, done: (Boolean) -> Unit) -> Unit,
    private val arrived: () -> Unit,
    /** Milliseconds on a monotonic clock. */
    private val now: () -> Long = { SystemClock.elapsedRealtime() }
) {
    @Volatile
    private var present = false

    /** A cover went without the model since it was last known to be in place. */
    private var missed = false

    /** A download is on its way. */
    private var running = false

    /** When the last optional download was asked for ([now]); null before the first. */
    private var askedAt: Long? = null

    /** Downloads this session that ended without the model. */
    private var failed = 0

    /** True once the model is in place; else asks for it as optional work, and false. Quick once true. */
    fun ready(): Boolean {
        if (present) return true
        if (installed()) {
            settle()
            return true
        }
        synchronized(this) { missed = true }
        request(optional = true)
        return false
    }

    /** Downloads the model as the user asked: whatever the network policy says, and however often it failed. */
    fun fetch() = request(optional = false)

    private fun request(optional: Boolean) {
        synchronized(this) {
            if (running || present) return
            val t = now()
            if (optional && (failed >= TRIES || askedAt?.let { t - it < RETRY_MS } == true)) return
            if (optional) askedAt = t
            running = true
        }
        try {
            start(optional, ::done)
        } catch (e: Exception) {
            done(false)
        }
    }

    /** Ends a download: the model in place when [ok]. */
    private fun done(ok: Boolean) {
        synchronized(this) {
            running = false
            if (!ok) failed++
        }
        if (ok) settle()
    }

    /** The model is in place: tells [arrived] when a cover went without it. */
    private fun settle() {
        val tell = synchronized(this) {
            val t = !present && missed
            present = true
            missed = false
            t
        }
        if (tell) arrived()
    }

    companion object {
        /** Least time between downloads the app starts by itself. */
        const val RETRY_MS = 15 * 60_000L

        /** Downloads ending without the model after which the app stops asking by itself until it starts again. */
        const val TRIES = 3

        @Volatile
        private var shared: DetectorModel? = null

        /** The app's: downloads through [ModelDownloads] into [ModelStore.of], and tells [CoverTextDetectors.shared] of the arrival. */
        fun shared(context: Context): DetectorModel = shared ?: synchronized(this) {
            shared ?: run {
                val app = context.applicationContext
                val store = ModelStore.of(app)
                val set = ModelCatalog.TEXT_DETECTOR
                DetectorModel(
                    installed = { store.installed(set) },
                    start = { optional, done ->
                        val job = ModelDownloads.start(store, set, optional = optional)
                        if (job == null) done(store.installed(set)) else job.invokeOnCompletion { done(store.installed(set)) }
                    },
                    arrived = { CoverTextDetectors.shared(app).modelArrived() }
                ).also { shared = it }
            }
        }
    }
}
