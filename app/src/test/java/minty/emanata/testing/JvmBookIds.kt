package minty.emanata.testing

import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin

/**
 * Before each Robolectric test, starts Book IDs anew (`BookIds.reset`) and has them
 * hashed by [Xxh3Port], since the JVM cannot load the app's native xxHash.
 */
class JvmBookIds : TestEnvironmentLifecyclePlugin {
    override fun onSetupApplicationState() {
        val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        val ids = Class.forName("minty.emanata.data.BookIds", true, loader)
        val hash = Class.forName("minty.emanata.data.Xxh3", true, loader)
        val port = Class.forName(Xxh3Port::class.java.name, true, loader)
        val instance = ids.getField("INSTANCE").get(null)
        ids.getMethod("reset").invoke(instance)
        ids.getMethod("setHasher", hash).invoke(instance, port.getField("INSTANCE").get(null))
    }
}
