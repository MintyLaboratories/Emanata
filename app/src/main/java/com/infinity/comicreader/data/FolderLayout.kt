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

        /** Retired modes, both read top to bottom: one page a screen, and one column. */
        internal const val OLD_VERTICAL = "vertical"
        internal const val OLD_LONGSTRIP = "longstrip"

        /** [value] when it names a mode, else paged. */
        fun modeOf(value: String?): String = value?.takeIf { it in MODES } ?: MODE_PAGED

        /** [value] when it names a direction, else left to right. */
        fun dirOf(value: String?): String = value?.takeIf { it in DIRS } ?: DIR_LTR

        /**
         * The mode and direction a stored [mode] and [dir] stand for today: the retired
         * vertical pages top to bottom, the retired long strip is continuous top to bottom.
         */
        fun migrated(mode: String?, dir: String?): Pair<String, String> = when (mode) {
            OLD_VERTICAL -> MODE_PAGED to DIR_TTB
            OLD_LONGSTRIP -> MODE_CONTINUOUS to DIR_TTB
            else -> modeOf(mode) to dirOf(dir)
        }

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


        /** True for an entry older versions wrote: mode, direction, fit and spread, all kept. */
        fun isLegacy(o: JSONObject): Boolean = !o.has(ALL) && o.has(MODE)

        /** True for an entry whose mode is a retired one, which [decode] reads as today's. */
        fun hasRetiredMode(o: JSONObject): Boolean =
            o.optString(MODE).let { it == ReaderLayout.OLD_VERTICAL || it == ReaderLayout.OLD_LONGSTRIP }

        /**
         * [o] read back. A legacy entry reads as frozen, filling auto-crop and the gap from
         * [defaults], so nothing it held is lost before [migrate] sorts it. A retired mode
         * reads as top to bottom ([ReaderLayout.migrated]), its direction chosen with it;
         * a retired long strip never cropped, so it keeps auto-crop off.
         */
        fun decode(o: JSONObject, defaults: ReaderLayout = ReaderLayout()): FolderLayout {
            if (!o.has(ALL) && !o.has(MODE)) return Direction(ReaderLayout.dirOf(o.optString(DIR)))
            val two = o.opt(TWO)
            val oldMode = o.optString(MODE)
            val (mode, dir) = ReaderLayout.migrated(oldMode, o.optString(DIR))
            val retired = oldMode == ReaderLayout.OLD_VERTICAL || oldMode == ReaderLayout.OLD_LONGSTRIP
            return Frozen(
                ReaderLayout(
                    mode = mode,
                    dir = dir,
                    fit = o.optString(FIT, "screen").ifEmpty { "screen" },
                    twoPage = if (two is Boolean) TwoPage.of(two) else TwoPage.of(two as? String),
                    autoCrop = oldMode != ReaderLayout.OLD_LONGSTRIP && o.optBoolean(CROP, defaults.autoCrop),
                    spacing = o.optInt(GAP, defaults.spacing).coerceIn(0, Prefs.PAGE_SPACING_MAX),
                    spreads = if (o.has(SPREADS)) SpreadDisplay.of(o.optString(SPREADS)) else defaults.spreads
                ),
                dirChosen = retired || o.optBoolean(CHOSEN, true)
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
