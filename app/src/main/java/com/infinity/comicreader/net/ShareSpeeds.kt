package com.infinity.comicreader.net

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap

/**
 * Each share's speed: what its reads measured ([SpeedMeter], fed by [Remotes]) and whether it
 * counts as slow, as its setting says ([Share.speed]: Auto goes by the measure). The latest
 * estimate is kept on disk, at most once every [SAVE_EVERY_MS] per share, so Auto knows a slow
 * share from the start; it is forgotten when the share is removed or reached another way.
 */
object ShareSpeeds {
    private val meters = ConcurrentHashMap<String, SpeedMeter>()
    private val savedAt = ConcurrentHashMap<String, Long>()

    @Volatile
    private var store: SharedPreferences? = null

    /** The clock reads are timed by, in milliseconds. */
    @Volatile
    @set:VisibleForTesting
    var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /** Keeps estimates in [context]'s app from now on. */
    fun init(context: Context) {
        store = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        meters.clear()
    }

    /** Share [id]'s meter, seeded with the estimate kept on disk. */
    fun meter(id: String): SpeedMeter = meters.getOrPut(id) { SpeedMeter({ clock() }, load(id)) }

    /** What share [id]'s reads measured; null before any. */
    fun estimate(id: String): SpeedMeter.Estimate? = meter(id).estimate()

    /** True when share [id] counts as slow: set so, or measured so while Auto. */
    fun slow(id: String): Boolean = when (Remotes.share(id)?.speed) {
        ShareSpeed.FAST -> false
        ShareSpeed.SLOW -> true
        else -> meter(id).slow()
    }

    /** Keeps share [id]'s estimate on disk once its own reads give one, unless kept less than [SAVE_EVERY_MS] ago. */
    internal fun measured(id: String) {
        val sp = store ?: return
        val now = clock()
        val last = savedAt[id]
        if (last != null && now - last < SAVE_EVERY_MS) return
        val meter = meters[id]?.takeIf { it.measured() } ?: return
        val e = meter.estimate() ?: return
        savedAt[id] = now
        sp.edit().putString(id, "${e.bytesPerSecond ?: ""},${e.firstByteMs ?: ""}").apply()
    }

    private fun load(id: String): SpeedMeter.Estimate? {
        val raw = store?.getString(id, null) ?: return null
        val parts = raw.split(',')
        val rate = parts.getOrNull(0)?.toLongOrNull()
        val first = parts.getOrNull(1)?.toLongOrNull()
        return if (rate == null && first == null) null else SpeedMeter.Estimate(rate, first)
    }

    /** Forgets what share [id] measured, on disk too. */
    fun forget(id: String) {
        meters.remove(id)
        savedAt.remove(id)
        store?.edit()?.remove(id)?.apply()
    }

    /** Forgets every share's measure in memory; for tests. */
    @VisibleForTesting
    fun reset() {
        meters.clear()
        savedAt.clear()
        store = null
    }

    private const val FILE = "share_speeds"

    /** Least time between two saves of one share's estimate. */
    const val SAVE_EVERY_MS = 30_000L
}
