package com.infinity.comicreader.reader

/** A set of cores a thread can be pinned to ([CpuCores]); [ANY] leaves placement to the system. */
enum class CoreSet(val key: String) {
    ANY("any"),
    BIG("big"),
    PRIME("prime");

    /** Its mask on [cores] (bit n = core n); 0 = any. */
    fun mask(cores: CpuCores): Long = when (this) {
        ANY -> 0L
        BIG -> cores.big
        PRIME -> cores.prime
    }

    companion object {
        fun of(key: String?): CoreSet? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Where pages the platform decodes (JPEG, PNG, WebP, HEIF: one thread each) run: a decode
 * the reader waits on ([Urgency.waited]) has its thread pinned to [cores], then put back.
 * Left to the system, such a decode often starts on a little core and stays there long
 * enough to be slower than on a large one. Other decodes (pages read ahead, covers,
 * previews, scans) stay with the system, which keeps the large cores free for the page
 * waited on and the drawing.
 */
object PlatformPages {
    /** The cores platform decodes run on: the large ones ([CpuCores.big]; none to pin to where every core is large). */
    @Volatile
    var cores: CoreSet = CoreSet.BIG

    internal var device: CpuCores? = null
        get() = field ?: CpuCores.device

    /** Pins this thread to a mask and returns the mask it had, 0 when refused; null where native code is missing. */
    internal var pin: ((Long) -> Long)? = null
        get() = field ?: loaded

    /** The resampler's library, which pages load anyway: pinning loads nothing more. */
    private val loaded: ((Long) -> Long)? by lazy { if (LibPixels.loaded) { m: Long -> LibPixels.pin(m) } else null }

    /** Runs [block] on [cores] when [waited]; as is otherwise, or where they are any, unknown or cannot be pinned to. */
    fun <T> placed(waited: Boolean = Urgency.waited(), block: () -> T): T {
        val mask = if (waited) cores.mask(device!!) else 0L
        val p = if (mask == 0L) null else pin
        val kept = p?.invoke(mask) ?: 0L
        try {
            return block()
        } finally {
            if (kept != 0L) p?.invoke(kept)
        }
    }
}
