package com.infinity.comicreader.net

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InterruptedIOException

class RemoteCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var share: File
    private lateinit var cacheDir: File
    private lateinit var fake: FakeRemoteFs

    @Before
    fun setUp() {
        share = tmp.newFolder("share")
        cacheDir = tmp.newFolder("cache")
        for (i in 1..3) File(share, "$i.cbr").writeBytes(ByteArray(1000 * i) { (it + i).toByte() })
        fake = FakeRemoteFs(share)
        Remotes.serve(listOf(FakeRemoteFs.share())) { fake }
        RemoteCache.limitBytes = RemoteCache.DEFAULT_LIMIT_BYTES
    }

    @After
    fun tearDown() {
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
        RemoteCache.limitBytes = RemoteCache.DEFAULT_LIMIT_BYTES
    }

    private fun remote(name: String) = Remotes.file("/net/s1/$name") as RemoteFile

    @Test
    fun aFetchedCopyIsReusedUntilTheFileChanges() {
        val copy = RemoteCache.fetch(remote("2.cbr"), cacheDir)
        assertArrayEquals(File(share, "2.cbr").readBytes(), copy.readBytes())
        assertEquals("cbr", copy.extension)
        val read = fake.bytesRead
        assertEquals(copy, RemoteCache.fetch(remote("2.cbr"), cacheDir))
        assertEquals(copy, RemoteCache.cached(remote("2.cbr"), cacheDir))
        assertEquals(read, fake.bytesRead)
        File(share, "2.cbr").writeBytes(ByteArray(5))
        val again = RemoteCache.fetch(remote("2.cbr"), cacheDir)
        assertEquals(5L, again.length())
    }

    @Test
    fun theLeastRecentlyUsedCopiesGoPastTheLimit() {
        RemoteCache.limitBytes = 5000
        val one = RemoteCache.fetch(remote("1.cbr"), cacheDir)
        one.setLastModified(1_000)
        val two = RemoteCache.fetch(remote("2.cbr"), cacheDir)
        two.setLastModified(2_000)
        RemoteCache.fetch(remote("3.cbr"), cacheDir)
        assertFalse(one.exists())
        assertTrue(two.exists())
        assertEquals(5000L, RemoteCache.dirIn(cacheDir).listFiles()!!.sumOf { it.length() })
    }

    @Test
    fun aFileLargerThanTheCacheIsRefused() {
        RemoteCache.limitBytes = 2500
        try {
            RemoteCache.fetch(remote("3.cbr"), cacheDir)
            fail("fetched past the limit")
        } catch (e: RemoteCache.TooLargeException) {
            assertEquals(3000L, e.size)
        }
    }

    @Test
    fun aCanceledFetchLeavesNothing() {
        try {
            RemoteCache.fetch(remote("3.cbr"), cacheDir, canceled = { true })
            fail("not canceled")
        } catch (e: InterruptedIOException) {
            // Expected.
        }
        assertEquals(0, RemoteCache.dirIn(cacheDir).listFiles()!!.size)
        assertNull(RemoteCache.cached(remote("3.cbr"), cacheDir))
    }

    @Test
    fun progressCountsUpToTheSize() {
        var last = 0L to 0L
        RemoteCache.fetch(remote("3.cbr"), cacheDir) { done, total -> last = done to total }
        assertEquals(3000L to 3000L, last)
    }

    @Test
    fun keptPagesInUseTwiceStayUntilBothLetGo() {
        val pages = RemoteCache.pagesOf(remote("1.cbr"), cacheDir).apply { mkdirs() }
        File(pages, "001.png").writeBytes(ByteArray(4000))
        assertEquals(pages, RemoteCache.pagesOf(remote("1.cbr"), cacheDir))
        RemoteCache.limitBytes = 0
        RemoteCache.release(pages, cacheDir)
        assertTrue("still in use", File(pages, "001.png").isFile)
        RemoteCache.release(pages, cacheDir)
        assertFalse("trimmed once let go", pages.exists())
    }
}
