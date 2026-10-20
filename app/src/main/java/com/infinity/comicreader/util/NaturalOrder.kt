package com.infinity.comicreader.util

import java.text.Normalizer

/**
 * Natural ordering comparator for names and archive paths:
 * - paths compare component by component (`/` or `\`), so a folder's pages stay
 *   together and a folder sorts before its longer-named siblings;
 * - the last components compare stem first, then extension (`X-156.jpg` before
 *   `X-156-157.jpg`);
 * - ASCII digit runs compare numerically, ignoring leading zeros;
 * - other text compares case-insensitively by code point after NFC, and a name
 *   that ends first sorts first (`156` < `156b` < `157`, `000_` < `000a`);
 * - ties fall back to the raw strings, so the order is total and deterministic.
 *
 * Built from a lambda (rather than an `object : Comparator`) because [Comparator] is
 * a functional interface.
 */
val NaturalOrder: Comparator<String> = Comparator { a, b -> compareNatural(a, b) }

private fun compareNatural(a: String, b: String): Int {
    val c = comparePaths(nfc(a), nfc(b))
    return if (c != 0) c else a.compareTo(b)
}

private fun nfc(s: String): String =
    if (s.all { it.code < 0x80 }) s else Normalizer.normalize(s, Normalizer.Form.NFC)

private fun comparePaths(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (true) {
        val ea = endOfComponent(a, i)
        val eb = endOfComponent(b, j)
        val c = compareComponents(a, i, ea, b, j, eb)
        if (c != 0) return c
        val moreA = ea < a.length
        val moreB = eb < b.length
        if (!moreA || !moreB) return moreA.compareTo(moreB)
        i = ea + 1
        j = eb + 1
    }
}

private fun endOfComponent(s: String, from: Int): Int {
    var k = from
    while (k < s.length && s[k] != '/' && s[k] != '\\') k++
    return k
}

/**
 * Compares the components `a[ai, ae)` and `b[bi, be)`: stem, then extension. Only the
 * last component of a path has an extension; a folder name is all stem.
 */
private fun compareComponents(a: String, ai: Int, ae: Int, b: String, bi: Int, be: Int): Int {
    val da = stemEnd(a, ai, ae)
    val db = stemEnd(b, bi, be)
    val stems = compareText(a, ai, da, b, bi, db)
    if (stems != 0) return stems
    return compareText(a, minOf(da + 1, ae), ae, b, minOf(db + 1, be), be)
}

/** End of the stem of the component `s[from, end)`: its extension dot when it is a file name. */
private fun stemEnd(s: String, from: Int, end: Int): Int {
    if (end != s.length) return end
    val dot = s.lastIndexOf('.')
    return if (dot > from) dot else end
}

private fun compareText(a: String, ai: Int, an: Int, b: String, bi: Int, bn: Int): Int {
    var i = ai
    var j = bi
    while (i < an && j < bn) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
            val ni = endOfDigitRun(a, i, an)
            val nj = endOfDigitRun(b, j, bn)
            val comparison = compareDigitRuns(a, i, ni, b, j, nj)
            if (comparison != 0) return comparison
            i = ni
            j = nj
        } else {
            val comparison = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (comparison != 0) return comparison
            i++
            j++
        }
    }
    return (an - i).compareTo(bn - j)
}

private fun Char.isAsciiDigit() = this in '0'..'9'

/** Index just past the run of digits in [s] starting at [from], at most [end]. */
private fun endOfDigitRun(s: String, from: Int, end: Int): Int {
    var k = from
    while (k < end && s[k].isAsciiDigit()) k++
    return k
}

/** Numeric comparison of the digit runs `a[ai, an)` and `b[bi, bn)`, ignoring leading zeros. */
private fun compareDigitRuns(a: String, ai: Int, an: Int, b: String, bi: Int, bn: Int): Int {
    var i = ai
    var j = bi
    while (i < an && a[i] == '0') i++
    while (j < bn && b[j] == '0') j++
    if (an - i != bn - j) return (an - i).compareTo(bn - j)
    while (i < an) {
        if (a[i] != b[j]) return a[i].compareTo(b[j])
        i++
        j++
    }
    return 0
}
