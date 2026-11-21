package com.infinity.comicreader.testing

import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin

/**
 * Before each Robolectric test, drops the main thread's animation clock
 * (`AnimationUtils.sAnimationState`). It keeps the last time it reported, and
 * reports no earlier time while a frame runs; Robolectric sets the system clock
 * back for each test but keeps that thread, so a scroller started in a frame
 * would otherwise start at an earlier test's last time, wait for the clock to
 * get there, and draw frames, each advancing the clock, until it does.
 */
class ResetAnimationClock : TestEnvironmentLifecyclePlugin {
    override fun onSetupApplicationState() {
        runCatching {
            val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
            val field = Class.forName("android.view.animation.AnimationUtils", false, loader).getDeclaredField("sAnimationState")
            field.isAccessible = true
            (field.get(null) as ThreadLocal<*>).remove()
        }
    }
}
