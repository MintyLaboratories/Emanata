package com.infinity.comicreader.reader

import com.infinity.comicreader.compact.LibJxl

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
 * Where pages the platform decodes (JPEG, PNG, WebP, HEIF: one thread each) run: the
 * decoding thread is pinned to [cores] for the decode, then put back.
 */
object PlatformPages {
    /** The cores platform decodes run on. */
    @Volatile
    var cores: CoreSet = CoreSet.ANY

    internal var device: CpuCores? = null
        get() = field ?: CpuCores.device

    /** Pins this thread to a mask and returns the mask it had, 0 when refused; null where native code is missing. */
    internal var pin: ((Long) -> Long)? = null
        get() = field ?: loaded

    private val loaded: ((Long) -> Long)? by lazy { runCatching { LibJxl.version; { m: Long -> LibJxl.pin(m) } }.getOrNull() }

    /** Runs [block] on [cores]; as is where they are any, unknown or cannot be pinned to. */
    fun <T> placed(block: () -> T): T {
        val mask = cores.mask(device!!)
        val p = if (mask == 0L) null else pin
        val kept = p?.invoke(mask) ?: 0L
        try {
            return block()
        } finally {
            if (kept != 0L) p?.invoke(kept)
        }
    }
}
