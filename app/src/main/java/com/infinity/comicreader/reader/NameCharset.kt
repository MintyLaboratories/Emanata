package com.infinity.comicreader.reader

import com.infinity.comicreader.data.BookId
import com.infinity.comicreader.data.BookIds
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * The charset an archive's file names are read in: the one a setting names, or for
 * [AUTO], UTF-8 when the archive marks its names as UTF-8 or they all decode cleanly
 * as UTF-8, else the one found in the names that do not ([guess]).
 * A book may have a choice of its own ([overrides]).
 */
object NameCharset {
    const val AUTO = "auto"

    /**
     * A file name as stored: its [bytes], and whether the archive gives it as Unicode
     * ([utf8]): marked UTF-8, or also given as [unicode] (an Info-ZIP Unicode Path extra field).
     */
    class Raw(val bytes: ByteArray, val utf8: Boolean, val unicode: String? = null)

    /** Names the charset of [text] (file names, one a line), or null when it finds none. */
    fun interface Detector {
        fun detect(text: ByteArray): String?
    }

    /**
     * [guess] with the device's language. Android's ICU `CharsetDetector` is not in the SDK,
     * and the system refuses it to apps targeting API 28 and later.
     */
    val guesser = Detector { text -> guess(text) }

    /**
     * The charset, of those the setting offers, that reads [text] cleanly and most like
     * names in a script: kana for Japanese, Hangul for Korean, ideographs for Chinese,
     * words of Cyrillic letters, accented Latin letters among plain ones. Where scripts tie
     * (Korean and Chinese bytes decode as both), the one of the device's [language] wins,
     * else the earlier offered.
     */
    fun guess(text: ByteArray, language: String = Locale.getDefault().language): String? {
        var best: String? = null
        var bestScore = 0.0
        for (name in GUESSES) {
            val decoded = decode(text, name) ?: continue
            var sum = 0
            var count = 0
            for (i in decoded.indices) {
                if (decoded[i].code < 0x80) continue
                count++
                sum += scoreAt(decoded, i)
            }
            if (count == 0) continue
            val score = sum.toDouble() / count + if (LANGUAGES[name] == language) TIE_BREAK else 0.0
            if (score > bestScore) {
                best = name
                bestScore = score
            }
        }
        return best
    }

    /**
     * How much the character at [i] of [s] looks like part of a name: kana most; ideographs,
     * Hangul and Cyrillic letters next to another; then an accented Latin letter, less
     * so in a run of them (as other scripts read wrongly make); symbols and odd forms below 0.
     */
    private fun scoreAt(s: String, i: Int): Int {
        fun near(range: IntRange) = (i > 0 && s[i - 1].code in range) || (i < s.lastIndex && s[i + 1].code in range)
        return when (s[i].code) {
            in 0x3040..0x30FF -> 4
            in 0xAC00..0xD7A3, in 0x4E00..0x9FFF -> 3
            in 0x0400..0x04FF -> if (near(0x0400..0x04FF)) 3 else 0
            in 0x00C0..0x00FF -> if (near(0x0080..0x00FF)) 1 else 2
            in 0x3000..0x303F, in 0xFF01..0xFF5E -> 1
            in 0xFF61..0xFF9F, in 0x2500..0x259F, in 0xE000..0xF8FF -> -3
            in 0x0080..0x00BF, in 0x2000..0x24FF -> -1
            else -> 0
        }
    }

    /** The charsets [guess] tries, as the setting offers them; the earlier wins a tie. */
    private val GUESSES = listOf("Shift_JIS", "GBK", "CP949", "Big5", "windows-1251", "windows-1252", "IBM437")

    /** The language each charset of [GUESSES] that shares bytes with another is written in. */
    private val LANGUAGES = mapOf("Shift_JIS" to "ja", "GBK" to "zh", "CP949" to "ko", "Big5" to "zh")

    /** What the device's language adds to a charset's score: less than any script tells apart. */
    private const val TIE_BREAK = 0.25

    /** [text] in the charset [name] gives, or null when it does not decode cleanly. */
    private fun decode(text: ByteArray, name: String): String? {
        val charset = charset(name) ?: return null
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(text)).toString()
                .takeIf { s -> s.none { it.code == 0xFFFD || (it.code < 0x20 && it != '\n') } }
        } catch (e: CharacterCodingException) {
            null
        }
    }

    /** Each book's own choice ([AUTO] or a charset name), by [BookId]; set from the library. */
    @Volatile
    var overrides: Map<BookId, String> = emptyMap()

    /** What book [id] reads its names with: its own choice, else [setting]. */
    fun forBook(id: BookId?, setting: String): String = id?.let { overrides[it] } ?: setting

    /** What the book [file] reads its names with: its own choice when its ID is known ([BookIds.known]), else [setting]. */
    fun forBook(file: File, setting: String): String = if (overrides.isEmpty()) setting else forBook(BookIds.known(file), setting)

    /**
     * The charset [setting] gives for an archive of [names] (asked for only for [AUTO]);
     * a name no charset here reads gives UTF-8.
     */
    fun resolve(setting: String, names: () -> List<Raw>, detector: Detector = guesser): Charset {
        if (setting != AUTO) return charset(setting) ?: Charsets.UTF_8
        val strays = names().filter { !it.utf8 && !isUtf8(it.bytes) }
        if (strays.isEmpty()) return Charsets.UTF_8
        val text = ByteArrayOutputStream()
        for (raw in strays) {
            text.write(raw.bytes)
            text.write('\n'.code)
        }
        return detector.detect(text.toByteArray())?.let { charset(it) } ?: FALLBACK
    }

    /**
     * [names] read as ISO-8859-1 (byte for byte) read again with what [AUTO] finds in them;
     * a name holding a character beyond ISO-8859-1 was given as Unicode and stays. Gives
     * the names with the charset found.
     */
    fun fromLatin1(names: List<String>, detector: Detector = guesser): Pair<List<String>, Charset> {
        val bytes = names.map { n -> if (n.any { it.code > 0xFF }) null else n.toByteArray(Charsets.ISO_8859_1) }
        if (bytes.all { it == null || it.all { b -> b >= 0 } }) return names to Charsets.UTF_8
        val charset = resolve(AUTO, { bytes.mapIndexed { i, b -> Raw(b ?: names[i].toByteArray(), utf8 = b == null) } }, detector)
        return names.mapIndexed { i, n -> bytes[i]?.let { String(it, charset) } ?: n } to charset
    }

    /** True when [bytes] are well-formed UTF-8. */
    fun isUtf8(bytes: ByteArray): Boolean {
        if (bytes.all { it >= 0 }) return true
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (e: CharacterCodingException) {
            false
        }
    }

    /**
     * The charset [name] stands for, widened to the superset Windows wrote names in
     * (Shift_JIS to Windows-31J, EUC-KR to CP949, ISO-8859-1 to Windows-1252), or null
     * when none of its names is known here.
     */
    fun charset(name: String): Charset? {
        val names = WIDER[name.uppercase()] ?: listOf(name)
        for (n in names) {
            val found = runCatching { Charset.forName(n) }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    /** Names, by upper-case name, to try in turn: the widest first. */
    private val WIDER = mapOf(
        "SHIFT_JIS" to listOf("windows-31j", "Shift_JIS"),
        "EUC-KR" to listOf("x-windows-949", "windows-949", "MS949", "EUC-KR"),
        "CP949" to listOf("x-windows-949", "windows-949", "MS949", "CP949", "EUC-KR"),
        "GBK" to listOf("GBK", "GB18030"),
        "ISO-8859-1" to listOf("windows-1252", "ISO-8859-1"),
        "IBM437" to listOf("IBM437", "cp437")
    )

    /** What zip's own rules assume of unmarked names, when nothing better is found. */
    private val FALLBACK: Charset = charset("IBM437") ?: Charsets.ISO_8859_1
}
