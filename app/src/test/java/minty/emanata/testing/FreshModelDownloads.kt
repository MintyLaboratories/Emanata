package minty.emanata.testing

import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin

/**
 * Before each Robolectric test, stops every model download and starts the app's download
 * worker anew (`ModelDownloads.reset`): a test's downloads never wait behind those earlier
 * tests started, and no earlier state shows in its screens.
 */
class FreshModelDownloads : TestEnvironmentLifecyclePlugin {
    override fun onSetupApplicationState() {
        val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        val downloads = Class.forName("minty.emanata.ai.ModelDownloads", true, loader)
        downloads.getMethod("reset").invoke(downloads.getField("INSTANCE").get(null))
    }
}
