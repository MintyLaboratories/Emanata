package com.infinity.comicreader.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicReference

/**
 * Whether the connection costs by the byte, asked through [NetPolicy] before any network work
 * nobody asked for. Mobile data always counts as metered.
 */
object Metered {
    /** What the active network says of itself. */
    data class Link(
        val connected: Boolean,
        val notMetered: Boolean,
        val activeMetered: Boolean,
        val vpn: Boolean = false,
        val cellular: Boolean = false
    )

    enum class Verdict { UNMETERED, METERED, OFFLINE }

    /**
     * [link] judged: metered when the network lacks [NetworkCapabilities.NET_CAPABILITY_NOT_METERED],
     * when Android calls the active network metered (a VPN over mobile data inherits it), or when it
     * carries mobile data; offline without one.
     */
    fun verdict(link: Link?): Verdict = when {
        link == null || !link.connected -> Verdict.OFFLINE
        !link.notMetered || link.activeMetered || link.cellular -> Verdict.METERED
        else -> Verdict.UNMETERED
    }

    /** The active network as Android describes it; unmetered until [init] (JVM tests). */
    @Volatile
    @set:VisibleForTesting
    var probe: () -> Link? = { Link(connected = true, notMetered = true, activeMetered = false) }

    /** The clock answers are kept by, in milliseconds. */
    @Volatile
    @set:VisibleForTesting
    var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /** How long an answer is kept: callers ask per read, and the network rarely changes faster. */
    private const val KEEP_MS = 1_000L

    /** The [verdict] judged at [at], if any; [generation] counts the times answers were let go. */
    private class Kept(val generation: Int, val at: Long, val verdict: Verdict?)

    private val kept = AtomicReference(Kept(0, 0, null))

    /** Lets go of the kept answer, and of every answer still being asked for. */
    private fun drop() {
        kept.updateAndGet { Kept(it.generation + 1, 0, null) }
    }

    /** Asks [context]'s connectivity service from now on, and hears its network change ([NetPolicy.changed]). */
    fun init(context: Context) {
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        probe = { linkOf(connectivity) }
        drop()
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = recheck()

                override fun onLost(network: Network) = recheck()
            })
        }
    }

    /** The verdict last told of by [recheck]. */
    @Volatile
    private var heard: Verdict? = null

    /** Asks again after the network changed; a new verdict tells [NetPolicy]'s listeners. */
    @VisibleForTesting
    fun recheck() {
        drop()
        val verdict = now()
        if (verdict == heard) return
        heard = verdict
        NetPolicy.changed()
    }

    /** Asks Android about its active network; null without one. */
    private fun linkOf(connectivity: ConnectivityManager): Link? = runCatching {
        val network = connectivity.activeNetwork ?: return@runCatching null
        val caps = connectivity.getNetworkCapabilities(network) ?: return@runCatching null
        Link(
            connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            activeMetered = connectivity.isActiveNetworkMetered,
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        )
    }.getOrNull()

    /** The connection now, as judged at most [KEEP_MS] ago; an answer let go of while asked for is not kept. */
    fun now(): Verdict {
        val at = clock()
        val seen = kept.get()
        seen.verdict?.let { if (at - seen.at in 0 until KEEP_MS) return it }
        return verdict(probe()).also { kept.compareAndSet(seen, Kept(seen.generation, at, it)) }
    }

    /** Forgets the kept answer, so the next [now] asks again. */
    @VisibleForTesting
    fun forget() {
        drop()
    }
}
