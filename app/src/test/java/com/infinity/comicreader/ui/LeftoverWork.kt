package com.infinity.comicreader.ui

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.infinity.comicreader.R
import com.infinity.comicreader.testing.idleUntil
import kotlinx.coroutines.Job

/**
 * Waits until no screen left from the test still works in the background. Such work runs
 * on into the next test, where its stale context swaps the app's shared stores (Book IDs,
 * book facts) for this test's, closed by then.
 */
internal fun awaitLeftoverWork() = idleUntil("the screens' leftover work") { leftoverScreens().none { it.working() } }

/**
 * The screens of this test, destroyed ones included while still reachable: canceling a
 * destroyed screen's jobs does not stop the blocking I/O they run, and a job ends only once
 * that returns.
 */
private fun leftoverScreens(): List<Activity> {
    val monitor = ActivityLifecycleMonitorRegistry.getInstance()
    return Stage.values().flatMap { monitor.getActivitiesInStage(it) }
}

/** True while [this] works in the background: a library screen listing, reading facts, planning file work or making covers; a cleanup screen searching or making covers. */
private fun Activity.working(): Boolean = when (this) {
    is ListActivity -> {
        val busy = if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
            // All canceled with the screen: each still runs until its blocking step returns.
            lifecycleScope.coroutineContext[Job]!!.children.any { !it.isCompleted }
        } else {
            // Its scope also holds collectors that run as long as it lives, so only its work counts,
            // a job told to stop included until it has. Its facts job waits, not works, while held
            // at its started gate with the screen out of sight.
            val started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            listOf(listJob, classifyJob, presenceJob, moreLook, markJob, factsLoad).any { it?.isCompleted == false } ||
                (factsJob?.isCompleted == false && (started || !factsAwaitingStart)) ||
                (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED) && fileActions.busy)
        }
        busy || (findViewById<RecyclerView>(R.id.recycler)?.adapter as? BookAdapter)?.coversBusy == true
    }
    is CleanupActivity -> lifecycleScope.coroutineContext[Job]!!.children.any { !it.isCompleted }
    else -> false
}
