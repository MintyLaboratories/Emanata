package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val direct = Executor { it.run() }

    private fun store(writer: Executor = direct) = PreviewStore(File(temp.root, "previews"), writer)

    private fun solid(color: Int, w: Int = 40, h: Int = 60) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun roundTrip() {
        val s = store()
        val key = PreviewStore.keyOf("book|1|2", 3, "p03.jpg")
        assertFalse(s.has(key))
        assertNull(s.get(key))
        val preview = solid(Color.rgb(30, 160, 220))
        s.put(key, preview)
        assertTrue(s.has(key))
        assertFalse(preview.isRecycled)
        val back = s.get(key)
        assertNotNull(back)
        assertEquals(40, back!!.width)
        assertEquals(60, back.height)
        val c = back.getPixel(20, 30)
        assertTrue(kotlin.math.abs(Color.blue(c) - 220) < 8)
    }

    @Test
    fun transparencyFlattensOntoWhite() {
        val s = store()
        val key = PreviewStore.keyOf("book", 0, null)
        s.put(key, solid(Color.TRANSPARENT))
        val c = s.get(key)!!.getPixel(5, 5)
        assertTrue(Color.red(c) > 245 && Color.green(c) > 245 && Color.blue(c) > 245)
    }

    @Test
    fun queuedWriteCountsAsPresent() {
        val queued = ArrayList<Runnable>()
        val s = store { queued += it }
        val key = PreviewStore.keyOf("book", 1, "a")
        s.put(key, solid(Color.RED))
        s.put(key, solid(Color.RED))
        assertEquals("a second put of a queued key is dropped", 1, queued.size)
        assertTrue(s.has(key))
        queued.forEach { it.run() }
        assertTrue(s.has(key))
        assertNotNull(store().get(key))
    }

    @Test
    fun keysTellBooksPagesAndEntriesApart() {
        val base = PreviewStore.keyOf("a|10|5", 0, "01.jpg")
        assertNotEquals(base, PreviewStore.keyOf("a|11|5", 0, "01.jpg"))
        assertNotEquals(base, PreviewStore.keyOf("a|10|5", 1, "01.jpg"))
        assertNotEquals(base, PreviewStore.keyOf("a|10|5", 0, "02.jpg"))
        assertTrue(base.endsWith("|${Previews.BOX}"))
    }

    @Test
    fun bookIdFollowsSizeAndTime() {
        val f = temp.newFile("book.cbz").apply { writeBytes(ByteArray(10)) }
        val before = PreviewStore.bookId(f)
        f.writeBytes(ByteArray(11))
        assertNotEquals(before, PreviewStore.bookId(f))
    }

    @Test
    fun trimDropsLeastRecentlyUsedFirst() {
        val dir = File(temp.root, "previews").apply { mkdirs() }
        val old = File(dir, "old.jpg").apply { writeBytes(ByteArray(100)); setLastModified(1_000_000L) }
        val mid = File(dir, "mid.jpg").apply { writeBytes(ByteArray(100)); setLastModified(2_000_000L) }
        val new = File(dir, "new.jpg").apply { writeBytes(ByteArray(100)); setLastModified(3_000_000L) }
        val stalePart = File(dir, "x.jpg.part").apply { writeBytes(ByteArray(5)); setLastModified(1_000L) }
        val freshPart = File(dir, "y.jpg.part").apply { writeBytes(ByteArray(5)) }
        PreviewStore.trim(dir, maxBytes = 200)
        assertFalse(old.exists())
        assertTrue(mid.exists())
        assertTrue(new.exists())
        assertFalse(stalePart.exists())
        assertTrue(freshPart.exists())
    }

    @Test
    fun writesKeepTheStoreUnderItsCap() {
        val dir = File(temp.root, "previews").apply { mkdirs() }
        val old = File(dir, "old.jpg").apply { writeBytes(ByteArray(4000)); setLastModified(1_000_000L) }
        val s = PreviewStore(dir, direct, maxBytes = 3000, trimEvery = 1)
        s.put(PreviewStore.keyOf("b", 0, null), solid(Color.GREEN))
        assertFalse(old.exists())
        assertTrue(s.has(PreviewStore.keyOf("b", 0, null)))
    }

    @Test
    fun carriedPreviewsFollowTheirPagesMovedOrCopied() {
        val s = store()
        val old = listOf("a/1.jpg", "a/2.jpg", "a/3.jpg")
        val new = listOf("1.jpg", "3.jpg")
        for (i in old.indices) s.put(PreviewStore.keyOf("id:00000000000000aa", i, old[i]), solid(Color.BLUE))
        val map = intArrayOf(0, -1, 1)
        PreviewStore.carry(s.dir, "id:00000000000000aa", old, "id:00000000000000bb", new, map, move = false)
        assertTrue(s.has(PreviewStore.keyOf("id:00000000000000bb", 0, "1.jpg")))
        assertTrue(s.has(PreviewStore.keyOf("id:00000000000000bb", 1, "3.jpg")))
        assertTrue(s.has(PreviewStore.keyOf("id:00000000000000aa", 2, "a/3.jpg")))
        PreviewStore.carry(s.dir, "id:00000000000000aa", old, "id:00000000000000cc", new, map, move = true)
        assertNotNull(s.get(PreviewStore.keyOf("id:00000000000000cc", 1, "3.jpg")))
        assertFalse(s.has(PreviewStore.keyOf("id:00000000000000aa", 2, "a/3.jpg")))
        // A page not carried stays with its book.
        assertTrue(s.has(PreviewStore.keyOf("id:00000000000000aa", 1, "a/2.jpg")))
        assertFalse(s.dir.list()!!.any { it.endsWith(".part") })
    }

    @Test
    fun clearRemovesTheStore() {
        val s = store()
        s.put(PreviewStore.keyOf("b", 0, null), solid(Color.GREEN))
        PreviewStore.clear(s.dir)
        assertFalse(s.dir.exists())
    }
}
