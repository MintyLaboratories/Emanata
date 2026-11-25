package minty.emanata.data

import android.content.Context

/**
 * Free and total bytes of locations as last read, with when, kept across runs, so the
 * location picker shows them at once and asks a share again only when a value is older
 * than [MAX_AGE_MS] (or when the share is opened).
 */
class LocationSpace(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Free and total bytes read at [time]. */
    data class Space(val free: Long, val total: Long, val time: Long)

    /** What was last read for the location at [path]; null when nothing was. */
    fun get(path: String): Space? {
        val parts = sp.getString(path, null)?.split(',') ?: return null
        if (parts.size != 3) return null
        val (free, total, time) = parts.map { it.toLongOrNull() ?: return null }
        return Space(free, total, time)
    }

    fun put(path: String, free: Long, total: Long) {
        sp.edit().putString(path, "$free,$total,${clock()}").apply()
    }

    fun remove(path: String) {
        sp.edit().remove(path).apply()
    }

    /** True when [space] is missing or older than [MAX_AGE_MS]. */
    fun stale(space: Space?): Boolean = space == null || clock() - space.time !in 0 until MAX_AGE_MS

    companion object {
        private const val FILE = "location_space"
        const val MAX_AGE_MS = 60L * 60 * 1000
    }
}
