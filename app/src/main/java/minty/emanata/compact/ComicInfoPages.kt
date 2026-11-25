package minty.emanata.compact

import minty.emanata.reader.ComicInfo
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * A ComicInfo.xml brought in line with its rebuilt book: `PageCount` set to the new page
 * count where it is given, the `<Page>` of each page left out removed, the others' `Image`
 * renumbered, a page made anew given its new `ImageSize` and, where its pixel size changed,
 * no `ImageWidth`/`ImageHeight`. The text is edited where it stands: every other byte, the
 * encoding, the byte order mark and the line endings stay as they are. Names match without
 * regard to case, as [ComicInfo] reads them; comments, CDATA and declarations are left alone.
 */
object ComicInfoPages {
    /** A page made anew: its new byte [size], and whether its pixel size changed or is unknown ([resized]). */
    class Remade(val size: Long, val resized: Boolean)

    private val PAGE_COUNT = Pattern.compile("<PageCount(?:\\s[^>]*)?>([^<]*)</PageCount\\s*>", Pattern.CASE_INSENSITIVE)
    private val PAGE = Pattern.compile("<Page(?=[\\s/>])", Pattern.CASE_INSENSITIVE)
    private val ATTRIBUTE = Pattern.compile("\\s+([^\\s=/>]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
    private val TAG_END = Pattern.compile("\\s*(/?)>")
    private val PAGE_END = Pattern.compile("</Page\\s*>", Pattern.CASE_INSENSITIVE)
    private val UNTOUCHED = Pattern.compile("<!--.*?-->|<!\\[CDATA\\[.*?]]>|<\\?.*?\\?>|<!DOCTYPE[^\\[>]*(?:\\[.*?])?[^>]*>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)

    /** One edit of the text: chars [start] until [end] become [text]. */
    private class Edit(val start: Int, val end: Int, val text: String)

    /** An attribute of a tag: [start] at the white space before it, [valueStart] until [valueEnd] inside its quotes. */
    private class Attribute(val name: String, val start: Int, val end: Int, val valueStart: Int, val valueEnd: Int)

    /**
     * [xml] for a book whose page `i` is now page [moves]`[i]` (-1: left out) of [pageCount],
     * with the pages [remade] by their old places; [xml] itself when nothing changes or it
     * is in an encoding whose markup is neither ASCII nor UTF-16.
     */
    fun edit(xml: ByteArray, moves: IntArray, pageCount: Int, remade: Map<Int, Remade>): ByteArray {
        val view = viewOf(xml) ?: return xml
        val unit = if (view == Charsets.ISO_8859_1) 1 else 2
        val text = String(xml, view)
        val skipped = UNTOUCHED.matcher(text).let { m -> generateSequence { if (m.find()) m.start() until m.end() else null }.toList() }
        fun untouched(at: Int) = skipped.any { at in it }
        val edits = ArrayList<Edit>()
        val count = PAGE_COUNT.matcher(text)
        while (count.find()) {
            if (untouched(count.start())) continue
            number(text, count.start(1), count.end(1), pageCount.toLong())?.let { edits += it }
        }
        val page = PAGE.matcher(text)
        while (page.find()) {
            if (!untouched(page.start())) edits += pageEdits(text, page.start(), page.end(), moves, remade)
        }
        if (edits.isEmpty()) return xml
        edits.sortBy { it.start }
        val out = ByteArrayOutputStream(xml.size)
        var at = 0
        for (e in edits) {
            out.write(xml, at, e.start * unit - at)
            out.write(e.text.toByteArray(view))
            at = minOf(e.end * unit, xml.size)
        }
        out.write(xml, at, xml.size - at)
        return out.toByteArray()
    }

    /**
     * The charset in which [xml]'s chars stand at fixed byte places: UTF-16 by its byte order
     * mark or its `<?`, else ISO-8859-1 when its encoding writes ASCII as ASCII (UTF-8 and
     * most others: markup bytes are then the same); null for any other.
     */
    private fun viewOf(xml: ByteArray): Charset? {
        fun at(i: Int) = if (i < xml.size) xml[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xFE && at(1) == 0xFF || at(0) == 0 && at(1) == 0x3C && at(2) == 0 && at(3) == 0x3F -> Charsets.UTF_16BE
            at(0) == 0xFF && at(1) == 0xFE || at(0) == 0x3C && at(1) == 0 && at(2) == 0x3F && at(3) == 0 -> Charsets.UTF_16LE
            else -> {
                val charset = ComicInfo.charsetOf(xml)
                val probe = "<Page Image=\"0\"/>"
                if (probe.toByteArray(charset).contentEquals(probe.toByteArray(Charsets.ISO_8859_1))) Charsets.ISO_8859_1 else null
            }
        }
    }

    /** The edits of the `<Page` element opening at [start] (its name ending at [nameEnd]); none when it is not well formed or has no number `Image`. */
    private fun pageEdits(text: String, start: Int, nameEnd: Int, moves: IntArray, remade: Map<Int, Remade>): List<Edit> {
        val attributes = ArrayList<Attribute>()
        val m = ATTRIBUTE.matcher(text)
        var at = nameEnd
        while (lookingAt(m, at)) {
            val quoted = if (m.start(3) >= 0) 3 else 4
            attributes += Attribute(text.substring(m.start(1), m.end(1)), m.start(), m.end(), m.start(quoted), m.end(quoted))
            at = m.end()
        }
        val tagEnd = TAG_END.matcher(text)
        if (!lookingAt(tagEnd, at)) return emptyList()
        val end = if (tagEnd.group(1) == "/") tagEnd.end() else PAGE_END.matcher(text).let { if (it.find(tagEnd.end())) it.end() else return emptyList() }
        fun attribute(name: String) = attributes.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val image = attribute("Image") ?: return emptyList()
        val old = trimmed(text, image.valueStart, image.valueEnd).let { (s, e) -> text.substring(s, e).toIntOrNull() }?.takeIf { it in moves.indices } ?: return emptyList()
        val new = moves[old]
        if (new < 0) return listOf(Edit(removalStart(text, start), end, ""))
        val edits = ArrayList<Edit>()
        number(text, image.valueStart, image.valueEnd, new.toLong())?.let { edits += it }
        val made = remade[old] ?: return edits
        attribute("ImageSize")?.let { a -> number(text, a.valueStart, a.valueEnd, made.size)?.let { edits += it } }
        if (made.resized) {
            for (name in listOf("ImageWidth", "ImageHeight")) attributes.filter { it.name.equals(name, ignoreCase = true) }.forEach { edits += Edit(it.start, it.end, "") }
        }
        return edits
    }

    /** True when [m] matches right at [at]. */
    private fun lookingAt(m: Matcher, at: Int): Boolean = m.region(at, m.regionEnd()).lookingAt()

    /** XML's white space. */
    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n'

    /**
     * Where removing an element starting at [start] begins: with the white space before it,
     * from its line's break when on a line of its own, so no blank line stays.
     */
    private fun removalStart(text: String, start: Int): Int {
        var from = start
        while (from > 0 && isSpace(text[from - 1])) from--
        val lineBreak = (from until start).lastOrNull { text[it] == '\n' || text[it] == '\r' } ?: return from
        return if (text[lineBreak] == '\n' && lineBreak > from && text[lineBreak - 1] == '\r') lineBreak - 1 else lineBreak
    }

    /** The edit making the number in chars [start] until [end] (white space around it kept) [value]; null when it is that already. */
    private fun number(text: String, start: Int, end: Int, value: Long): Edit? {
        val (s, e) = trimmed(text, start, end)
        if (text.substring(s, e).toLongOrNull() == value) return null
        return Edit(s, e, value.toString())
    }

    /** Chars [start] until [end] of [text] without the white space around them. */
    private fun trimmed(text: String, start: Int, end: Int): Pair<Int, Int> {
        var s = start
        var e = end
        while (s < e && isSpace(text[s])) s++
        while (e > s && isSpace(text[e - 1])) e--
        return s to e
    }
}
