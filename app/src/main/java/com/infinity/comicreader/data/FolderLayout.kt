package com.infinity.comicreader.data

import org.json.JSONObject

/**
 * How the reader lays pages out: the layout [mode] (paged or continuous), the reading
 * direction [dir] (left to right, right to left or top to bottom), [fit], [twoPage],
 * [autoCrop], the page gap [spacing] in dp and how [spreads] in one file show. Only top
 * to bottom reads as one continuous column; continuous with a left or right direction
 * pages ([shownMode]), so the mode stays as chosen for books that read top to bottom.
 */
data class ReaderLayout(
    val mode: String = MODE_PAGED,
    val dir: String = DIR_LTR,
    val fit: String = "screen",
    val twoPage: TwoPage = TwoPage.OFF,
    val autoCrop: Boolean = false,
    val spacing: Int = 0,
    val spreads: SpreadDisplay = SpreadDisplay.MERGE
) {
    companion object {
        const val MODE_PAGED = "paged"
        const val MODE_CONTINUOUS = "continuous"
        val MODES = listOf(MODE_PAGED, MODE_CONTINUOUS)

        const val DIR_LTR = "ltr"
        const val DIR_RTL = "rtl"
        const val DIR_TTB = "ttb"
        val DIRS = listOf(DIR_LTR, DIR_RTL, DIR_TTB)

        /** [value] when it names a mode, else paged. */
        fun modeOf(value: String?): String = value?.takeIf { it in MODES } ?: MODE_PAGED

        /** [value] when it names a direction, else left to right. */
        fun dirOf(value: String?): String = value?.takeIf { it in DIRS } ?: DIR_LTR

        /**
         * The mode a book shows in: continuous only when it reads top to bottom, and then
         * also when [sliced] (detected slices of one tall strip) though [mode] is paged.
         */
        fun shownMode(mode: String, dir: String, sliced: Boolean = false): String = when {
            dir != DIR_TTB -> MODE_PAGED
            sliced -> MODE_CONTINUOUS
            else -> modeOf(mode)
        }

        /** The global defaults in [p]. */
        fun of(p: Prefs) = ReaderLayout(p.pageMode, p.readingDir, p.fit, p.twoPage, p.autoCrop, p.pageSpacing, p.spreads)
    }
}

/**
 * What the reader keeps for one folder: its reading direction alone ([Direction], set by
 * choosing one), or its whole layout ([Frozen], "Apply to: This folder").
 */
sealed class FolderLayout {
    abstract val dir: String

    /** True when the folder decides the direction; false leaves it to detection and the defaults. */
    abstract val dirChosen: Boolean

    /** The layout a book in the folder shows, given the global [defaults]. */
    abstract fun over(defaults: ReaderLayout): ReaderLayout

    data class Direction(override val dir: String) : FolderLayout() {
        override val dirChosen: Boolean get() = true

        override fun over(defaults: ReaderLayout) = defaults.copy(dir = dir)
    }

    /** The whole [layout]; its direction only while [dirChosen], else the default one, which detection may replace. */
    data class Frozen(val layout: ReaderLayout, override val dirChosen: Boolean = true) : FolderLayout() {
        override val dir: String get() = layout.dir

        override fun over(defaults: ReaderLayout) = if (dirChosen) layout else layout.copy(dir = defaults.dir)

        /** What "Apply to: All books" leaves the folder: its direction when chosen, else nothing. */
        fun thawed(): FolderLayout? = if (dirChosen) Direction(dir) else null
    }

    fun encode(): JSONObject = when (this) {
        is Direction -> JSONObject().put(DIR, dir)
        is Frozen -> JSONObject().put(ALL, true).put(MODE, layout.mode).put(DIR, layout.dir).put(FIT, layout.fit)
            .put(TWO, layout.twoPage.key).put(CROP, layout.autoCrop).put(GAP, layout.spacing).put(SPREADS, layout.spreads.key)
            .apply { if (!dirChosen) put(CHOSEN, false) }
    }

    companion object {
        private const val ALL = "all"
        private const val MODE = "mode"
        private const val DIR = "dir"
        private const val FIT = "fit"
        private const val TWO = "two"
        private const val CROP = "crop"
        private const val GAP = "gap"
        private const val SPREADS = "spreads"
        private const val CHOSEN = "chosen"

        /** [o] read back; odd values fall back to the defaults. */
        fun decode(o: JSONObject): FolderLayout {
            val dir = ReaderLayout.dirOf(o.optString(DIR))
            if (!o.has(ALL)) return Direction(dir)
            return Frozen(
                ReaderLayout(
                    mode = ReaderLayout.modeOf(o.optString(MODE)),
                    dir = dir,
                    fit = o.optString(FIT, "screen").ifEmpty { "screen" },
                    twoPage = TwoPage.of(o.optString(TWO)),
                    autoCrop = o.optBoolean(CROP),
                    spacing = o.optInt(GAP).coerceIn(0, Prefs.PAGE_SPACING_MAX),
                    spreads = SpreadDisplay.of(o.optString(SPREADS))
                ),
                dirChosen = o.optBoolean(CHOSEN, true)
            )
        }
    }
}
