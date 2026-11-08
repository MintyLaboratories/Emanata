package com.infinity.comicreader.net

import androidx.annotation.VisibleForTesting

/** Network work nobody asked for, by what the skip choices ([NetSkip]) say of it. */
enum class NetWork {
    /** A cover of a book on a share, its row on screen. */
    COVER,

    /** A cover of a book on a share whose row is not on screen (bound ahead of a scroll). */
    COVER_OFF_SCREEN,

    /** Reading share books for their hashes: similar books, exact copies. */
    HASHING,

    /** Share bytes fetched before they are read, beyond the page being read. */
    READ_AHEAD,

    /** A model or detector download the app starts or resumes by itself. */
    DOWNLOAD,

    /** Any other background read of shares: folder facts, page sizes, ComicInfo for likeness. */
    OTHER
}

/** One choice in "Skip on metered connections" or "On slow shares, skip", stored by [key]. */
enum class NetSkip(val key: String) {
    COVERS("covers"),
    HASHING("hashing"),
    READ_AHEAD("read_ahead"),
    DOWNLOADS("downloads"),

    /** Any network use the user did not start: every other choice, and [NetWork.OTHER]. */
    ANY("any");

    companion object {
        /** The choices [stored] names, comma-separated; unknown names are left out. */
        fun parse(stored: String): Set<NetSkip> =
            stored.split(',').mapNotNullTo(LinkedHashSet()) { name -> entries.firstOrNull { it.key == name.trim() } }

        /** [skips] as [parse] reads them, in declaration order. */
        fun format(skips: Set<NetSkip>): String = entries.filter { it in skips }.joinToString(",") { it.key }

        /** The choices offered for metered connections. */
        val METERED_CHOICES = listOf(COVERS, HASHING, READ_AHEAD, DOWNLOADS, ANY)

        /** The choices offered for slow shares: downloads come from the internet, not shares. */
        val SLOW_CHOICES = listOf(COVERS, HASHING, READ_AHEAD, ANY)

        /**
         * [current] after the user ticks ([checked]) or unticks [choice] among [offered]: ticking
         * [ANY] ticks every choice, unticking another unticks [ANY] too.
         */
        fun toggle(current: Set<NetSkip>, choice: NetSkip, checked: Boolean, offered: List<NetSkip>): Set<NetSkip> = when {
            choice == ANY && checked -> offered.toSet()
            checked -> current + choice
            else -> current - choice - ANY
        }
    }
}

/** What is skipped on a metered connection ([metered]) and on a slow share ([slow]). */
data class NetRules(val metered: Set<NetSkip> = METERED_DEFAULT, val slow: Set<NetSkip> = SLOW_DEFAULT) {
    companion object {
        /** Every optional item on metered connections. */
        val METERED_DEFAULT: Set<NetSkip> = NetSkip.METERED_CHOICES.toSet()

        /** Background hashing, read-ahead beyond the page shown, covers of rows not on screen. */
        val SLOW_DEFAULT: Set<NetSkip> = setOf(NetSkip.COVERS, NetSkip.HASHING, NetSkip.READ_AHEAD)
    }
}

/**
 * Decides whether optional network work may run now: not on a metered connection ([Metered]) when
 * the user skips it there, not on a slow share ([ShareSpeeds]) when skipped there. Explicit actions
 * (opening a book, a search, a Download or Compare tap) never ask.
 */
object NetPolicy {
    /** Why work was held back: the connection is metered, or the share slow. */
    enum class Why { METERED, SLOW }

    /** The skip choices; the settings set them. */
    @Volatile
    @set:VisibleForTesting
    var rules: NetRules = NetRules()

    /**
     * Why [work] is held back under [rules] on a [metered] connection, on a [slow] share; null
     * when it may run. [NetSkip.ANY] holds back everything; on slow shares, [NetSkip.COVERS]
     * holds back only covers not on screen, those shown being fetched as they are needed.
     */
    fun why(work: NetWork, metered: Boolean, slow: Boolean, rules: NetRules): Why? = when {
        metered && skips(rules.metered, work, slowShare = false) -> Why.METERED
        slow && skips(rules.slow, work, slowShare = true) -> Why.SLOW
        else -> null
    }

    private fun skips(chosen: Set<NetSkip>, work: NetWork, slowShare: Boolean): Boolean {
        if (NetSkip.ANY in chosen) return true
        return when (work) {
            NetWork.COVER -> !slowShare && NetSkip.COVERS in chosen
            NetWork.COVER_OFF_SCREEN -> NetSkip.COVERS in chosen
            NetWork.HASHING -> NetSkip.HASHING in chosen
            NetWork.READ_AHEAD -> NetSkip.READ_AHEAD in chosen
            NetWork.DOWNLOAD -> NetSkip.DOWNLOADS in chosen
            NetWork.OTHER -> false
        }
    }

    /** Why [work] on share [shareId] (none: not on a share) is held back now; null when it may run. */
    fun whyNot(work: NetWork, shareId: String? = null): Why? =
        why(work, Metered.now() == Metered.Verdict.METERED, shareId != null && ShareSpeeds.slow(shareId), rules)

    /** True when [work] on share [shareId] may run now ([whyNot]). */
    fun may(work: NetWork, shareId: String? = null): Boolean = whyNot(work, shareId) == null

    /** Why [work] on the file at [path] is held back now; null when it may run, always for one on the device. */
    fun whyNotAt(work: NetWork, path: String): Why? = Remotes.idOf(path)?.let { whyNot(work, it) }

    /** True when [work] on the file at [path] may run now ([whyNotAt]). */
    fun mayAt(work: NetWork, path: String): Boolean = whyNotAt(work, path) == null
}
