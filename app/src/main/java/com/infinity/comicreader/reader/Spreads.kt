package com.infinity.comicreader.reader

import java.text.Normalizer
import java.util.Locale

/**
 * Two-page spreads: what each page of a book is ([classify]) and which pages share a
 * screen in two-page reading ([pair]). Works on page paths in reading order and, when
 * known, their `[width, height]`; free of Android types.
 *
 * Pages are numbered by their family: the pages whose names share everything before
 * their page number (the same issue, chapter and folder). A name's page number is its
 * leftmost number that, across the family, is mostly distinct and mostly rising, so
 * issue, volume, year and chapter numbers never count. A filename spread has one more
 * number right after its page number, one higher (`-023-024`, `p190-p191`, `22+23`),
 * where neither number is a page of its own.
 *
 * Shown alone: the first page (an option), cover blocks and variant covers, inserted
 * pages (`156b`), wide pages, filename spreads, scanner tags at the end and pages
 * outside any family. Pairing restarts after each of them. Within a run of story
 * pages, the left pages' parity comes from the seams between them when they tell
 * ([Options.seams]: the picture runs on from one page into the next, so those two share
 * a screen; one match alone tells only near the reader), else from what spreads say (a spread starts on a left page), else the run's
 * first page is a left page; [Options.shift] flips it. In one-page reading, two pages
 * whose seam matches show as one ([merge]). The reader's own choices come first: a page
 * shown alone ([Options.alone]) breaks a run like a wide one; a page paired with the next
 * ([Options.paired]) shares a screen with it, whatever its kind, and the run after them
 * starts on a left page.
 */
object Spreads {

    enum class Kind {
        /** A numbered story page, paired with its neighbor. */
        STORY,

        /** A filename spread at least as wide as tall (or of unknown size). */
        SPREAD,

        /** A filename spread narrower than tall: a half of one, or a mislabeled page. */
        HALF_SPREAD,

        /** A wide page whose next page number is missing: a spread in one file. */
        SPREAD_WIDE,

        /** A wide page whose next page number exists, or without a number. */
        WIDE,

        /** Far taller than the book's pages: a strip. */
        TALL,
        FRONT_COVER,
        VARIANT_COVER,
        BACK_COVER,

        /** A page named as a cover in mid-book. */
        INTERIOR_COVER,

        /** A page with a letter after its number among plain numbers (`156b`). */
        INSERTED,

        /** A scanner's tag page at the end (`zzzz`, `zTag-…`). */
        TAG,

        /** A page outside any family before the first numbered one. */
        FRONT_MATTER,

        /** Any other page outside a family. */
        EXTRA
    }

    /**
     * One page: its [kind]; its [family] and [group] (families numbered as one, like the
     * chapters of an omnibus), -1 outside any; its page [number] and, for a filename
     * spread, the [last] number it covers (else [number]); [aspect] is width / height,
     * 0 when unknown.
     */
    data class Page(
        val kind: Kind,
        val family: Int = -1,
        val group: Int = -1,
        val number: Long = -1,
        val last: Long = number,
        val aspect: Double = 0.0,
        val dir: String = ""
    ) {
        /** True for any page of a cover block or named as a cover. */
        val cover: Boolean
            get() = kind == Kind.FRONT_COVER || kind == Kind.VARIANT_COVER || kind == Kind.BACK_COVER || kind == Kind.INTERIOR_COVER
    }

    /**
     * What [classify] found: every page, and whether the book is [landscape]: mostly wide
     * pages, which are then read one at a time.
     */
    class Analysis(val pages: List<Page>, val landscape: Boolean) {
        val size: Int get() = pages.size

        /** True when page [i] is wider than tall. */
        fun wide(i: Int): Boolean = pages.getOrNull(i)?.let { it.aspect > 1.0 } == true

        /**
         * True when page [i] is a spread in one file that can be shown as its two halves:
         * a wide spread or wide page, not a cover nor the book's first page, in a book of
         * portrait pages.
         */
        fun splittable(i: Int): Boolean {
            val p = pages.getOrNull(i) ?: return false
            if (landscape || i == 0 || p.cover || p.aspect <= 1.0) return false
            return p.kind == Kind.SPREAD || p.kind == Kind.SPREAD_WIDE || p.kind == Kind.WIDE
        }
    }

    /**
     * Pairing choices: the [coverAlone] option, the book's manual [shift] by one page and
     * the [seams] found so far, by page: the seam between that page and the next in
     * reading order, null where not checked. [visited]
     * holds the pages the reader has had on screen: a lone seam match settles a run only
     * within [NEAR] pages of one of them. Pages in [alone] (tall ones, and those the reader
     * chose to show alone) never share a screen. Each page in [paired] shares one with the
     * next, as the reader chose.
     */
    data class Options(
        val coverAlone: Boolean = true,
        val shift: Boolean = false,
        val seams: List<SpreadSeams.Verdict?> = emptyList(),
        val visited: Set<Int> = emptySet(),
        val alone: Set<Int> = emptySet(),
        val paired: Set<Int> = emptySet()
    ) {
        /** True when the picture runs on from page [i] into the next. */
        fun joins(i: Int): Boolean = seams.getOrNull(i)?.match == true

        /** True when the seam after page [i] lies within [NEAR] pages of a page the reader has had on screen. */
        fun near(i: Int): Boolean = visited.isNotEmpty() && (i - NEAR..i + 1 + NEAR).any { it in visited }
    }

    /** Pages either way of a seam within which the reader sees it, so a lone match there may settle its run. */
    const val NEAR = 4

    /**
     * The fewest known page sizes a book is judged [landscape][Analysis.landscape] on (all of
     * a shorter book's): two one-file spreads beside the page a book opens on are not a
     * landscape book.
     */
    const val LANDSCAPE_SAMPLE = 5

    // ------------------------------------------------------------------ tokens

    /** A number in a name: digits at [start] until [end], letter suffix until [endAll]. */
    internal class Token(
        val start: Int,
        val end: Int,
        val endAll: Int,
        val value: Long,
        val prefix: Char?,
        val suffix: String
    )

    private val NUM = Regex("""([0-9]+)(?:([A-Za-z])(?:([0-9]+)|(?![A-Za-z]))|(_)(?=\s|$))?""")
    private val SEP = Regex("""^\s*[-+&,_~]\s*([A-Za-z])?$""")
    private val COVER_WORD = Regex("""(?i)(?<![a-z])(cover|variant)(?![a-z])""")
    private val TAG = Regex("""^(?:(?i:zz)|z[A-Z0-9_\-])""")
    private val DIGITS = Regex("[0-9]+")
    private val SPACES = Regex("\\s+")

    /** The numbers in [stem], each with its one-letter prefix and letter suffix. */
    internal fun tokens(stem: String): List<Token> = NUM.findAll(stem).map { m ->
        val digits = m.groups[1]!!
        val s = digits.range.first
        val before = stem.getOrNull(s - 1)
        val prefix = before?.takeIf { it.isLetter() && stem.getOrNull(s - 2)?.isLetter() != true }?.lowercaseChar()
        val suffix = (m.groupValues[2] + m.groupValues[3] + m.groupValues[4]).lowercase(Locale.ROOT)
        val value = if (digits.value.length > 18) -1L else digits.value.toLong()
        Token(s, digits.range.last + 1, m.range.last + 1, value, prefix, suffix)
    }.toList()

    /** The folder part of [path], inner archives included. */
    private fun dirOf(path: String): String {
        val slash = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        return if (slash >= 0) path.substring(0, slash) else ""
    }

    /** The last component of [path] without its extension. */
    internal fun stemOf(path: String): String {
        val slash = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        val name = path.substring(slash + 1)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** What comes before a number, normalized: lowercase NFC, numbers without leading zeros, spaces collapsed. */
    private fun prefixKey(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            .replace(DIGITS) { it.value.trimStart('0').ifEmpty { "0" } }
            .replace(SPACES, " ").trimEnd()

    private class Name(val dir: String, val stem: String, val tokens: List<Token>) {
        private val keys = arrayOfNulls<String>(tokens.size)

        /** The family key of token [i]: [dir] and the normalized text before it, made once. */
        fun key(i: Int): String = keys[i] ?: (dir + '\u0000' + prefixKey(stem.substring(0, tokens[i].start))).also { keys[i] = it }
    }

    /** The token right after [i] in [n] when the two read as one range (`23-24`, `p190-p191`, `22+23`). */
    private fun adjacentNext(n: Name, i: Int): Token? {
        val a = n.tokens[i]
        val b = n.tokens.getOrNull(i + 1) ?: return null
        if (a.suffix.isNotEmpty() && a.suffix != "_") return null
        val m = SEP.matchEntire(n.stem.substring(a.endAll, b.start)) ?: return null
        val letter = m.groups[1]?.value?.single()?.lowercaseChar()
        return if (letter == null || letter == a.prefix) b else null
    }

    // ------------------------------------------------------------------ classify

    private class Member(val page: Int, val token: Token, val next: Token?)

    private class Family(val id: Int, val key: String, val members: MutableList<Member> = ArrayList()) {
        var group = -1
        val min get() = members.minOf { it.token.value }
        val max get() = members.maxOf { it.next?.value ?: it.token.value }
    }

    /**
     * Classifies the pages at [paths], in reading order; [sizes] (`[width, height]` each, null
     * when unknown, missing entries unknown) find wide and tall pages.
     */
    fun classify(paths: List<String>, sizes: List<IntArray?> = emptyList()): Analysis {
        val n = paths.size
        val names = paths.map { p -> stemOf(p).let { Name(dirOf(p), it, tokens(it)) } }
        val aspects = DoubleArray(n) { i ->
            val s = sizes.getOrNull(i)
            if (s != null && s.size >= 2 && s[0] > 0 && s[1] > 0) s[0].toDouble() / s[1] else 0.0
        }
        val families = families(names)
        val slotOf = arrayOfNulls<Member>(n)
        val familyOf = IntArray(n) { -1 }
        for (f in families) for (m in f.members) {
            slotOf[m.page] = m
            familyOf[m.page] = f.id
        }
        groupFamilies(families)

        // Portrait shape of the book: wide and tall pages are judged against it.
        val portrait = aspects.filter { it > 0 && it < 1.0 }.sorted()
        val m = if (portrait.isEmpty()) 0.66 else portrait[portrait.size / 2]
        // Wider than tall, and clearly wider than the book's pages.
        val wide = { a: Double -> a > 1.0 && a >= 1.45 * m }
        val known = aspects.count { it > 0 }
        val landscape = known > 0 && known >= minOf(n, LANDSCAPE_SAMPLE) && aspects.count(wide) * 10 >= known * 6

        val kinds = arrayOfNulls<Kind>(n)
        val last = LongArray(n) { -1 }
        for (f in families) classifyFamily(f, names, aspects, wide, kinds, last)

        // Pages outside every family.
        val firstAssigned = (0 until n).firstOrNull { familyOf[it] >= 0 } ?: n
        val mostlyNumbered = familyOf.count { it >= 0 } * 2 >= n
        for (i in 0 until n) {
            if (kinds[i] != null) continue
            val a = aspects[i]
            kinds[i] = when {
                wide(a) -> Kind.WIDE
                a > 0 && a < 0.4 * m -> Kind.TALL
                isTrailingTag(i, names, familyOf) -> Kind.TAG
                !mostlyNumbered -> Kind.STORY
                i < firstAssigned -> Kind.FRONT_MATTER
                else -> Kind.EXTRA
            }
        }
        val pages = (0 until n).map { i ->
            val member = slotOf[i]
            val fam = familyOf[i]
            Page(
                kind = kinds[i]!!,
                family = fam,
                group = if (fam >= 0) families[fam].group else -1,
                number = member?.token?.value ?: -1,
                last = if (last[i] >= 0) last[i] else member?.token?.value ?: -1,
                aspect = aspects[i],
                dir = names[i].dir
            )
        }
        return Analysis(pages, landscape)
    }

    /** True when page [i] is outside every family, named like a tag, with only such pages after it in its folder. */
    private fun isTrailingTag(i: Int, names: List<Name>, familyOf: IntArray): Boolean {
        if (!TAG.containsMatchIn(names[i].stem)) return false
        for (j in i + 1 until names.size) {
            if (names[j].dir != names[i].dir) break
            if (familyOf[j] >= 0) return false
        }
        return true
    }

    /** Families of the page numbers in [names]: each name's leftmost number that numbers pages. */
    private fun families(names: List<Name>): List<Family> {
        val candidates = HashMap<String, MutableList<Pair<Int, Token>>>()
        for ((page, name) in names.withIndex()) {
            for (i in name.tokens.indices) candidates.getOrPut(name.key(i)) { ArrayList() } += page to name.tokens[i]
        }
        val qualifies = HashMap<String, Boolean>()
        fun qualifies(key: String): Boolean = qualifies.getOrPut(key) {
            val f = candidates[key].orEmpty()
            if (f.size < 3) return@getOrPut false
            val distinct = f.map { it.second.value to it.second.suffix }.toSet().size
            if (distinct * 4 < f.size * 3) return@getOrPut false
            val rising = f.zipWithNext().count { (a, b) -> b.second.value >= a.second.value }
            rising * 10 >= (f.size - 1) * 9
        }
        val byKey = LinkedHashMap<String, Family>()
        for ((page, name) in names.withIndex()) {
            val i = name.tokens.indices.firstOrNull { qualifies(name.key(it)) } ?: continue
            val key = name.key(i)
            val family = byKey.getOrPut(key) { Family(byKey.size, key) }
            family.members += Member(page, name.tokens[i], adjacentNext(name, i))
        }
        return byKey.values.toList()
    }

    /** Numbers families as one group when they read as one numbering (keys alike but for numbers, ranges rising). */
    private fun groupFamilies(families: List<Family>) {
        val lastOf = HashMap<String, Family>()
        var groups = 0
        for (f in families) {
            val masked = f.key.replace(DIGITS, "#")
            val prev = lastOf[masked]
            f.group = if (prev != null && prev.max < f.min) prev.group else groups++
            lastOf[masked] = f
        }
    }

    private fun classifyFamily(f: Family, names: List<Name>, aspects: DoubleArray, wide: (Double) -> Boolean, kinds: Array<Kind?>, last: LongArray) {
        val members = f.members
        val withNext = members.filter { it.next != null }
        val constSuffix = members.groupingBy { it.token.suffix }.eachCount()
            .filter { (s, c) -> s.isNotEmpty() && c * 2 >= members.size }.keys.firstOrNull()
        // Every page carries a second number: dual numbering, never spreads.
        val dual = withNext.size * 2 >= members.size
        val values = members.filter { it.next == null && (it.token.suffix.isEmpty() || it.token.suffix == constSuffix) }
            .mapTo(HashSet()) { it.token.value }
        val suffixed = members.count { it.token.suffix.isNotEmpty() && it.token.suffix != constSuffix }
        val m0 = members.minOf { it.token.value }
        val m0Count = members.count { it.token.value == m0 }
        val lastPage = members.last().page
        var frontSeen = false
        var storySeen = false
        for (member in members) {
            val page = member.page
            val t = member.token
            val a = aspects[page]
            val ownSuffix = t.suffix.isNotEmpty() && t.suffix != constSuffix
            val b = member.next?.value
            val filenameSpread = !dual && b != null && b == t.value + 1 && t.value !in values && b !in values &&
                (t.value - 1 in values || b + 1 in values)
            val keyword = COVER_WORD.containsMatchIn(names[page].stem)
            val blockCover = !storySeen && ((t.value == m0 && (ownSuffix || m0Count > 1)) || t.value == 0L || keyword)
            val kind = when {
                filenameSpread -> {
                    last[page] = b!!
                    if (a > 0 && a < 1.0) Kind.HALF_SPREAD else Kind.SPREAD
                }
                wide(a) && !blockCover -> if (t.value + 1 in values) Kind.WIDE else Kind.SPREAD_WIDE.also { last[page] = t.value + 1 }
                blockCover -> if (frontSeen) Kind.VARIANT_COVER else Kind.FRONT_COVER.also { frontSeen = true }
                keyword && page == lastPage -> Kind.BACK_COVER
                keyword -> Kind.INTERIOR_COVER
                ownSuffix && (t.value in values || suffixed * 2 < members.size) -> Kind.INSERTED
                else -> Kind.STORY
            }
            if (kind != Kind.FRONT_COVER && kind != Kind.VARIANT_COVER) storySeen = true
            kinds[page] = kind
        }
    }

    // ------------------------------------------------------------------ pair

    /** Breaks after which pairing keeps the parity it had, rather than starting over. */
    private val SOFT = setOf(Kind.INSERTED, Kind.INTERIOR_COVER)

    private class Segment(val pages: List<Int>, val numbered: Boolean, val group: Int, val soft: Boolean)

    /**
     * The screens of a two-page reader: each a list of one or two page indices in reading
     * order, covering every page once, in order.
     */
    fun pair(analysis: Analysis, options: Options = Options()): List<IntArray> {
        val pages = analysis.pages
        val forced = forcedPairs(pages.size, options)
        val out = ArrayList<IntArray>(pages.size)
        var at = 0
        // The pages before [until]: each alone but for the pairs the reader chose.
        fun fill(until: Int) {
            while (at < until) {
                out += if (at in forced) intArrayOf(at, at + 1).also { at += 2 } else intArrayOf(at++)
            }
        }
        if (analysis.landscape) {
            fill(pages.size)
            return out
        }
        val pairable = pairable(pages, options, forced)
        val segments = segments(pages, pairable)
        val votes = votes(pages)
        var previousParity = -1
        var previousGroup = -2
        for (seg in segments) {
            fill(seg.pages.first())
            // Right after a pair the reader chose, a run starts on a left page.
            val anchored = seg.pages.first() - 2 in forced
            val pairs = if (seg.numbered) {
                val soft = seg.soft && seg.group == previousGroup
                val bySeams = if (!anchored) seamSide(numberedSeams(seg, pages, options)) else null
                var parity = if (anchored) Math.floorMod(pages[seg.pages.first()].number, 2L).toInt()
                else bySeams ?: parityOf(seg, pages, votes[seg.group], if (soft) previousParity else -1)
                val flip = options.shift && !anchored
                if (flip) parity = 1 - parity
                previousParity = if (flip) 1 - parity else parity
                previousGroup = seg.group
                pairNumbered(seg.pages, pages, parity)
            } else {
                previousGroup = -2
                val offset = (if (!anchored) seamSide(positionalSeams(seg, options)) else null) ?: 0
                pairPositional(seg.pages, if (options.shift && !anchored) 1 - offset else offset)
            }
            out += pairs
            at = seg.pages.last() + 1
        }
        fill(pages.size)
        return out
    }

    /**
     * The first pages of the pairs the reader chose ([Options.paired]) among [count] pages: each
     * with a next page, none overlapping the one before.
     */
    private fun forcedPairs(count: Int, options: Options): Set<Int> {
        if (options.paired.isEmpty()) return emptySet()
        val out = HashSet<Int>()
        for (p in options.paired.sorted()) if (p >= 0 && p + 1 < count && p - 1 !in out) out += p
        return out
    }

    /**
     * Pages that may share a screen: story pages, covers too without [Options.coverAlone]; never
     * wide ones, nor [Options.alone], nor those of a pair the reader chose ([forced]).
     */
    private fun pairable(pages: List<Page>, options: Options, forced: Set<Int>) = BooleanArray(pages.size) { i ->
        val k = pages[i].kind
        when {
            i in forced || i - 1 in forced -> false
            i == 0 && options.coverAlone -> false
            i in options.alone -> false
            // A wide page is alone whatever its kind, a wraparound cover too.
            pages[i].aspect > 1.0 -> false
            k == Kind.STORY -> true
            !options.coverAlone && (k == Kind.FRONT_COVER || k == Kind.VARIANT_COVER) -> true
            else -> false
        }
    }

    /**
     * The screens of a one-page reader: each page alone, but for two pages that may share
     * a screen whose seam matches ([Options.seams]), shown as one, first come first, and the
     * pairs the reader chose ([Options.paired]).
     */
    fun merge(analysis: Analysis, options: Options): List<IntArray> {
        val pages = analysis.pages
        val forced = forcedPairs(pages.size, options)
        val bySeams = !analysis.landscape && options.seams.any { it?.match == true }
        if (!bySeams && forced.isEmpty()) return pages.indices.map { intArrayOf(it) }
        val pairable = pairable(pages, options, forced)
        val out = ArrayList<IntArray>(pages.size)
        var i = 0
        while (i < pages.size) {
            val seam = bySeams && i + 1 < pages.size && pairable[i] && pairable[i + 1] && pages[i].dir == pages[i + 1].dir && options.joins(i)
            if (i in forced || seam) {
                out += intArrayOf(i, i + 1)
                i += 2
            } else {
                out += intArrayOf(i++)
            }
        }
        return out
    }

    /** The screens of [views] (page indices in reading order) whose two pages are one picture, by their seam. */
    fun joined(views: List<IntArray>, options: Options): Set<Int> =
        views.indices.filterTo(HashSet()) { v -> views[v].size == 2 && views[v][1] == views[v][0] + 1 && options.joins(views[v][0]) }

    /** A seam that runs on: the side, 0 or 1, that puts its first page on the left, how sure it is, and whether the reader is [near] it. */
    private class SeamVote(val side: Int, val confidence: Double, val near: Boolean)

    /** Seams that run on between consecutive numbers of [seg], each voting the parity of its first page's number. */
    private fun numberedSeams(seg: Segment, pages: List<Page>, options: Options): List<SeamVote> =
        seg.pages.zipWithNext().mapNotNull { (a, b) ->
            val v = options.seams.getOrNull(a)
            if (v?.match == true && pages[b].number == pages[a].number + 1) {
                SeamVote(Math.floorMod(pages[a].number, 2L).toInt(), v.confidence, options.near(a))
            } else {
                null
            }
        }

    /** Seams that run on within [seg], each voting the offset that puts its first page on the left. */
    private fun positionalSeams(seg: Segment, options: Options): List<SeamVote> =
        seg.pages.withIndex().toList().dropLast(1).mapNotNull { (k, a) ->
            val v = options.seams.getOrNull(a)
            if (v?.match == true) SeamVote(k % 2, v.confidence, options.near(a)) else null
        }

    /**
     * The side seams settle: one with at least two matches and three times the other's, or
     * a lone [SpreadSeams.STRONG] match with none against it near the reader
     * ([Options.near]); null when they do not tell.
     */
    private fun seamSide(votes: List<SeamVote>): Int? {
        if (votes.isEmpty()) return null
        for (side in 0..1) {
            val mine = votes.filter { it.side == side }
            val other = votes.size - mine.size
            if (mine.size >= 2 && mine.size >= 3 * other) return side
            if (mine.size == 1 && other == 0 && mine[0].confidence >= SpreadSeams.STRONG && mine[0].near) return side
        }
        return null
    }

    /** Runs of pairable pages, broken by every other page and by a change of group or folder. */
    private fun segments(pages: List<Page>, pairable: BooleanArray): List<Segment> {
        val out = ArrayList<Segment>()
        var run = ArrayList<Int>()
        var soft = false
        var nextSoft = false
        fun close() {
            if (run.isEmpty()) return
            val numbered = run.all { pages[it].number >= 0 && pages[it].group >= 0 }
            out += Segment(run, numbered, pages[run[0]].group, soft)
            run = ArrayList()
        }
        for (i in pages.indices) {
            if (!pairable[i]) {
                val wasRun = run.isNotEmpty()
                close()
                // A soft break only carries parity on when nothing harder came between.
                nextSoft = pages[i].kind in SOFT && (wasRun || nextSoft)
                continue
            }
            val p = pages[i]
            val prev = run.lastOrNull()?.let { pages[it] }
            if (prev != null && (prev.group != p.group || prev.dir != p.dir)) {
                close()
                nextSoft = false
            }
            if (run.isEmpty()) {
                soft = nextSoft
                nextSoft = false
            }
            run += i
        }
        close()
        return out
    }

    /** A spread's say on which parity left pages have: [parity] with [weight], from the page numbered [at]. */
    private class Vote(val parity: Int, val weight: Int, val at: Long)

    /** Every group's votes: filename spreads (strong when wide), wide spreads. */
    private fun votes(pages: List<Page>): Map<Int, List<Vote>> {
        val out = HashMap<Int, MutableList<Vote>>()
        for (p in pages) {
            if (p.group < 0 || p.number < 0) continue
            val weight = when (p.kind) {
                Kind.SPREAD -> 3
                Kind.SPREAD_WIDE -> 2
                Kind.HALF_SPREAD -> 1
                else -> continue
            }
            out.getOrPut(p.group) { ArrayList() } += Vote(Math.floorMod(p.number, 2L).toInt(), weight, p.number)
        }
        return out
    }

    /**
     * The parity of left pages in [seg]: the votes' when one side has at least twice the
     * weight, the nearest vote's when they conflict, else [carried] from a soft break, else
     * the segment's first page is a left page.
     */
    private fun parityOf(seg: Segment, pages: List<Page>, votes: List<Vote>?, carried: Int): Int {
        val first = pages[seg.pages.first()].number
        if (!votes.isNullOrEmpty()) {
            val odd = votes.filter { it.parity == 1 }.sumOf { it.weight }
            val even = votes.filter { it.parity == 0 }.sumOf { it.weight }
            if (odd >= 2 * even) return 1
            if (even >= 2 * odd) return 0
            return votes.minByOrNull { Math.abs(it.at - first) }!!.parity
        }
        if (carried >= 0) return carried
        return Math.floorMod(first, 2L).toInt()
    }

    /** Pairs consecutive numbers whose first has [parity]; gaps and the odd one out stand alone. */
    private fun pairNumbered(seg: List<Int>, pages: List<Page>, parity: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        while (i < seg.size) {
            val p = pages[seg[i]]
            val q = seg.getOrNull(i + 1)?.let { pages[it] }
            if (q != null && q.number == p.number + 1 && Math.floorMod(p.number - parity, 2L) == 0L) {
                out += intArrayOf(seg[i], seg[i + 1])
                i += 2
            } else {
                out += intArrayOf(seg[i])
                i++
            }
        }
        return out
    }

    /** Pairs by position, after [offset] pages shown alone. */
    private fun pairPositional(seg: List<Int>, offset: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        val lead = minOf(offset, seg.size)
        for (i in 0 until lead) out += intArrayOf(seg[i])
        seg.drop(lead).chunked(2).forEach { out += it.toIntArray() }
        return out
    }
}
