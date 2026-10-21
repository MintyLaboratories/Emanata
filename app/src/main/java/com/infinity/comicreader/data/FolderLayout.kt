package com.infinity.comicreader.data

import org.json.JSONObject

/**
 * How the reader lays pages out: [mode] (paged, vertical or long strip), reading
 * direction [dir] ("ltr" or "rtl"), [fit], [twoPage], [autoCrop] and the page gap
 * [spacing] in dp. Mode and direction stay separate fields, so a later paged /
 * continuous × direction split only changes their values.
 */
data class ReaderLayout(
    val mode: String = MODE_PAGED,
    val dir: String = "ltr",
    val fit: String = "screen",
    val twoPage: TwoPage = TwoPage.OFF,
    val autoCrop: Boolean = false,
    val spacing: Int = 0
) {
    companion object {
        const val MODE_PAGED = "paged"
        const val MODE_VERTICAL = "vertical"
        const val MODE_LONGSTRIP = "longstrip"
        val MODES = listOf(MODE_PAGED, MODE_VERTICAL, MODE_LONGSTRIP)

        /** The global defaults in [p]. */
        fun of(p: Prefs) = ReaderLayout(p.pageMode, p.readingDir, p.fit, p.twoPage, p.autoCrop, p.pageSpacing)
    }
}

/**
 * What the reader keeps for one folder: its reading direction alone ([Direction], set by
 * choosing one), or its whole layout ([Frozen], "Apply to: This folder").
 */
sealed class FolderLayout {
    abstract val dir: String

    /** The layout a book in the folder shows, given the global [defaults]. */
    abstract fun over(defaults: ReaderLayout): ReaderLayout

    data class Direction(override val dir: String) : FolderLayout() {
        override fun over(defaults: ReaderLayout) = defaults.copy(dir = dir)
    }

    data class Frozen(val layout: ReaderLayout) : FolderLayout() {
        override val dir: String get() = layout.dir

        override fun over(defaults: ReaderLayout) = layout
    }

    fun encode(): JSONObject = when (this) {
        is Direction -> JSONObject().put(DIR, dir)
        is Frozen -> JSONObject().put(ALL, true).put(MODE, layout.mode).put(DIR, layout.dir).put(FIT, layout.fit)
            .put(TWO, layout.twoPage.key).put(CROP, layout.autoCrop).put(GAP, layout.spacing)
    }

    companion object {
        private const val ALL = "all"
        private const val MODE = "mode"
        private const val DIR = "dir"
        private const val FIT = "fit"
        private const val TWO = "two"
        private const val CROP = "crop"
        private const val GAP = "gap"

        private fun dirOf(o: JSONObject) = if (o.optString(DIR) == "rtl") "rtl" else "ltr"

        /** True for an entry older versions wrote: mode, direction, fit and spread, all kept. */
        fun isLegacy(o: JSONObject): Boolean = !o.has(ALL) && o.has(MODE)

        /**
         * [o] read back. A legacy entry reads as frozen, filling auto-crop and the gap from
         * [defaults], so nothing it held is lost before [migrate] sorts it.
         */
        fun decode(o: JSONObject, defaults: ReaderLayout = ReaderLayout()): FolderLayout {
            if (!o.has(ALL) && !o.has(MODE)) return Direction(dirOf(o))
            val two = o.opt(TWO)
            return Frozen(
                ReaderLayout(
                    mode = o.optString(MODE).takeIf { it in ReaderLayout.MODES } ?: ReaderLayout.MODE_PAGED,
                    dir = dirOf(o),
                    fit = o.optString(FIT, "screen").ifEmpty { "screen" },
                    twoPage = if (two is Boolean) TwoPage.of(two) else TwoPage.of(two as? String),
                    autoCrop = o.optBoolean(CROP, defaults.autoCrop),
                    spacing = o.optInt(GAP, defaults.spacing).coerceIn(0, Prefs.PAGE_SPACING_MAX)
                )
            )
        }

        /**
         * A legacy entry in today's form. Older versions kept the whole layout whenever a
         * direction was chosen over a detected one, so an entry whose mode, fit and spread
         * match the [defaults] keeps only its direction and follows the defaults from now on;
         * one that differs was set on purpose and stays frozen.
         */
        fun migrate(o: JSONObject, defaults: ReaderLayout): FolderLayout {
            val read = decode(o, defaults)
            if (read !is Frozen) return read
            val l = read.layout
            val same = l.mode == defaults.mode && l.fit == defaults.fit && l.twoPage == defaults.twoPage
            return if (same) Direction(l.dir) else read
        }
    }
}
