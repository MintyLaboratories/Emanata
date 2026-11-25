package minty.emanata.testing

import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin

/**
 * Before each Robolectric test, drops Material's snackbar queue
 * (`SnackbarManager.snackbarManager`). It outlives the test that showed a
 * snackbar, so an earlier test's snackbar, never dismissed, would stay current
 * and make the next one wait for its dismissal, which a test that does not move
 * the clock on never sees.
 */
class ForgetSnackbars : TestEnvironmentLifecyclePlugin {
    override fun onSetupApplicationState() {
        runCatching {
            val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
            val field = Class.forName("com.google.android.material.snackbar.SnackbarManager", false, loader).getDeclaredField("snackbarManager")
            field.isAccessible = true
            field.set(null, null)
        }
    }
}
