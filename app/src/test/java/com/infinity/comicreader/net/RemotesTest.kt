package com.infinity.comicreader.net

import org.junit.After
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

/** Shares as files: paths, listings and their stats, the main-thread guess, and failing fast while unreachable. */
class RemotesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fake: FakeRemoteFs
    private var now = 1_000_000L

    @Before
    fun setUp() {
        val dir = tmp.newFolder("share")
        File(dir, "Series").mkdirs()
        File(dir, "Series/001.cbz").writeBytes(ByteArray(10))
        File(dir, "Series/002.cbz").writeBytes(ByteArray(20))
        File(dir, "notes.txt").writeText("x")
        fake = FakeRemoteFs(dir)
        Remotes.serve(listOf(FakeRemoteFs.share())) { fake }
        Remotes.online = { true }
        Remotes.onMainThread = { false }
        Remotes.clock = { now }
    }

    @After
    fun tearDown() {
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
        Remotes.clock = { System.nanoTime() / 1_000_000 }
    }

    @Test
    fun pathsOnSharesMakeRemoteFiles() {
        val f = Remotes.file("/net/s1/Series/001.cbz")
        assertTrue(f is RemoteFile)
        assertEquals("/net/s1/Series/001.cbz", f.path)
        assertEquals("/net/s1/Series/001.cbz", f.absolutePath)
        assertEquals("001.cbz", f.name)
        assertEquals("/net/s1/Series", f.parentFile?.path)
        assertTrue(f.parentFile is RemoteFile)
        val root = Remotes.file("/net/s1/")
        assertEquals("/net/s1", root.path)
        // Above a share's root is no share.
        assertFalse(root.parentFile is RemoteFile)
        assertFalse(Remotes.file("/storage/emulated/0/a.cbz") is RemoteFile)
        assertEquals("s1", Remotes.idOf("/net/s1/Series"))
        assertEquals("Series/001.cbz", Remotes.relOf("/net/s1/Series/001.cbz"))
        assertEquals("", Remotes.relOf("/net/s1"))
        assertNull(Remotes.idOf("/net"))
    }

    @Test
    fun listingsKnowTheirEntriesWithoutAskingAgain() {
        val series = Remotes.file("/net/s1/Series")
        val books = series.listFiles()!!.sortedBy { it.name }
        assertEquals(listOf("001.cbz", "002.cbz"), books.map { it.name })
        assertEquals(listOf(10L, 20L), books.map { it.length() })
        assertTrue(books.all { it.isFile && !it.isDirectory && it.exists() })
        // A new file for the same path knows it too: the listing is remembered.
        assertEquals(20L, Remotes.file("/net/s1/Series/002.cbz").length())
        assertEquals(0, fake.stats)
        assertEquals(1, fake.lists)
    }

    @Test
    fun theRootIsAFolderKnownWithoutAsking() {
        val root = Remotes.file("/net/s1")
        assertTrue(root.isDirectory)
        assertTrue(root.exists())
        assertEquals(0, fake.stats)
        assertEquals(setOf("Series", "notes.txt"), root.list()!!.toSet())
    }

    @Test
    fun aMissingFileIsAskedForOnce() {
        val f = Remotes.file("/net/s1/gone.cbz")
        assertFalse(f.exists())
        assertFalse(f.isFile)
        assertEquals(0L, f.length())
        assertEquals(1, fake.stats)
    }

    @Test
    fun theMainThreadGuessesByNameAndNeverAsks() {
        Remotes.onMainThread = { true }
        val book = Remotes.file("/net/s1/Other/a.cbz")
        val folder = Remotes.file("/net/s1/Other/Folder")
        assertTrue(book.exists())
        assertTrue(book.isFile)
        assertTrue(folder.isDirectory)
        assertNull(folder.listFiles())
        assertEquals(0, fake.stats + fake.lists)
    }

    @Test
    fun anUnreachableShareFailsFastUntilRetriedOrAWhileLater() {
        fake.offline = true
        val dir = Remotes.file("/net/s1/Series") as RemoteFile
        assertNull(dir.listFiles())
        assertTrue(Remotes.unreachable("s1"))
        fake.offline = false
        // Not asked again while quiet.
        try {
            dir.entries()
            fail("listed while quiet")
        } catch (e: RemoteException) {
            assertEquals(RemoteException.Reason.UNREACHABLE, e.reason)
        }
        assertEquals(0, fake.lists)
        now += Remotes.QUIET_MS
        assertFalse(Remotes.unreachable("s1"))
        assertEquals(2, dir.entries().size)
        fake.offline = true
        assertNull(dir.listFiles())
        fake.offline = false
        Remotes.retry("s1")
        assertEquals(2, dir.entries().size)
    }

    @Test
    fun aRefusedSignInIsNeverTriedAgainUnasked() {
        val calls = listOf({ Remotes.list("/net/s1/Series") }, { Remotes.stat("/net/s1/notes.txt") }, { Remotes.space("s1") })
        for (reason in listOf(RemoteException.Reason.DENIED, RemoteException.Reason.NO_SHARE)) {
            fake.refuse = reason
            val before = fake.attempts
            repeat(5) {
                for (call in calls) {
                    try {
                        call()
                        fail("succeeded while refused")
                    } catch (e: RemoteException) {
                        assertEquals(reason, e.reason)
                    }
                }
                now += Remotes.QUIET_MS
            }
            assertNull(Remotes.file("/net/s1/Series").listFiles())
            assertEquals(1, fake.attempts - before)
            // The user's Retry, or editing the share, signs in again.
            fake.refuse = null
            Remotes.retry("s1")
            assertEquals(2, Remotes.list("/net/s1/Series").size)
            fake.refuse = reason
            runCatching { Remotes.space("s1") }
            Remotes.changed("s1")
            fake.refuse = null
            assertEquals(2, Remotes.list("/net/s1/Series").size)
        }
    }

    @Test
    fun offlineNothingIsTried() {
        Remotes.online = { false }
        assertTrue(Remotes.unreachable("s1"))
        assertNull(Remotes.file("/net/s1/Series").listFiles())
        assertEquals(0, fake.lists)
    }

    @Test
    fun aRemovedOrUnknownShareFails() {
        try {
            Remotes.list("/net/zz/Series")
            fail("listed an unknown share")
        } catch (e: RemoteException) {
            assertEquals(RemoteException.Reason.UNKNOWN_SHARE, e.reason)
        }
    }

    @Test
    fun aChangedShareDropsItsConnectionAndFindings() {
        Remotes.file("/net/s1/Series").listFiles()
        assertEquals(10L, Remotes.cachedStat("/net/s1/Series/001.cbz")?.size)
        Remotes.changed("s1")
        assertTrue(fake.closed)
        assertNull(Remotes.cachedStat("/net/s1/Series/001.cbz"))
    }

    @Test
    fun sharesAreReadOnly() {
        val f = Remotes.file("/net/s1/Series/001.cbz")
        assertFalse(f.delete())
        assertFalse(f.renameTo(File(tmp.root, "x")))
        assertFalse(Remotes.file("/net/s1/New").mkdir())
        assertFalse(f.canWrite())
    }

    @Test
    fun spaceComesFromTheShare() {
        assertEquals(3_000_000_000L to 8_000_000_000L, Remotes.space("s1"))
    }
}
