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
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Shares as files: paths, listings and their stats, the main-thread guess, and failing fast while unreachable. */
class RemotesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var fake: FakeRemoteFs
    @Volatile
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
    fun readsMeasureTheShareSpeed() {
        File(tmp.root, "share/big.bin").writeBytes(ByteArray(3 shl 20))
        var list = listOf(FakeRemoteFs.share())
        Remotes.serve({ list }) { fake }
        Remotes.clock = { now }
        var t = 0L
        ShareSpeeds.clock = { t += 150; t }
        try {
            Remotes.open("/net/s1/big.bin").use { input ->
                assertEquals("s1", input.shareId)
                val buffer = ByteArray(1 shl 20)
                for (i in 0 until 3) input.readFully(i.toLong() shl 20, buffer, 0, buffer.size)
                for (i in 0 until 3) input.read(i * 4096L, buffer, 0, 4096)
            }
            val e = ShareSpeeds.estimate("s1")!!
            // Each long read was under way for two ticks; each short one answered in one.
            assertEquals((1L shl 20) * 1000 / 300, e.bytesPerSecond)
            assertEquals(150L, e.firstByteMs)
            assertFalse(ShareSpeeds.slow("s1"))
            // Editing what is not how it is reached keeps what it measured; removing the share forgets it.
            list = listOf(FakeRemoteFs.share().copy(speed = ShareSpeed.FAST))
            Remotes.changed("s1")
            assertEquals(e, ShareSpeeds.estimate("s1"))
            list = emptyList()
            Remotes.changed("s1")
            assertNull(ShareSpeeds.estimate("s1"))
            // An edit made before the shares were loaded may have moved the share: forgotten too.
            list = listOf(FakeRemoteFs.share())
            Remotes.serve({ list }) { fake }
            repeat(3) { ShareSpeeds.meter("s1").firstByte(5) }
            assertEquals(5L, ShareSpeeds.estimate("s1")!!.firstByteMs)
            list = listOf(FakeRemoteFs.share().copy(name = "Share B"))
            Remotes.changed("s1")
            assertNull(ShareSpeeds.estimate("s1"))
        } finally {
            ShareSpeeds.clock = { System.nanoTime() / 1_000_000 }
        }
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
    fun foldersListedFromTheShareTellWhen() {
        assertNull(Remotes.listedAt("/net/s1/Series"))
        Remotes.file("/net/s1/Series").listFiles()
        assertEquals(now, Remotes.listedAt("/net/s1/Series/"))
        // A kept listing is not a listing from the share.
        Remotes.changed(null)
        assertNull(Remotes.listedAt("/net/s1/Series"))
        assertTrue(Remotes.cachedNames("/net/s1/Series") != null)
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
    fun aLostPasswordIsNeverSignedInWith() {
        Remotes.serve(listOf(FakeRemoteFs.share().copy(password = "", passwordLost = true))) { fake }
        try {
            Remotes.list("/net/s1/Series")
            fail("signed in with a lost password")
        } catch (e: RemoteException) {
            assertEquals(RemoteException.Reason.DENIED, e.reason)
        }
        Remotes.retry("s1")
        assertNull(Remotes.file("/net/s1/Series").listFiles())
        assertEquals(0, fake.attempts)
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
    fun aShareReachedAnotherWayDropsItsConnectionFindingsAndListings() {
        var shares = listOf(FakeRemoteFs.share())
        Remotes.serve({ shares }, listingsDir = tmp.newFolder("listings")) { fake }
        Remotes.file("/net/s1/Series").listFiles()
        assertEquals(10L, Remotes.cachedStat("/net/s1/Series/001.cbz")?.size)
        shares = listOf(FakeRemoteFs.share().copy(user = "someone else"))
        Remotes.changed("s1")
        assertTrue(fake.closed)
        assertNull(Remotes.cachedStat("/net/s1/Series/001.cbz"))
        assertNull(Remotes.cachedList("/net/s1/Series"))
    }

    @Test
    fun aShareRenamedKeepsItsConnectionAndListings() {
        var shares = listOf(FakeRemoteFs.share())
        Remotes.serve({ shares }) { fake }
        Remotes.list("/net/s1/Series")
        shares = listOf(FakeRemoteFs.share().copy(name = "Share B"))
        Remotes.changed("s1")
        assertFalse(fake.closed)
        assertEquals("Share B", Remotes.share("s1")?.name)
        assertEquals(10L, Remotes.cachedStat("/net/s1/Series/001.cbz")?.size)
        assertEquals(2, Remotes.cachedList("/net/s1/Series")?.size)
    }

    @Test
    fun listingsAreKeptAcrossRestartsAndNeverAskTheNetwork() {
        val dir = tmp.newFolder("listings")
        Remotes.serve(listOf(FakeRemoteFs.share()), listingsDir = dir) { fake }
        assertNull(Remotes.cachedList("/net/s1/Series"))
        Remotes.list("/net/s1/Series/")
        // As after a restart: nothing in memory, the share not asked.
        Remotes.serve(listOf(FakeRemoteFs.share()), listingsDir = dir) { fake }
        Remotes.online = { false }
        val kept = Remotes.cachedList("/net/s1/Series")!!
        assertEquals(listOf("001.cbz", "002.cbz"), kept.map { it.name }.sorted())
        assertEquals(20L, kept.first { it.name == "002.cbz" }.length())
        assertEquals(1, fake.lists)
    }

    @Test
    fun aKeptListingNeverReplacesANewerStat() {
        Remotes.list("/net/s1/Series")
        File(tmp.root, "share/Series/002.cbz").writeBytes(ByteArray(30))
        assertEquals(30L, Remotes.stat("/net/s1/Series/002.cbz")?.size)
        val kept = Remotes.cachedList("/net/s1/Series")!!
        assertEquals("as kept", 20L, kept.first { it.name == "002.cbz" }.length())
        assertEquals("the newer stat stays", 30L, Remotes.cachedStat("/net/s1/Series/002.cbz")?.size)
        assertTrue(Remotes.isListed("/net/s1/Series/"))
        assertFalse(Remotes.isListed("/net/s1"))
    }

    @Test
    fun aRemovedShareHasNoListings() {
        var shares = listOf(FakeRemoteFs.share())
        val dir = tmp.newFolder("listings")
        Remotes.serve({ shares }, listingsDir = dir) { fake }
        Remotes.list("/net/s1/Series")
        shares = emptyList()
        Remotes.changed("s1")
        assertNull(Remotes.cachedList("/net/s1/Series"))
        assertFalse(File(dir, "s1").exists())
    }

    @Test
    fun namesNoFileCanHaveAreLeftOut() {
        val names = listOf("ok.cbz", "", ".", "..", "a/b", "..\\up", "nul\u0000.cbz", ".hidden")
        Remotes.serve(listOf(FakeRemoteFs.share())) {
            object : RemoteFs by fake {
                override fun list(path: String) = names.map { RemoteStat(it, false, 1, 0, 0) }
            }
        }
        assertEquals(listOf("ok.cbz", ".hidden"), Remotes.list("/net/s1").map { it.name })
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

    @Test
    fun aFileOpenedForAnAskerTimesItsReadsFromAnyThread() {
        val book = "/net/s1/Series/001.cbz"
        val reader = Remotes.Asker()
        val input = Remotes.askingAs(reader) { Remotes.open(book) }
        assertEquals(0L, Remotes.silentFor(reader))
        val held = CountDownLatch(1)
        fake.slow = held
        val read = thread { input.read(0, ByteArray(10), 0, 10) }
        while (fake.held.get() == 0) Thread.sleep(5)
        now += 1_500
        assertEquals(1_500L, Remotes.silentFor(reader))
        fake.slow = null
        held.countDown()
        read.join()
        assertEquals(0L, Remotes.silentFor(reader))
        input.close()
    }

    @Test
    fun aListingWaitedOnCountsAsItsAskersSilence() {
        val reader = Remotes.Asker()
        val held = CountDownLatch(1)
        fake.slow = held
        val list = thread { Remotes.askingAs(reader) { Remotes.list("/net/s1/Series") } }
        while (fake.held.get() == 0) Thread.sleep(5)
        now += 700
        assertEquals(700L, Remotes.silentFor(reader))
        fake.slow = null
        held.countDown()
        list.join()
        assertEquals(0L, Remotes.silentFor(reader))
    }

    @Test
    fun othersWaitingOnTheSameShareNeverCount() {
        val reader = Remotes.Asker()
        val covers = Remotes.Asker()
        val mine = Remotes.askingAs(reader) { Remotes.open("/net/s1/Series/001.cbz") }
        val theirs = Remotes.askingAs(covers) { Remotes.open("/net/s1/Series/002.cbz") }
        val anyone = Remotes.open("/net/s1/notes.txt")
        val held = CountDownLatch(1)
        fake.slow = held
        val reads = listOf(theirs, anyone).map { input -> thread { input.read(0, ByteArray(1), 0, 1) } }
        val listing = thread { Remotes.list("/net/s1/Series") }
        while (fake.held.get() < 3) Thread.sleep(5)
        now += 5_000
        assertEquals(0L, Remotes.silentFor(reader))
        assertEquals(5_000L, Remotes.silentFor(covers))
        fake.slow = null
        held.countDown()
        (reads + listing).forEach { it.join() }
        listOf(mine, theirs, anyone).forEach { it.close() }
    }

    @Test
    fun readsFetchedAheadCountOnlyWhileTheReaderWaitsOnThem() {
        File(tmp.root, "share/Series/big.cbz").writeBytes(ByteArray(100_000) { it.toByte() })
        val reader = Remotes.Asker()
        val input = Remotes.askingAs(reader) { Remotes.open("/net/s1/Series/big.cbz") }
        val background = java.util.concurrent.Executors.newCachedThreadPool()
        val held = CountDownLatch(1)
        try {
            val channel = RemoteChannel(input, minBlock = 1024, maxBlock = 16_384, background = background)
            channel.expect(0, 60_000)
            fake.slowReads = 16_384L..99_999L
            fake.slow = held
            channel.read(java.nio.ByteBuffer.allocate(10))
            while (fake.held.get() == 0) Thread.sleep(5)
            now += 5_000
            // Only the runs coming ahead are held: background work, not the reader's.
            assertEquals(0L, Remotes.silentFor(reader))
            val read = thread {
                channel.position(20_000)
                channel.read(java.nio.ByteBuffer.allocate(10))
            }
            while (Remotes.silentFor(reader) == 0L) {
                now += 1
                Thread.sleep(5)
            }
            val since = now - Remotes.silentFor(reader)
            now += 3_000
            // Now the reader waits on one of them.
            assertEquals(now - since, Remotes.silentFor(reader))
            fake.slow = null
            held.countDown()
            read.join()
            assertEquals(0L, Remotes.silentFor(reader))
            channel.close()
        } finally {
            held.countDown()
            background.shutdownNow()
        }
    }
}
