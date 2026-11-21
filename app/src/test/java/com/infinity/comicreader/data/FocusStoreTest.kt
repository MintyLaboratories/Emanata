package com.infinity.comicreader.data

import com.infinity.comicreader.reader.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FocusStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun focus(top: Double) = CoverFocus(Box(0.1, top, 0.9, top + 0.1), glyph = 0.1)

    @Test
    fun keepsFocusesAcrossInstances() {
        val file = File(tmp.root, "focus.txt")
        FocusStore(file).apply {
            put("book a|0,0,1,1", focus(0.2))
            put("book b|0,0,1,1", CoverFocus.NONE)
        }
        val again = FocusStore(file)
        assertEquals(0.2, again.get("book a|0,0,1,1")!!.title!!.top, 1e-9)
        assertEquals(CoverFocus.NONE, again.get("book b|0,0,1,1"))
        assertNull(again.get("book c|0,0,1,1"))
    }

    @Test
    fun theLastFocusOfACoverWins() {
        val file = File(tmp.root, "focus.txt")
        FocusStore(file).apply {
            put("a", focus(0.2))
            put("a", focus(0.5))
        }
        assertEquals(0.5, FocusStore(file).get("a")!!.title!!.top, 1e-9)
    }

    @Test
    fun rewritesWithTheNewestHalfWhenFull() {
        val file = File(tmp.root, "focus.txt")
        val store = FocusStore(file, max = 4)
        for (i in 0..4) store.put("k$i", focus(i / 10.0))
        assertEquals(2, file.readLines().size)
        val again = FocusStore(file, max = 4)
        assertNull(again.get("k2"))
        assertNotNull(again.get("k3"))
        assertNotNull(again.get("k4"))
    }

    @Test
    fun linesHoldNoKeys() {
        val file = File(tmp.root, "focus.txt")
        FocusStore(file).put("/books/Some title.cbz|0,0,1,1", focus(0.2))
        assertTrue(!file.readText().contains("title"))
    }

    @Test
    fun clearForgetsAll() {
        val file = File(tmp.root, "focus.txt")
        val store = FocusStore(file)
        store.put("a", focus(0.2))
        store.clear()
        assertNull(store.get("a"))
        assertNull(FocusStore(file).get("a"))
    }
}
