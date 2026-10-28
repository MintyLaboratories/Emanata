package com.infinity.comicreader.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.net.FakeRemoteFs
import com.infinity.comicreader.net.FileEnds
import com.infinity.comicreader.net.RemoteChannel
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.testing.Xxh3Port
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookIdsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        BookIndex.reset()
        BookIds.init(context)
        FileEnds.clear()
    }

    @After
    fun tearDown() {
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
        BookIndex.reset()
    }

    private fun book(name: String, size: Int, seed: Int = 1): File =
        tmp.root.resolve(name).also { it.parentFile?.mkdirs(); it.writeBytes(Random(seed).nextBytes(size)) }

    /** The ID [bytes] have, made the way the layout says, independently of [BookIds]. */
    private fun expected(bytes: ByteArray): BookId {
        val n = bytes.size
        val layout = ByteBuffer.allocate(BookIdBytes.length(n.toLong())).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(n.toLong())
        if (n < 65_536) layout.put(bytes) else layout.put(bytes, 0, 32_768).put(bytes, n - 32_768, 32_768)
        return BookId(Xxh3Port.hash(layout.array(), layout.capacity()))
    }

    @Test
    fun hashesUnseenFilesAsTheLayoutSays() {
        for (size in listOf(0, 1, 65_535, 65_536, 65_537, 100_000, 300_000)) {
            val f = book("b$size.cbz", size, seed = size)
            assertEquals("size $size", expected(f.readBytes()), BookIds.of(f))
        }
    }

    @Test
    fun aFileSeenBeforeIsKnownWithoutReading() {
        val f = book("a.cbz", 200_000)
        assertNull(BookIds.known(f))
        val id = BookIds.of(f)
        assertEquals(id, BookIds.known(f))
        // The index answers by path, size and time alone: the contents are not read again.
        val time = f.lastModified()
        f.writeBytes(ByteArray(200_000))
        f.setLastModified(time)
        assertEquals(id, BookIds.of(f))
    }

    @Test
    fun aChangedFileIsHashedAgain() {
        val f = book("a.cbz", 200_000)
        val id = BookIds.of(f)
        f.writeBytes(Random(9).nextBytes(200_000))
        f.setLastModified(f.lastModified() + 5_000)
        assertNull(BookIds.known(f))
        val now = BookIds.of(f)
        assertNotEquals(id, now)
        assertEquals(expected(f.readBytes()), now)
    }

    @Test
    fun copiesMovesAndRenamesKeepTheId() {
        val f = book("Series/one.cbz", 150_000)
        val id = BookIds.of(f)
        val copy = tmp.root.resolve("elsewhere/copy of one.cbz").also { it.parentFile!!.mkdirs(); f.copyTo(it) }
        assertEquals(id, BookIds.of(copy))
        val moved = tmp.root.resolve("moved.cbz")
        assertTrue(f.renameTo(moved))
        assertEquals(id, BookIds.of(moved))
    }

    @Test
    fun theAppsMovesCarryKnownIds() {
        val f = book("Series/one.cbz", 150_000)
        val id = BookIds.of(f)
        val dir = tmp.root.resolve("Series")
        val to = tmp.root.resolve("Renamed")
        assertTrue(dir.renameTo(to))
        BookIds.moved(dir.absolutePath, to.absolutePath)
        assertEquals(id, BookIds.known(File(to, "one.cbz")))
        BookIds.removed(to.absolutePath)
        assertNull(BookIds.known(File(to, "one.cbz")))
    }

    @Test
    fun theIndexLastsAcrossInstances() {
        val f = book("a.cbz", 70_000)
        val id = BookIds.of(f)
        BookIndex.reset()
        val index = BookIndex(context)
        // Lookups never wait on the database: unknown until loaded.
        assertNull(index.get(f.absolutePath, f.length(), f.lastModified()))
        index.load()
        assertEquals(id, index.get(f.absolutePath, f.length(), f.lastModified()))
        assertEquals(id, index.last(f.absolutePath))
    }

    @Test
    fun aFolderOrMissingFileHasNone() {
        assertNull(BookIds.find(tmp.newFolder("dir")))
        assertNull(BookIds.find(File(tmp.root, "missing.cbz")))
    }

    @Test
    fun bothEndsOfAShareFileAreAskedForAtOnceAndKept() {
        val shareDir = tmp.newFolder("share")
        val bytes = Random(3).nextBytes(500_000)
        File(shareDir, "a.cbz").writeBytes(bytes)
        val fake = FakeRemoteFs(shareDir)
        // Every read waits until two are under way: one read after the other would never finish.
        val gate = CountDownLatch(1)
        Remotes.serve(listOf(FakeRemoteFs.share())) { fake }
        val file = Remotes.file("/net/s1/a.cbz") as RemoteFile
        file.fresh()
        fake.slowReads = 0L..Long.MAX_VALUE
        fake.slow = gate
        val opener = Thread { while (fake.held.get() < 2) Thread.sleep(5); gate.countDown() }.apply { start() }
        val id = BookIds.of(file)
        opener.join(5_000)
        assertEquals(expected(bytes), id)
        assertEquals(65_536L, fake.bytesRead)
        fake.slow = null
        // Opening the book next starts from both ends, read once.
        val ends = FileEnds.of(file.path, bytes.size.toLong())
        assertEquals(listOf(0L, 500_000L - 32_768), ends.map { it.first })
        assertArrayEquals(bytes.copyOfRange(500_000 - 32_768, 500_000), ends[1].second)
        RemoteChannel.open(file.path).use { channel ->
            channel.position(500_000L - 100)
            channel.read(ByteBuffer.allocate(100))
            assertEquals(0, channel.fetches)
        }
        assertEquals(id, BookIds.known(file))
    }

    @Test
    fun oneFileIsHashedOnceHoweverManyAsk() {
        val shareDir = tmp.newFolder("share")
        File(shareDir, "a.cbz").writeBytes(Random(4).nextBytes(400_000))
        val fake = FakeRemoteFs(shareDir)
        Remotes.serve(listOf(FakeRemoteFs.share())) { fake }
        val file = Remotes.file("/net/s1/a.cbz") as RemoteFile
        file.fresh()
        val gate = CountDownLatch(1)
        fake.slowReads = 0L..Long.MAX_VALUE
        fake.slow = gate
        val ids = arrayOfNulls<BookId>(4)
        val threads = (0 until 4).map { i -> Thread { ids[i] = BookIds.of(file) }.apply { start() } }
        while (fake.held.get() < 1) Thread.sleep(5)
        Thread.sleep(50)
        gate.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(1, ids.toSet().size)
        assertEquals(65_536L, fake.bytesRead)
    }

    @Test
    fun locationsTellDeviceVolumesAndShares() {
        assertEquals(BookLocation.DEVICE, BookLocation.of("/storage/emulated/0/Comics/a.cbz"))
        assertEquals(BookLocation.DEVICE, BookLocation.of("/data/user/0/app/cache/incoming/a.cbz"))
        assertEquals("volume:1234-ABCD", BookLocation.of("/storage/1234-ABCD/Comics/a.cbz"))
        assertEquals("volume:1234-ABCD", BookLocation.of("/mnt/media_rw/1234-ABCD/a.cbz"))
        assertEquals("share:s1", BookLocation.of("/net/s1/Comics/a.cbz"))
    }
}
