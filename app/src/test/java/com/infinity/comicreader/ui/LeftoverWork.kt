package com.infinity.comicreader.ui

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.infinity.comicreader.R
import com.infinity.comicreader.testing.idleUntil

/**
 * Waits until no screen left from the test still works in the background. Such work runs
 * on into the next test, where its stale context swaps the app's shared stores (Book IDs,
 * book facts) for this test's, closed by then.
 */
internal fun awaitLeftoverWork() = idleUntil("the screens' leftover work") { leftoverScreens().none { it.working() } }

/** The screens of this test not destroyed yet. */
private fun leftoverScreens(): List<Activity> {
    val monitor = ActivityLifecycleMonitorRegistry.getInstance()
    return listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
        .flatMap { monitor.getActivitiesInStage(it) }
}

/** True while [this] works in the background: a library screen listing, reading facts or making covers; a cleanup screen searching or making covers. */
private fun Activity.working(): Boolean = when (this) {
    is ListActivity -> {
        val started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        listJob?.isActive == true || listOf(classifyJob, presenceJob, moreLook).any { it?.isActive == true } ||
            (started && factsJob?.isActive == true) ||
            (findViewById<RecyclerView>(R.id.recycler)?.adapter as? BookAdapter)?.coversBusy == true
    }
    is CleanupActivity -> lifecycleScope.coroutineContext[kotlinx.coroutines.Job]!!.children.any { !it.isCompleted }
    else -> false
}
