package com.infinity.comicreader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * The startup folder's share being waited for ([ListActivity]), kept across a recreate
 * such as a rotation, so the wait goes on instead of starting over: the folder, its
 * listing under way, since when, and whether "Wait" was chosen. Then, once given up
 * on, the share still asked in the background ([behind]).
 */
class StartupWait : ViewModel() {
    var dir: RemoteFile? = null
        private set

    var attempt: Deferred<Result<List<RemoteFile>>>? = null
        private set

    /** When the wait started, in [android.os.SystemClock.uptimeMillis]. */
    var since = 0L
        private set

    /** "Wait" was chosen: not asked again. */
    var waitChosen = false

    /** Starts waiting at [now] for [dir], listed by [list] off the main thread, outliving the screen. */
    fun start(dir: RemoteFile, now: Long, list: suspend () -> Result<List<RemoteFile>>): Deferred<Result<List<RemoteFile>>> {
        this.dir = dir
        since = now
        waitChosen = false
        return viewModelScope.async(AppDispatchers.io) { list() }.also { attempt = it }
    }

    /** The startup share given up on, asked again while the folder opened instead is browsed; null when none is. */
    var behind: Behind? = null

    /**
     * Startup folder [dir], given up on for [fallback]: its first listing [attempt], the
     * [answer] last taken (null until [attempt]'s), and how long to [wait] before asking again.
     */
    class Behind(val dir: RemoteFile, val attempt: Deferred<Result<List<RemoteFile>>>, val fallback: String, var wait: Long) {
        var answer: Result<List<RemoteFile>>? = null
    }

    /** Done waiting: the next startup waits afresh. The listing goes on for whoever holds it. */
    fun end() {
        dir = null
        attempt = null
        since = 0L
        waitChosen = false
    }
}
