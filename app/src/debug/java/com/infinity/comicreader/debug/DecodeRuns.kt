package com.infinity.comicreader.debug

/** Pure parts of [DecodeCheckService]: its decoder setups and which files each takes. */
internal object DecodeRuns {
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

    /** One setup: [engine] on [threads] threads pinned to [cores] (native page decodes; jxl-coder's cores only). */
    data class Setup(val engine: Engine, val threads: Int, val cores: ResampleRuns.Cores) {
        val key: String get() = when {
            engine == Engine.PAGE -> "${engine.key}/$threads/${cores.key}"
            engine == Engine.CODER && cores != ResampleRuns.Cores.ANY -> "${engine.key}/${cores.key}"
            else -> engine.key
        }

        /** True when it decodes files of [jxl] kind. */
        fun takes(jxl: Boolean): Boolean = engine.jxl == jxl

        companion object {
            /**
             * `engine[/threads[/cores]]`, as `page/4/big`, `page/8/any`, `coder` or `platform`, or
             * `coder/cores`, as `coder/big`; threads default to [threads], cores to any. Null when
             * unreadable.
             */
            fun of(text: String, threads: Int): Setup? {
                val parts = text.trim().split('/')
                val engine = Engine.of(parts[0]) ?: return null
                if (engine == Engine.CODER && parts.size == 2) return ResampleRuns.Cores.of(parts[1])?.let { Setup(engine, 1, it) }
                if (engine != Engine.PAGE) return if (parts.size == 1) Setup(engine, 1, ResampleRuns.Cores.ANY) else null
                val n = parts.getOrNull(1)?.toIntOrNull() ?: if (parts.size > 1) return null else threads
                val cores = parts.getOrNull(2)?.let { ResampleRuns.Cores.of(it) ?: return null } ?: ResampleRuns.Cores.ANY
                if (n !in 1..16) return null
                return Setup(engine, n, cores)
            }
        }
    }
}
