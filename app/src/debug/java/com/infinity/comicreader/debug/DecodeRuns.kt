package com.infinity.comicreader.debug

/** Pure parts of [DecodeCheckService]: its decoder setups, which files each takes, and how many it holds. */
internal object DecodeRuns {
    /** How many of files [sizes] long, taken in order, fit in [budget] bytes; at least one. */
    fun fitting(sizes: List<Long>, budget: Long): Int {
        var held = 0L
        for ((i, n) in sizes.withIndex()) {
            if (i > 0 && held + n > budget) return i
            held += n
        }
        return sizes.size
    }

    /** True when `sustain` measures the group named [group]: all but the synthetic 2000x3000 pages (decode energy uses 4K pages). */
    fun sustained(group: String): Boolean = !group.startsWith("synthetic-2k")

    /** Where the reader's JPEG XL threads run: the large cores when [pinned], else anywhere. */
    fun jxlCores(pinned: Boolean): ResampleRuns.Cores = if (pinned) ResampleRuns.Cores.BIG else ResampleRuns.Cores.ANY

    /**
     * The setups timed when none are asked, each once: jxl-coder, the reader's native JPEG XL
     * setup ([threads], pinned when [pinned]), one thread per large core pinned there
     * ([bigThreads]; 0 where there are no large cores), 8 threads anywhere, and the platform.
     */
    fun defaultSetups(threads: Int, pinned: Boolean, bigThreads: Int): List<Setup> = listOfNotNull(
        Setup(Engine.CODER, 1, ResampleRuns.Cores.ANY), Setup(Engine.PAGE, threads, jxlCores(pinned)),
        bigThreads.takeIf { it > 0 }?.let { Setup(Engine.PAGE, it, ResampleRuns.Cores.BIG) },
        Setup(Engine.PAGE, 8, ResampleRuns.Cores.ANY), Setup(Engine.PLATFORM, 1, ResampleRuns.Cores.ANY)
    ).distinct()

    /** A page decoder under test. */
    enum class Engine(val key: String, val jxl: Boolean) {
        /** jxl-coder's own decode, as JPEG XL pages decoded before; on cores, its threads start pinned there. */
        CODER("coder", true),

        /** Native decode into the bitmap ([com.infinity.comicreader.reader.JxlPages]) with jxl-coder's libjxl. */
        PAGE("page", true),

        /** [android.graphics.ImageDecoder] into software pixels, as other pages decode. */
        PLATFORM("platform", false),

        /** [android.graphics.BitmapFactory]. */
        FACTORY("factory", false);

        companion object {
            fun of(key: String): Engine? = entries.firstOrNull { it.key == key }
        }
    }

    /** One setup: [engine] on [threads] threads (native page decodes only) pinned to [cores]. */
    data class Setup(val engine: Engine, val threads: Int, val cores: ResampleRuns.Cores) {
        val key: String get() = when {
            engine == Engine.PAGE -> "${engine.key}/$threads/${cores.key}"
            cores != ResampleRuns.Cores.ANY -> "${engine.key}/${cores.key}"
            else -> engine.key
        }

        /** True when it decodes files of [jxl] kind. */
        fun takes(jxl: Boolean): Boolean = engine.jxl == jxl

        companion object {
            /**
             * `engine[/threads[/cores]]`, as `page/4/big`, `page/8/any`, `coder` or `platform`, or
             * `engine/cores` for the others, as `coder/big` or `platform/prime`; threads default to
             * [threads], cores to any. Null when unreadable.
             */
            fun of(text: String, threads: Int): Setup? {
                val parts = text.trim().split('/')
                val engine = Engine.of(parts[0]) ?: return null
                if (engine != Engine.PAGE && parts.size == 2) return ResampleRuns.Cores.of(parts[1])?.let { Setup(engine, 1, it) }
                if (engine != Engine.PAGE) return if (parts.size == 1) Setup(engine, 1, ResampleRuns.Cores.ANY) else null
                val n = parts.getOrNull(1)?.toIntOrNull() ?: if (parts.size > 1) return null else threads
                val cores = parts.getOrNull(2)?.let { ResampleRuns.Cores.of(it) ?: return null } ?: ResampleRuns.Cores.ANY
                if (n !in 1..16) return null
                return Setup(engine, n, cores)
            }
        }
    }
}
