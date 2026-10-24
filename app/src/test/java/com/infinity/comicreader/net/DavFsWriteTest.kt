package com.infinity.comicreader.net

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import kotlin.random.Random

/** Changes to WebDAV shares against a server on this machine: MKCOL, PUT, MOVE, COPY, DELETE. */
class DavFsWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var server: FakeDavServer
    private lateinit var fs: DavFs

    @Before
    fun setUp() {
        dir = tmp.newFolder("share")
        server = FakeDavServer(dir)
        fs = DavFs(server.share())
    }

    @After
    fun tearDown() {
        fs.close()
        server.close()
    }

    @Test
    fun makesFoldersAndWritesFilesOnlyWhereNothingIs() {
        fs.mkdir("New Folder")
        assertTrue(File(dir, "New Folder").isDirectory)
        assertEquals("/dav/New%20Folder/", server.paths("MKCOL").single())
        val bytes = Random(1).nextBytes(300_000)
        fs.create("New Folder/a #1.cbz", ByteArrayInputStream(bytes), bytes.size.toLong())
        assertArrayEquals(bytes, File(dir, "New Folder/a #1.cbz").readBytes())
        val put = server.requests.single { it.method == "PUT" }
        assertEquals("*", put.getHeader("If-None-Match"))
        expectFailure { fs.create("New Folder/a #1.cbz", ByteArrayInputStream(ByteArray(3)), 3) }
        assertArrayEquals(bytes, File(dir, "New Folder/a #1.cbz").readBytes())
        expectFailure { fs.mkdir("New Folder") }
    }

    @Test
    fun renamesWithoutOverwritingAndNeverFollowsRedirects() {
        File(dir, "old.cbz").writeBytes(ByteArray(10))
        File(dir, "taken.cbz").writeBytes(ByteArray(3))
        File(dir, "Folder").mkdirs()
        fs.rename("old.cbz", "Folder/new name.cbz")
        assertTrue(File(dir, "Folder/new name.cbz").isFile)
        assertFalse(File(dir, "old.cbz").exists())
        val move = server.requests.single { it.method == "MOVE" }
        assertEquals("F", move.getHeader("Overwrite"))
        assertTrue(move.getHeader("Destination")!!.endsWith("/dav/Folder/new%20name.cbz"))
        expectFailure { fs.rename("Folder/new name.cbz", "taken.cbz") }
        assertEquals(3L, File(dir, "taken.cbz").length())
        // A folder moves by its folder address.
        fs.rename("Folder", "Moved")
        assertTrue(File(dir, "Moved/new name.cbz").isFile)
        assertTrue(server.paths("MOVE").last().endsWith("/dav/Folder/"))
    }

    @Test
    fun deletesFoldersWithAllInside() {
        File(dir, "Series/Inner").mkdirs()
        File(dir, "Series/Inner/001.cbz").writeBytes(ByteArray(5))
        fs.delete("Series")
        assertFalse(File(dir, "Series").exists())
        try {
            fs.delete("Series")
            fail("no failure")
        } catch (e: RemoteException) {
            assertEquals(RemoteException.Reason.MISSING, e.reason)
        }
    }

    @Test
    fun copiesOnTheServerWhenItCan() {
        File(dir, "a.cbz").writeBytes(ByteArray(10) { it.toByte() })
        assertTrue(fs.copy("a.cbz", "b.cbz") {})
        assertArrayEquals(File(dir, "a.cbz").readBytes(), File(dir, "b.cbz").readBytes())
        assertEquals("0", server.requests.single { it.method == "COPY" }.getHeader("Depth"))
        server.noCopy = true
        assertFalse(fs.copy("a.cbz", "c.cbz") {})
        assertFalse(File(dir, "c.cbz").exists())
    }

    @Test
    fun changesAreWaitedOnLongerThanReads() {
        assertTrue(DavFs.CHANGE_WAIT_MS >= 5 * 60_000L)
        // A 5 GB copy is waited on for over 13 minutes.
        assertTrue(DavFs.copyWaitMs(5_000_000_000L) > 13 * 60_000L)
        assertEquals(DavFs.CHANGE_WAIT_MS, DavFs.copyWaitMs(0))
    }

    @Test
    fun aReadOnlyShareTurnsChangesDownWithoutLockingTheShare() {
        server.readOnly = true
        try {
            fs.mkdir("x")
            fail("no failure")
        } catch (e: RemoteException) {
            assertEquals(RemoteException.Reason.DENIED, e.reason)
            assertFalse(e.lasting)
        }
    }

    @Test
    fun aFailingSourceOrCancelingPassesThroughUnchanged() {
        val failing = object : InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 1000) 1 else throw IOException("source broke")
        }
        try {
            fs.create("a.cbz", failing, 5000)
            fail("no failure")
        } catch (e: IOException) {
            assertFalse(e is RemoteException)
            assertEquals("source broke", e.message)
        }
        val canceled = object : InputStream() {
            override fun read(): Int = throw CancellationException("stop")
        }
        try {
            fs.create("b.cbz", canceled, 10)
            fail("no failure")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("no failure")
        } catch (e: IOException) {
            // Expected.
        }
    }
}
