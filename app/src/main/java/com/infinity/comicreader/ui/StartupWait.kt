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
 * listing under way, since when, and whether "Wait" was chosen.
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

    /** Done waiting: the next startup waits afresh. The listing goes on for whoever holds it. */
    fun end() {
        dir = null
        attempt = null
        since = 0L
        waitChosen = false
    }
}
