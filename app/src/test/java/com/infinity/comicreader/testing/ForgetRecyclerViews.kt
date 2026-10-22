package com.infinity.comicreader.testing

import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin

/**
 * Before each Robolectric test, drops the main thread's RecyclerView prefetcher
 * (`GapWorker.sGapWorker`). It lists every RecyclerView attached on that thread
 * until detached, and tests never detach their activities' views, so on the
 * sandbox's long-lived main thread it would keep every earlier test's activities,
 * and their application, alive.
 */
class ForgetRecyclerViews : TestEnvironmentLifecyclePlugin {
    override fun onSetupApplicationState() {
        runCatching {
            val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
            val field = Class.forName("androidx.recyclerview.widget.GapWorker", false, loader).getDeclaredField("sGapWorker")
            field.isAccessible = true
            (field.get(null) as ThreadLocal<*>).remove()
        }
    }
}
