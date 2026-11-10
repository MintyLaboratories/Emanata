package com.infinity.comicreader.ai

/**
 * Google Play services as the ML Kit text detector needs it: there or not, and ML Kit's text
 * recognition module, which Play services downloads. [GmsPlayServices] on devices; tests stand
 * in their own.
 */
interface PlayServices {
    /** True when Play services is installed, enabled and recent enough. Quick: fine on the main thread. */
    fun available(): Boolean

    /**
     * True when the text recognition module is on the device, false when not; null when Play
     * services did not tell (an interrupted wait leaves the thread interrupted). Blocking.
     */
    fun moduleInstalled(): Boolean?

    /**
     * Asks Play services to download the text recognition module; [done] is called once, on any
     * thread, with true once it is installed and false when the download fails or is canceled.
     * Returns what cancels the request (then [done] says false), if it is still under way.
     */
    fun requestModule(done: (Boolean) -> Unit): () -> Unit
}

/** [real], except that it is never [available] while [absent] says so: a debug flag that simulates a device without Play services. */
class WithoutPlayServices(private val real: PlayServices, private val absent: () -> Boolean) : PlayServices {
    override fun available(): Boolean = !absent() && real.available()

    override fun moduleInstalled(): Boolean? = if (absent()) null else real.moduleInstalled()

    override fun requestModule(done: (Boolean) -> Unit): () -> Unit {
        if (!absent()) return real.requestModule(done)
        done(false)
        return {}
    }
}
