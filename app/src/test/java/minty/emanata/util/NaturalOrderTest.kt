package minty.emanata.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {

    private fun sorted(vararg names: String) = names.sortedWith(NaturalOrder)

    /** [names] are already in natural order, whatever order they arrive in. */
    private fun assertOrder(vararg names: String) {
        assertEquals(names.toList(), sorted(*names.reversedArray()))
        assertEquals(names.toList(), sorted(*names))
    }

    @Test
    fun digitRunsCompareNumerically() {
        assertEquals(listOf("page2", "page10", "page100"), sorted("page100", "page10", "page2"))
    }

    @Test
    fun leadingZerosAreIgnored() {
        assertTrue(NaturalOrder.compare("p007", "p8") < 0)
        assertOrder("p0807", "p808", "p0809", "p810")
        assertOrder("Book 001 - 00009", "Book 001 - 0010", "Book 001 - 011")
    }

    @Test
    fun paddedTiesBreakOnRawString() {
        assertOrder("0003", "003")
        assertOrder("01", "1")
        assertTrue(NaturalOrder.compare("p007", "p7") < 0)
        assertTrue(NaturalOrder.compare("p7", "p007") > 0)
    }

    @Test
    fun lettersCompareCaseInsensitively() {
        assertEquals(listOf("a.jpg", "B.jpg", "c.jpg"), sorted("c.jpg", "B.jpg", "a.jpg"))
        assertOrder("Cover.jpg", "cover.jpg", "cover2.jpg")
    }

    @Test
    fun prefixSortsFirst() {
        assertEquals(listOf("page", "page1"), sorted("page1", "page"))
    }

    @Test
    fun directoriesSortByPath() {
        assertEquals(
            listOf("ch1/p2.jpg", "ch1/p10.jpg", "ch2/p1.jpg"),
            sorted("ch2/p1.jpg", "ch1/p10.jpg", "ch1/p2.jpg")
        )
    }

    @Test
    fun folderBeforeLongerNamedSibling() {
        assertOrder("ch1/z.jpg", "ch1 extra/a.jpg", "ch2/a.jpg")
        assertOrder("Vol 1/a.jpg", "Vol 1/sub/a.jpg", "Vol 1-2/a.jpg")
    }

    @Test
    fun backslashSeparatesToo() {
        assertOrder("ch1\\z.jpg", "ch1 extra\\a.jpg")
    }

    @Test
    fun stemBeforeExtension() {
        assertOrder("X-156.jpg", "X-156-157.jpg", "X-158.jpg")
        assertOrder("B-000.jpg", "B-001-002.jpg", "B-003.jpg")
        assertOrder("page.jpg", "page.png", "page1.jpg")
    }

    @Test
    fun letterSuffixesAfterTheirNumber() {
        assertOrder("B-155.jpg", "B-156.jpg", "B-156b.jpg", "B-157.jpg")
        assertOrder("001.jpg", "001b.jpg", "001b2.jpg", "001b3.jpg", "001c.jpg", "002.jpg")
        assertOrder("001k1.jpg", "001k12.jpg")
    }

    @Test
    fun variantCoversInOrder() {
        assertOrder(
            "Book 01 - 000_ Store.jpg",
            "Book 01 - 000a Cover A.jpg",
            "Book 01 - 000b Cover B.jpg",
            "Book 01 - 000c Cover RI.jpg",
            "Book 01 - 001.jpg",
            "Book 01 - 002.jpg"
        )
    }

    @Test
    fun prefixLetterOrdersChapters() {
        assertOrder("Book c01 - p999.jpg", "Book c01 - q1064.jpg")
    }

    @Test
    fun scannerTagPagesSortLast() {
        assertOrder("Book 01-024.jpg", "Book 01-025.jpg", "zScanTag.jpg", "zzzz.jpg", "ZZZZZ.jpg")
    }

    @Test
    fun composedAndDecomposedAccentsMatch() {
        assertOrder("f.jpg", "éa.jpg", "éb.jpg")
    }

    private val mixed = listOf(
        "a", "A", "a1", "a01", "a/b", "a.b", "a-b", "a_b", "a-c", "a-c/d", "a.b/c", "a/b.c",
        "1", "01", "1.jpg", "1/2.jpg", "z", "", "x 2", "x2", "x02b", "x2.b"
    )

    @Test
    fun orderIsAntisymmetric() {
        for (x in mixed) for (y in mixed) {
            assertEquals("$x vs $y", Integer.signum(NaturalOrder.compare(x, y)), -Integer.signum(NaturalOrder.compare(y, x)))
            if (x != y) assertTrue("$x vs $y", NaturalOrder.compare(x, y) != 0)
        }
    }

    @Test
    fun orderIsTransitive() {
        for (x in mixed) for (y in mixed) for (z in mixed) {
            if (NaturalOrder.compare(x, y) < 0 && NaturalOrder.compare(y, z) < 0) {
                assertTrue("$x < $y < $z", NaturalOrder.compare(x, z) < 0)
            }
        }
    }
}
