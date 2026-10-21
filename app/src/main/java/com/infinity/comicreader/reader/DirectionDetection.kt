package com.infinity.comicreader.reader

import com.infinity.comicreader.data.ReadingDirection
import java.util.Locale

/**
 * Works out a book's reading direction from what it carries, strongest signal first:
 *  1. ComicInfo `Manga=YesAndRightToLeft`;
 *  2. a wraparound cover: the front half on the left reads right to left, on the right left to right;
 *  3. a manga publisher, from ComicInfo `Publisher` or a bracketed tag in the file or page names;
 *  4. manga-style page names (`c001 (v01) - p000`), as a last resort.
 * Null when nothing tells. Free of Android types.
 */
object DirectionDetection {

    /** Which signal decided. */
    enum class Source(val key: Int) { COMIC_INFO(0), COVER(1), PUBLISHER(2), NAMING(3) }

    /** A detected [direction], from [source]; [detail] names the publisher when that decided. */
    data class Detected(val direction: ReadingDirection, val source: Source, val detail: String? = null) {
        /** One number for storage: 0 never occurs, so it can stand for "nothing detected". */
        fun encode(): Int = 1 + direction.ordinal + 2 * source.key

        companion object {
            /** The detection [encode] gave [value]; null for 0 or anything unknown. */
            fun decode(value: Int): Detected? {
                if (value <= 0) return null
                val direction = ReadingDirection.entries.getOrNull((value - 1) % 2) ?: return null
                val source = Source.entries.firstOrNull { it.key == (value - 1) / 2 } ?: return null
                return Detected(direction, source)
            }
        }
    }

    /**
     * The direction of the book [fileName] with pages [pageNames] and [info]. [cover] is
     * asked only when the stronger signals say nothing: it gives the front half of a
     * wraparound first page, or null when there is none or it cannot tell.
     */
    fun detect(
        info: ComicInfo?,
        fileName: String,
        pageNames: List<String>,
        cover: () -> Wraparound.Half? = { null }
    ): Detected? {
        if (info?.manga == ComicInfo.Manga.YES_RTL) return Detected(ReadingDirection.RTL, Source.COMIC_INFO)
        when (cover()) {
            Wraparound.Half.LEFT -> return Detected(ReadingDirection.RTL, Source.COVER)
            Wraparound.Half.RIGHT -> return Detected(ReadingDirection.LTR, Source.COVER)
            null -> Unit
        }
        val publisher = MangaPublishers.match(info?.publisher)
            ?: (sequenceOf(fileName) + pageNames.asSequence()).flatMap { bracketTags(it) }.firstNotNullOfOrNull { MangaPublishers.match(it) }
        if (publisher != null) return Detected(ReadingDirection.RTL, Source.PUBLISHER, publisher)
        if (isMangaNaming(pageNames)) return Detected(ReadingDirection.RTL, Source.NAMING)
        return null
    }

    private val BRACKETS = Regex("""\[([^\[\]]{2,60})]""")

    /** The texts of `[...]` tags in the file name of [path]. */
    internal fun bracketTags(path: String): Sequence<String> =
        BRACKETS.findAll(path.substringAfterLast('/').substringAfterLast('\\')).map { it.groupValues[1] }

    /** A chapter number, then later a page number: `c012 (v02) - p034`, `c012.5 - p001`. */
    private val MANGA_NAME = Regex("""(?i)(?:^|[^a-z0-9])c\d{1,4}(?:[.x]\d+)?(?:[^a-z0-9]).*(?:^|[^a-z0-9])p\d{1,4}(?:[^0-9]|$)""")

    /** True when most page names, at least [MIN_NAMED_PAGES], follow manga scan naming. */
    fun isMangaNaming(pageNames: List<String>): Boolean {
        if (pageNames.size < MIN_NAMED_PAGES) return false
        val named = pageNames.count { MANGA_NAME.containsMatchIn(it.substringAfterLast('/').substringAfterLast('\\')) }
        return named >= MIN_NAMED_PAGES && named * 2 > pageNames.size
    }

    private const val MIN_NAMED_PAGES = 3
}

/**
 * Publishers of manga (read right to left), matched by whole words from the start
 * of a name, without regard to case or punctuation: "VIZ" matches `VIZ Media LLC`,
 * "Kodansha" matches `[Kodansha Comics]`. Publishers that also print western
 * comics, or Korean and Chinese comics (read left to right), are left out.
 */
object MangaPublishers {
    val NAMES = listOf(
        // English-language
        "Kodansha", "VIZ", "Shonen Jump", "Yen Press", "Yen On", "Seven Seas", "Ghost Ship", "Vertical",
        "Square Enix", "Tokyopop", "Del Rey Manga", "Digital Manga", "SuBLime", "One Peace Books",
        "Denpa", "J-Novel Club", "Kaiten Books", "Glacier Bay Books", "Manga Planet", "Azuki",
        "Dark Horse Manga", "Titan Manga",
        // Japanese
        "Shueisha", "Shogakukan", "Hakusensha", "Akita Shoten", "Futabasha", "Kadokawa", "ASCII Media Works",
        "Enterbrain", "Houbunsha", "Shinchosha", "Takeshobo", "Mag Garden", "Ichijinsha", "Media Factory",
        "Coamix", "Leed",
        // European
        "Kana", "Pika", "Ki-oon", "Kurokawa", "Carlsen Manga", "Egmont Manga", "Panini Manga", "Planet Manga",
        "J-Pop", "Waneko", "JPF", "Japonica Polonica Fantastica", "Studio JG", "Kotori", "Hanami", "Dango"
    )

    private val WORDS = NAMES.map { words(it) to it }

    /** The listed publisher [text] names; null when it names none. */
    fun match(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val words = words(text)
        if (words.isEmpty()) return null
        return WORDS.firstOrNull { (name, _) -> words.size >= name.size && name.indices.all { words[it] == name[it] } }?.second
    }

    private fun words(text: String): List<String> =
        text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotEmpty() }
}
