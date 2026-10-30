package com.infinity.comicreader.reader

import com.infinity.comicreader.reader.Spreads.Kind
import com.infinity.comicreader.util.NaturalOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The test vectors of the spread spec, on invented names. */
class SpreadsTest {

    private val portrait = intArrayOf(1300, 2000)
    private val wide = intArrayOf(2620, 2000)

    /** Page names [prefix] + each number of [range], zero-padded to [pad], + [suffix]. */
    private fun run(prefix: String, range: IntRange, pad: Int = 3, suffix: String = "") =
        range.map { prefix + it.toString().padStart(pad, '0') + suffix + ".jpg" }

    /** [names] as an archive lists its pages: in natural order. */
    private fun book(vararg parts: List<String>): List<String> = parts.flatMap { it }.sortedWith(NaturalOrder)

    private fun page(paths: List<String>, stem: String, sizes: List<IntArray?> = emptyList()): Spreads.Page {
        val i = paths.indexOfFirst { Spreads.stemOf(it) == stem }
        assertTrue("no page $stem", i >= 0)
        return Spreads.classify(paths, sizes).pages[i]
    }

    /** The screens of [paths] as `[a|b][c]…`, each page by its stem less [strip]. */
    private fun screens(
        paths: List<String>,
        strip: String,
        sizes: List<IntArray?> = emptyList(),
        options: Spreads.Options = Spreads.Options()
    ): String = Spreads.pair(Spreads.classify(paths, sizes), options).joinToString("") { view ->
        view.joinToString("|", "[", "]") { Spreads.stemOf(paths[it]).removePrefix(strip) }
    }

    private fun sizes(paths: List<String>, wideStems: Set<String>) =
        paths.map { if (Spreads.stemOf(it) in wideStems) wide else portrait }

    // -------------------------------------------------------------- 1, 2, 38

    private val harbor = book(run("Pale Harbor 018-", 0..22), listOf("Pale Harbor 018-023-024.jpg"), run("Pale Harbor 018-", 25..30), listOf("zWave.jpg"))

    @Test
    fun v01_filenameSpreadAfterIssueNumber() {
        val p = page(harbor, "Pale Harbor 018-023-024")
        assertEquals(Kind.SPREAD, p.kind)
        assertEquals(23L, p.number)
        assertEquals(24L, p.last)
    }

    @Test
    fun v02_spreadVotesOddLeftPages() {
        val expected = "[000]" + (1..21 step 2).joinToString("") { "[%03d|%03d]".format(it, it + 1) } +
            "[023-024][025|026][027|028][029|030][zWave]"
        assertEquals(expected, screens(harbor, "Pale Harbor 018-"))
    }

    @Test
    fun v38_portraitFilenameSpreadIsAHalfSpreadAlone() {
        val sizes = harbor.map { portrait }
        assertEquals(Kind.HALF_SPREAD, page(harbor, "Pale Harbor 018-023-024", sizes).kind)
        assertTrue(screens(harbor, "Pale Harbor 018-", sizes).contains("[021|022][023-024][025|026]"))
    }

    // -------------------------------------------------------------- 3, 4, 36, 37

    private val gate = book(run("Iron Gate 001-", 0..30))

    @Test
    fun v03_issuePageNamesAreNotSpreads() {
        val p = page(gate, "Iron Gate 001-002")
        assertEquals(Kind.STORY, p.kind)
        assertEquals(2L, p.number)
    }

    @Test
    fun v04_defaultParityStartsAfterTheCover() {
        val expected = "[000]" + (1..29 step 2).joinToString("") { "[%03d|%03d]".format(it, it + 1) }
        assertEquals(expected, screens(gate, "Iron Gate 001-"))
    }

    @Test
    fun v36_widePageWithItsNextNumberMissingIsAWideSpread() {
        val paths = book(run("Iron Gate 001-", 0..12), run("Iron Gate 001-", 14..30))
        val sizes = sizes(paths, setOf("Iron Gate 001-012"))
        val p = page(paths, "Iron Gate 001-012", sizes)
        assertEquals(Kind.SPREAD_WIDE, p.kind)
        assertEquals(13L, p.last)
        // It votes even: 2|3 … 10|11, so the story before it starts with page 1 alone.
        assertTrue(screens(paths, "Iron Gate 001-", sizes).startsWith("[000][001][002|003]"))
        assertTrue(screens(paths, "Iron Gate 001-", sizes).contains("[010|011][012][014|015]"))
    }

    @Test
    fun v37_widePageWithItsNextNumberPresentIsWideAndPairingRestarts() {
        val sizes = sizes(gate, setOf("Iron Gate 001-012"))
        assertEquals(Kind.WIDE, page(gate, "Iron Gate 001-012", sizes).kind)
        assertTrue(screens(gate, "Iron Gate 001-", sizes).contains("[009|010][011][012][013|014]"))
    }

    // -------------------------------------------------------------- 5, 6

    @Test
    fun v05_spreadVoteOverridesTheDefault() {
        val paths = book(run("Sky Lanterns v1-", 0..1), listOf("Sky Lanterns v1-002-003.jpg"), run("Sky Lanterns v1-", 4..6))
        assertEquals(Kind.SPREAD, page(paths, "Sky Lanterns v1-002-003").kind)
        assertEquals("[000][001][002-003][004|005][006]", screens(paths, "Sky Lanterns v1-"))
    }

    @Test
    fun v06_spreadAndTrailingTag() {
        val paths = book(run("Complete-Night-Watch-", 1..31), listOf("Complete-Night-Watch-032-033.jpg"), run("Complete-Night-Watch-", 34..148), listOf("zzzz.jpg"))
        val p = page(paths, "Complete-Night-Watch-032-033")
        assertEquals(Kind.SPREAD, p.kind)
        assertEquals(32L, p.number)
        assertEquals(Kind.TAG, page(paths, "zzzz").kind)
    }

    // -------------------------------------------------------------- 7, 8, 9, 10, 11

    @Test
    fun v07_prefixedRangeIgnoresNumbersInTrailingTags() {
        val tail = " [Pub] [Digital] [t4g2x] {HQ}"
        val paths = book(run("Silver Thread - c005 (v01) - p", 186..189, suffix = tail), listOf("Silver Thread - c005 (v01) - p190-p191$tail.jpg"), run("Silver Thread - c005 (v01) - p", 192..195, suffix = tail))
        val p = page(paths, "Silver Thread - c005 (v01) - p190-p191$tail")
        assertEquals(Kind.SPREAD, p.kind)
        assertEquals(190L, p.number)
        assertEquals(191L, p.last)
    }

    private val omnibusTail = " [dig] [Old Title (1989) - c033]"
    private fun chapter(c: String, letter: String, range: IntRange) = run("Omnibus - $c (v01) - $letter", range, suffix = omnibusTail)

    @Test
    fun v08_paddingDifferenceInsideARangeIsIgnored() {
        val paths = book(chapter("c034", "p", 800..807), listOf("Omnibus - c034 (v01) - p808-0809$omnibusTail.jpg"), chapter("c034", "p", 810..815))
        val p = page(paths, "Omnibus - c034 (v01) - p808-0809$omnibusTail")
        assertEquals(Kind.SPREAD, p.kind)
        assertEquals(809L, p.last)
    }

    @Test
    fun v09_v11_prefixLetterQAndBackCover() {
        val paths = book(
            chapter("c042", "q", 1050..1055), listOf("Omnibus - c042 (v01) - q1056-1057$omnibusTail.jpg"),
            chapter("c042", "q", 1058..1064), listOf("Omnibus - c042 (v01) - q1065 [Cover]$omnibusTail.jpg")
        )
        assertEquals(Kind.SPREAD, page(paths, "Omnibus - c042 (v01) - q1056-1057$omnibusTail").kind)
        assertEquals(Kind.BACK_COVER, page(paths, "Omnibus - c042 (v01) - q1065 [Cover]$omnibusTail").kind)
    }

    @Test
    fun v10_pageZeroIsTheFrontCover() {
        val paths = book(listOf("Omnibus - c001 (v01) - p000 [Cover]$omnibusTail.jpg"), chapter("c001", "p", 1..8))
        assertEquals(Kind.FRONT_COVER, page(paths, "Omnibus - c001 (v01) - p000 [Cover]$omnibusTail").kind)
    }

    @Test
    fun chaptersSharingOneNumberingAreOneGroup() {
        val paths = book(chapter("c034", "p", 800..807), chapter("c035", "p", 808..815))
        val a = Spreads.classify(paths)
        assertNotEquals(a.pages.first().family, a.pages.last().family)
        assertEquals(a.pages.first().group, a.pages.last().group)
        // No break at the chapter change: 806|807 then 808|809 by the page numbers.
        assertTrue(screens(paths, "Omnibus - ", options = Spreads.Options(coverAlone = false)).contains("[c034 (v01) - p806$omnibusTail|c034 (v01) - p807$omnibusTail][c035 (v01) - p808$omnibusTail|"))
    }

    // -------------------------------------------------------------- 12, 13, 14

    @Test
    fun v12_plusSeparator() {
        val paths = book(run("MarigoldSketches01-1-", 1..21, pad = 2), listOf("MarigoldSketches01-1-22+23.jpg"), run("MarigoldSketches01-1-", 24..44, pad = 2))
        val p = page(paths, "MarigoldSketches01-1-22+23")
        assertEquals(Kind.SPREAD, p.kind)
        assertEquals(22L, p.number)
    }

    private val suffixed = book(run("", 1..64, suffix = "s"), listOf("065-066s.jpg"), run("", 67..97, suffix = "s"))

    @Test
    fun v13_constantSuffix() {
        val p = page(suffixed, "065-066s")
        assertEquals(Kind.SPREAD, p.kind)
        assertEquals(66L, p.last)
    }

    @Test
    fun v14_constantSuffixIsNotAnInsertedPage() {
        val p = page(suffixed, "097s")
        assertEquals(Kind.STORY, p.kind)
        assertEquals(97L, p.number)
    }

    // -------------------------------------------------------------- 15, 16, 17

    private val signal = book(
        run("Deep Signal 001 - ", 0..7), listOf("Deep Signal 001 - 008-009.jpg"), run("Deep Signal 001 - ", 10..35),
        run("Deep Signal 006 (2025) (Digital) (Group-Team) ", 0..30, pad = 5)
    )

    @Test
    fun v15_spreadInsideOneIssueOfAMergedBook() {
        assertEquals(Kind.SPREAD, page(signal, "Deep Signal 001 - 008-009").kind)
    }

    @Test
    fun v16_anotherIssueIsAnotherFamily() {
        val p = page(signal, "Deep Signal 006 (2025) (Digital) (Group-Team) 00023")
        assertEquals(Kind.STORY, p.kind)
        assertEquals(23L, p.number)
        val first = page(signal, "Deep Signal 001 - 035")
        assertNotEquals(first.family, p.family)
        assertNotEquals(first.group, p.group)
    }

    @Test
    fun v17_fourDigitRange() {
        val paths = book(run("Lost Rounds - Deluxe Edition-", 1..2, pad = 4), listOf("Lost Rounds - Deluxe Edition-0003-0004.jpg"), run("Lost Rounds - Deluxe Edition-", 5..12, pad = 4))
        assertEquals(Kind.SPREAD, page(paths, "Lost Rounds - Deluxe Edition-0003-0004").kind)
    }

    // -------------------------------------------------------------- 18, 19, 20, 21, 22

    @Test
    fun v18_dualNumberingIsNotASpread() {
        val paths = book((100..140).map { "Grand Rivals v01 (2014) (Digital) (Group)-$it-0$it.jpg" })
        val p = page(paths, "Grand Rivals v01 (2014) (Digital) (Group)-137-0137")
        assertEquals(Kind.STORY, p.kind)
        assertEquals(137L, p.number)
    }

    @Test
    fun v19_dualNumberingOffByOneIsNotASpread() {
        val paths = book((100..120).map { "2The-Story-v02-$it-0${it - 1}.jpg" })
        assertEquals(Kind.STORY, page(paths, "2The-Story-v02-108-0107").kind)
    }

    @Test
    fun v20_everyPageOneHigherIsDualNumbering() {
        val paths = book((10..30).map { "X-%03d-%04d.jpg".format(it, it + 1) })
        val a = Spreads.classify(paths)
        assertTrue(a.pages.none { it.kind == Kind.SPREAD || it.kind == Kind.HALF_SPREAD })
    }

    @Test
    fun v21_yearRangeIsNotAPageNumber() {
        val paths = book(run("Stone Weights ( 2011 -2012)-", 1..190))
        val p = page(paths, "Stone Weights ( 2011 -2012)-189")
        assertEquals(Kind.STORY, p.kind)
        assertEquals(189L, p.number)
    }

    @Test
    fun v22_aLoneRangeNameIsAnExtra() {
        val paths = book(run("Book-", 1..10), listOf("P00001 - P00500 A1.jpg"))
        assertEquals(Kind.EXTRA, page(paths, "P00001 - P00500 A1").kind)
    }

    // -------------------------------------------------------------- 23, 24, 39

    @Test
    fun v23_insertedPageStandsAloneAndKeepsParity() {
        val paths = book(run("Dry Plains-", 0..168), listOf("Dry Plains-156b.jpg"))
        assertEquals("Dry Plains-156b", Spreads.stemOf(paths[157]))
        assertEquals(Kind.INSERTED, page(paths, "Dry Plains-156b").kind)
        val s = screens(paths, "Dry Plains-")
        assertTrue(s, s.contains("[155|156][156b][157|158]"))
    }

    @Test
    fun v24_spreadRightAfterTheCover() {
        val paths = book(listOf("Dry Plains-000.jpg", "Dry Plains-001-002.jpg"), run("Dry Plains-", 3..20))
        val p = page(paths, "Dry Plains-001-002")
        assertEquals(Kind.SPREAD, p.kind)
        assertTrue(screens(paths, "Dry Plains-").startsWith("[000][001-002][003|004]"))
    }

    @Test
    fun v39_v40_naturalOrderComparesStems() {
        assertEquals(
            listOf("Dry Plains-000.jpg", "Dry Plains-001-002.jpg", "Dry Plains-003.jpg"),
            listOf("Dry Plains-003.jpg", "Dry Plains-001-002.jpg", "Dry Plains-000.jpg").sortedWith(NaturalOrder)
        )
        assertEquals(listOf("C - p999.jpg", "C - q1064.jpg"), listOf("C - q1064.jpg", "C - p999.jpg").sortedWith(NaturalOrder))
    }

    // -------------------------------------------------------------- 25, 26, 27, 28

    @Test
    fun v25_variantCoverBlock() {
        val paths = book(
            listOf("Bright Pals # 001 - 000a Cover A.jpg", "Bright Pals # 001 - 000b Cover B.jpg", "Bright Pals # 001 - 000c Cover B.jpg", "Bright Pals # 001 - 000d Cover RI.jpg"),
            run("Bright Pals # 001 - ", 1..26)
        )
        assertEquals(Kind.FRONT_COVER, page(paths, "Bright Pals # 001 - 000a Cover A").kind)
        assertEquals(Kind.VARIANT_COVER, page(paths, "Bright Pals # 001 - 000c Cover B").kind)
        assertTrue(screens(paths, "Bright Pals # 001 - ").startsWith("[000a Cover A][000b Cover B][000c Cover B][000d Cover RI][001|002]"))
    }

    @Test
    fun v26_v27_suffixedCoversSortAndClassify() {
        val covers = listOf("001a", "001b", "001b2 Cover B textless", "001c", "001d", "001e", "001k1 alt", "001k12 alt").map { "Bright Tales # 005 - $it.jpg" }
        val paths = book(covers, run("Bright Tales # 005 - ", 2..24))
        val stems = paths.map { Spreads.stemOf(it).removePrefix("Bright Tales # 005 - ") }
        assertTrue(stems.indexOf("001b") < stems.indexOf("001b2 Cover B textless"))
        assertTrue(stems.indexOf("001b2 Cover B textless") < stems.indexOf("001c"))
        assertTrue(stems.indexOf("001k1 alt") < stems.indexOf("001k12 alt"))
        assertEquals(Kind.VARIANT_COVER, page(paths, "Bright Tales # 005 - 001b2 Cover B textless").kind)
        assertEquals(Kind.FRONT_COVER, page(paths, "Bright Tales # 005 - 001a").kind)
    }

    @Test
    fun v28_underscoreCoverSortsFirst() {
        val paths = book(listOf("Bright Days # 31 - 000a.jpg", "Bright Days # 31 - 000_ Promo.jpg", "Bright Days # 31 - 000b.jpg"), run("Bright Days # 31 - ", 1..20))
        assertEquals("Bright Days # 31 - 000_ Promo", Spreads.stemOf(paths[0]))
        assertTrue(page(paths, "Bright Days # 31 - 000_ Promo").cover)
        assertTrue(page(paths, "Bright Days # 31 - 000a").cover)
    }

    // -------------------------------------------------------------- 29, 30

    @Test
    fun v29_scannerTags() {
        val tags = listOf("zzzz", "ZZZZZ", "zA-Note", "zzzQzzz", "zWave", "zztag-alpha-beta", "zztag-beta-gamma2", "zzzzz - Group-Team")
        for (tag in tags) {
            val paths = book(run("Tag Test-", 1..20), listOf("$tag.jpg"))
            assertEquals(tag, Kind.TAG, page(paths, tag).kind)
        }
        // Mid-book or lowercase second letter: not a tag.
        val paths = book(run("Tag Test-", 1..20), listOf("zombie walk.jpg"))
        assertEquals(Kind.EXTRA, page(paths, "zombie walk").kind)
    }

    @Test
    fun v30_coverGalleryPagesAreExtras() {
        val gallery = listOf("Cover Artist box", "2nd Printing B", "promo_cover_2014")
        val paths = book(run("Bright Days # 001 - ", 0..24)) + gallery.map { "$it.jpg" }
        for (g in gallery) assertEquals(g, Kind.EXTRA, page(paths, g).kind)
    }

    // -------------------------------------------------------------- 31 to 35

    @Test
    fun v31_constantNumberPairIsNotThePageNumber() {
        val paths = book(listOf("Twenty Pub 20-20 - 000a.jpg", "Twenty Pub 20-20 - 000b.jpg"), run("Twenty Pub 20-20 - ", 1..25))
        val p = page(paths, "Twenty Pub 20-20 - 025")
        assertEquals(Kind.STORY, p.kind)
        assertEquals(25L, p.number)
    }

    @Test
    fun v32_issueThenPage() {
        val paths = book(run("Brass Lab - Tales 007-", 1..20))
        val p = page(paths, "Brass Lab - Tales 007-008")
        assertEquals(Kind.STORY, p.kind)
        assertEquals(8L, p.number)
    }

    @Test
    fun v33_v34_textAfterTheRangeDoesNotMatter() {
        val three = book(run("Fine Hound 3 03-", 1..10, suffix = "_result"), listOf("Fine Hound 3 03-011-012_result.jpg"), run("Fine Hound 3 03-", 13..30, suffix = "_result"))
        val p = page(three, "Fine Hound 3 03-011-012_result")
        assertEquals(Kind.SPREAD, p.kind)
        assertEquals(11L, p.number)
        val two = book(run("Fine Hound 2 04-", 1..32, suffix = "_result"), listOf("Fine Hound 2 04-033-034.jpg"), run("Fine Hound 2 04-", 35..50, suffix = "_result"))
        assertEquals(Kind.SPREAD, page(two, "Fine Hound 2 04-033-034").kind)
    }

    @Test
    fun v35_pageNumberAfterTags() {
        val paths = book(run("Oakfield 001 (1991) (Digital-HD) (color) (Group-Team) ", 1..30))
        val p = page(paths, "Oakfield 001 (1991) (Digital-HD) (color) (Group-Team) 025")
        assertEquals(Kind.STORY, p.kind)
        assertEquals(25L, p.number)
    }

    // -------------------------------------------------------------- options and fallbacks

    @Test
    fun firstPageAloneThenPairsFromTheSecond() {
        val paths = book(run("page", 1..7, pad = 2))
        assertEquals("[01][02|03][04|05][06|07]", screens(paths, "page"))
    }

    @Test
    fun coverAloneOffPairsTheFirstPage() {
        val paths = book(run("page", 1..7, pad = 2))
        assertEquals("[01|02][03|04][05|06][07]", screens(paths, "page", options = Spreads.Options(coverAlone = false)))
    }

    @Test
    fun shiftMovesPairingByOnePage() {
        val paths = book(run("page", 1..7, pad = 2))
        assertEquals("[01][02][03|04][05|06][07]", screens(paths, "page", options = Spreads.Options(shift = true)))
        assertEquals("[000][001][002|003][004|005][006]", screens(gate.take(7), "Iron Gate 001-", options = Spreads.Options(shift = true)))
    }

    @Test
    fun unnumberedPagesPairByPosition() {
        val paths = listOf("alpha.jpg", "bravo.jpg", "charlie.jpg", "delta.jpg", "echo.jpg")
        assertEquals("[alpha][bravo|charlie][delta|echo]", screens(paths, ""))
    }

    @Test
    fun wideFirstPageIsAloneAndNotPairedWithPageTwo() {
        val paths = book(run("Fold Test 01 - ", 1..9, pad = 2))
        val sizes = sizes(paths, setOf("Fold Test 01 - 01"))
        assertEquals(Kind.WIDE, page(paths, "Fold Test 01 - 01", sizes).kind)
        assertTrue(screens(paths, "Fold Test 01 - ", sizes).startsWith("[01][02|03]"))
        assertTrue(screens(paths, "Fold Test 01 - ", sizes, Spreads.Options(coverAlone = false)).startsWith("[01][02|03]"))
    }

    @Test
    fun wideCoverIsAloneWithFirstPageAloneOff() {
        val paths = book(run("Fold Test 02 - ", 0..9))
        val sizes = sizes(paths, setOf("Fold Test 02 - 000"))
        assertEquals(Kind.FRONT_COVER, page(paths, "Fold Test 02 - 000", sizes).kind)
        assertTrue(screens(paths, "Fold Test 02 - ", sizes, Spreads.Options(coverAlone = false)).startsWith("[000][001|002]"))
    }

    @Test
    fun widePageNamedAsCoverIsAloneWithFirstPageAloneOff() {
        val paths = book(listOf("Fold Test 03 - 01 cover.jpg"), run("Fold Test 03 - ", 2..9, pad = 2))
        val sizes = sizes(paths, setOf("Fold Test 03 - 01 cover"))
        assertEquals(Kind.FRONT_COVER, page(paths, "Fold Test 03 - 01 cover", sizes).kind)
        assertTrue(screens(paths, "Fold Test 03 - ", sizes, Spreads.Options(coverAlone = false)).startsWith("[01 cover][02|03]"))
    }

    @Test
    fun folderChangeBreaksPairing() {
        val paths = book(run("one/page", 1..3, pad = 2), run("two/page", 1..4, pad = 2))
        val views = Spreads.pair(Spreads.classify(paths), Spreads.Options(coverAlone = false))
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4), listOf(5, 6)), views.map { it.toList() })
    }

    @Test
    fun tallPagesNeverShareAScreen() {
        val paths = book(run("page", 1..6, pad = 2))
        val views = Spreads.pair(Spreads.classify(paths), Spreads.Options(coverAlone = false, alone = setOf(2)))
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4), listOf(5)), views.map { it.toList() })
    }

    @Test
    fun mostlyWideBookIsReadOneAtATime() {
        val paths = book(run("strip", 1..10, pad = 2))
        val a = Spreads.classify(paths, paths.map { wide })
        assertTrue(a.landscape)
        assertEquals(10, Spreads.pair(a).size)
        assertFalse(a.splittable(4))
    }

    @Test
    fun onlyWideSpreadsAfterTheFirstPageSplit() {
        val sizes = sizes(gate, setOf("Iron Gate 001-000", "Iron Gate 001-012"))
        val a = Spreads.classify(gate, sizes)
        assertTrue(a.splittable(12))
        assertFalse(a.splittable(0))
        assertFalse(a.splittable(11))
        assertTrue(a.wide(0))
    }

    @Test
    fun everyPageIsShownOnceInOrder() {
        for (paths in listOf(harbor, gate, signal, suffixed)) {
            val views = Spreads.pair(Spreads.classify(paths))
            assertEquals(paths.indices.toList(), views.flatMap { it.toList() })
        }
    }

    // -------------------------------------------------------------- seams

    private val match = SpreadSeams.Verdict(SpreadSeams.Seam.MATCH, 0.9)
    private val weakMatch = SpreadSeams.Verdict(SpreadSeams.Seam.MATCH, 0.4)
    private val noMatch = SpreadSeams.Verdict(SpreadSeams.Seam.NO_MATCH, 1.0)

    /** Seams after each of [count] pages: [matches] at the pages given, the rest not matching. */
    private fun seams(count: Int, matches: Map<Int, SpreadSeams.Verdict>): List<SpreadSeams.Verdict?> =
        List(count) { matches[it] ?: noMatch }

    @Test
    fun aMatchingSeamAcrossTwoScreensShiftsTheRun() {
        val paths = book(run("page", 1..9, pad = 2))
        assertEquals("[01][02|03][04|05][06|07][08|09]", screens(paths, "page"))
        // page03 runs on into page04: they share a screen, the reader being near.
        val options = Spreads.Options(seams = seams(paths.size, mapOf(2 to match)), visited = setOf(0))
        assertEquals("[01][02][03|04][05|06][07|08][09]", screens(paths, "page", options = options))
        // A seam that agrees with the pairing changes nothing.
        assertEquals("[01][02|03][04|05][06|07][08|09]", screens(paths, "page", options = Spreads.Options(seams = seams(paths.size, mapOf(3 to match)))))
    }

    @Test
    fun aWeakLoneMatchOrMixedMatchesChangeNothing() {
        val paths = book(run("page", 1..9, pad = 2))
        val plain = "[01][02|03][04|05][06|07][08|09]"
        assertEquals(plain, screens(paths, "page", options = Spreads.Options(seams = seams(paths.size, mapOf(2 to weakMatch)))))
        // Two say shift, one says not: not three times as many.
        assertEquals(plain, screens(paths, "page", options = Spreads.Options(seams = seams(paths.size, mapOf(2 to match, 4 to match, 3 to match)))))
        // Two weak ones together do tell.
        assertEquals("[01][02][03|04][05|06][07|08][09]", screens(paths, "page", options = Spreads.Options(seams = seams(paths.size, mapOf(2 to weakMatch, 4 to weakMatch)))))
    }

    @Test
    fun aLoneStrongMatchSettlesItsRunOnlyNearTheReader() {
        val paths = book(run("page", 1..30, pad = 2))
        val plain = screens(paths, "page")
        assertTrue(plain.startsWith("[01][02|03][04|05]"))
        // page21 runs on into page22, against the pairing by number.
        val lone = Spreads.Options(seams = seams(paths.size, mapOf(20 to match)))
        assertEquals(plain, screens(paths, "page", options = lone))
        assertEquals(plain, screens(paths, "page", options = lone.copy(visited = setOf(0, 1, 2))))
        val shifted = "[01][02][03|04][05|06]"
        assertTrue(screens(paths, "page", options = lone.copy(visited = setOf(20 - Spreads.NEAR))).startsWith(shifted))
        assertTrue(screens(paths, "page", options = lone.copy(visited = setOf(21 + Spreads.NEAR))).startsWith(shifted))
        assertEquals(plain, screens(paths, "page", options = lone.copy(visited = setOf(22 + Spreads.NEAR))))
        // A second match settles it wherever the reader is.
        val two = Spreads.Options(seams = seams(paths.size, mapOf(20 to match, 24 to weakMatch)))
        assertTrue(screens(paths, "page", options = two).startsWith(shifted))
    }

    @Test
    fun theManualShiftStillFlipsASeamCorrection() {
        val paths = book(run("page", 1..9, pad = 2))
        val options = Spreads.Options(shift = true, seams = seams(paths.size, mapOf(2 to match)), visited = setOf(0))
        assertEquals("[01][02|03][04|05][06|07][08|09]", screens(paths, "page", options = options))
    }

    @Test
    fun aShiftFromBeforeSeamsFlipsThePairingByNamesAlone() {
        val paths = book(run("page", 1..9, pad = 2))
        val shifted = "[01][02][03|04][05|06][07|08][09]"
        for (at in listOf(2, 3)) {
            val options = Spreads.Options(shift = true, seams = seams(paths.size, mapOf(at to match)), shiftByNames = true)
            assertEquals(shifted, screens(paths, "page", options = options))
        }
        val unnumbered = listOf("alpha.jpg", "bravo.jpg", "charlie.jpg", "delta.jpg", "echo.jpg", "foxtrot.jpg")
        val options = Spreads.Options(shift = true, seams = seams(unnumbered.size, mapOf(1 to match)), shiftByNames = true)
        assertEquals("[alpha][bravo][charlie|delta][echo|foxtrot]", screens(unnumbered, "", options = options))
    }

    @Test
    fun seamsOutrankSpreadVotesFromElsewhere() {
        // The spread votes odd left pages; after it, page 026 runs on into 027.
        val at = harbor.indexOfFirst { Spreads.stemOf(it) == "Pale Harbor 018-026" }
        val options = Spreads.Options(seams = seams(harbor.size, mapOf(at to match)), visited = setOf(at))
        assertTrue(screens(harbor, "Pale Harbor 018-", options = options).endsWith("[023-024][025][026|027][028|029][030][zWave]"))
        assertTrue(screens(harbor, "Pale Harbor 018-", options = options).startsWith("[000][001|002]"))
    }

    @Test
    fun unnumberedPagesShiftByTheirSeams() {
        val paths = listOf("alpha.jpg", "bravo.jpg", "charlie.jpg", "delta.jpg", "echo.jpg", "foxtrot.jpg")
        assertEquals("[alpha][bravo|charlie][delta|echo][foxtrot]", screens(paths, ""))
        val options = Spreads.Options(seams = seams(paths.size, mapOf(2 to match)), visited = setOf(0))
        assertEquals("[alpha][bravo][charlie|delta][echo|foxtrot]", screens(paths, "", options = options))
        assertEquals("[alpha][bravo|charlie][delta|echo][foxtrot]", screens(paths, "", options = options.copy(shift = true)))
    }

    @Test
    fun mergeShowsMatchingPagesAsOne() {
        val paths = book(run("page", 1..7, pad = 2))
        val a = Spreads.classify(paths)
        val options = Spreads.Options(seams = seams(paths.size, mapOf(2 to match, 3 to match)))
        val views = Spreads.merge(a, options)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4), listOf(5), listOf(6)), views.map { it.toList() })
        assertEquals(setOf(2), Spreads.joined(views, options))
        // Without matches, every page alone.
        assertEquals(paths.indices.map { listOf(it) }, Spreads.merge(a, Spreads.Options()).map { it.toList() })
    }

    @Test
    fun mergeLeavesCoversAndWidePagesAlone() {
        val paths = book(run("page", 1..6, pad = 2))
        val sizes = paths.map { if (Spreads.stemOf(it) == "page04") wide else portrait }
        val a = Spreads.classify(paths, sizes)
        val options = Spreads.Options(seams = seams(paths.size, mapOf(0 to match, 2 to match, 3 to match)))
        assertEquals(paths.indices.map { listOf(it) }, Spreads.merge(a, options).map { it.toList() })
        // Covers join their neighbor only without "cover alone".
        assertEquals(listOf(0, 1), Spreads.merge(a, options.copy(coverAlone = false)).first().toList())
    }

    @Test
    fun pairsThatRunOnAreJoined() {
        val paths = book(run("page", 1..9, pad = 2))
        val options = Spreads.Options(seams = seams(paths.size, mapOf(3 to match)))
        val views = Spreads.pair(Spreads.classify(paths), options)
        assertEquals(setOf(2), Spreads.joined(views, options))
    }
}
