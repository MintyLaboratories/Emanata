package com.infinity.comicreader.files

import com.infinity.comicreader.net.DavFs
import com.infinity.comicreader.net.FakeDavServer
import com.infinity.comicreader.net.FakeRemoteFs
import com.infinity.comicreader.net.RemoteException
import com.infinity.comicreader.net.Remotes
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
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import kotlin.random.Random

/** Copying and moving within the device, onto a share and back: checks, side names, canceling, replacing. */
class TransferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var device: File
    private lateinit var shareDir: File
    private lateinit var fake: FakeRemoteFs

    @Before
    fun setUp() {
        device = tmp.newFolder("device")
        shareDir = tmp.newFolder("share")
        fake = FakeRemoteFs(shareDir)
        Remotes.serve(listOf(FakeRemoteFs.share())) { fake }
    }

    @After
    fun tearDown() {
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    private fun write(f: File, size: Int, seed: Int = size): ByteArray {
        f.parentFile!!.mkdirs()
        return Random(seed).nextBytes(size).also { f.writeBytes(it) }
    }

    /** [rel] on the device, in the device's own form. */
    private fun p(rel: String) = File(device, rel).path

    /** Names in [dir], side names included. */
    private fun names(dir: File) = dir.list()!!.sorted()

    @Test
    fun copiesAFileUnderASideNameThenRenamesIt() {
        val bytes = write(File(device, "A/book.cbz"), 3_000_000)
        File(device, "B").mkdirs()
        val seen = ArrayList<Long>()
        var total = 0L
        val result = Transfer(progress = { d, t -> seen += d; total = t }).run(p("A/book.cbz"), p("B"), "book.cbz", move = false, replace = false)
        assertEquals(p("B/book.cbz"), result.path)
        assertArrayEquals(bytes, File(device, "B/book.cbz").readBytes())
        assertTrue(File(device, "A/book.cbz").isFile)
        assertEquals(listOf("book.cbz"), names(File(device, "B")))
        assertEquals(3_000_000L, total)
        assertEquals(total, seen.last())
    }

    @Test
    fun keepsTheModificationTimeOfDeviceCopies() {
        write(File(device, "a.cbz"), 10)
        File(device, "a.cbz").setLastModified(1_600_000_000_000L)
        File(device, "B").mkdirs()
        Transfer().run(p("a.cbz"), p("B"), "a.cbz", move = false, replace = false)
        assertEquals(1_600_000_000_000L, File(device, "B/a.cbz").lastModified())
    }

    @Test
    fun movesWithinOneSpaceByRenaming() {
        write(File(device, "A/Series/001.cbz"), 100)
        File(device, "B").mkdirs()
        var reported = false
        Transfer(progress = { _, _ -> reported = true }).run(p("A/Series"), p("B"), "Series", move = true, replace = false)
        assertTrue(File(device, "B/Series/001.cbz").isFile)
        assertFalse(File(device, "A/Series").exists())
        // Nothing was copied.
        assertFalse(reported)
    }

    @Test
    fun movesAFolderOntoAShareCheckingEveryFileThenDeletesTheSource() {
        val one = write(File(device, "Series/001.cbz"), 200_000)
        val two = write(File(device, "Series/Extras/cover.jpg"), 5_000)
        var last = 0L to 0L
        val result = Transfer(progress = { d, t -> last = d to t }).run(p("Series"), "/net/s1", "Series", move = true, replace = false)
        assertEquals("/net/s1/Series", result.path)
        assertFalse(result.sourceLeft)
        assertArrayEquals(one, File(shareDir, "Series/001.cbz").readBytes())
        assertArrayEquals(two, File(shareDir, "Series/Extras/cover.jpg").readBytes())
        assertFalse(File(device, "Series").exists())
        assertEquals(listOf("Series"), names(shareDir))
        // Each byte written, then read back.
        assertEquals(2L * (200_000 + 5_000), last.second)
        assertEquals(last.second, last.first)
    }

    @Test
    fun copiesFromAShareToTheDevice() {
        val bytes = write(File(shareDir, "Loose.cbz"), 70_000)
        Transfer().run("/net/s1/Loose.cbz", device.path, "Loose.cbz", move = false, replace = false)
        assertArrayEquals(bytes, File(device, "Loose.cbz").readBytes())
        assertTrue(File(shareDir, "Loose.cbz").isFile)
    }

    @Test
    fun movesFromAWebDavShareThatTellsNoSizes() {
        val dav = tmp.newFolder("dav")
        FakeDavServer(dav).use { server ->
            server.sizes = false
            Remotes.serve(listOf(server.share("d1"))) { DavFs(it) }
            val bytes = write(File(dav, "a.cbz"), 100_000)
            Transfer().run("/net/d1/a.cbz", device.path, "a.cbz", move = true, replace = false)
            assertArrayEquals(bytes, File(device, "a.cbz").readBytes())
            assertFalse(File(dav, "a.cbz").exists())
        }
    }

    @Test
    fun aCopyThatDiffersFromItsSourceFailsTheMoveKeepingTheSource() {
        val bytes = write(File(device, "book.cbz"), 50_000)
        // A share that stores one byte wrong.
        val flipping = object : FileSpace by ShareSpace("s1") {
            override fun sameAs(other: FileSpace) = other === this

            override fun create(path: String, input: InputStream, length: Long, modified: Long) =
                Remotes.create(path, Flip(input), length)
        }
        val spaces = { p: String -> if (Remotes.isRemote(p)) flipping else DeviceSpace }
        try {
            Transfer(spaceOf = spaces).run(p("book.cbz"), "/net/s1", "book.cbz", move = true, replace = false)
            fail("no failure")
        } catch (e: FileProblem) {
            assertEquals(FileProblem.Kind.DIFFERS, e.kind)
            assertEquals("book.cbz", e.name)
        }
        assertArrayEquals(bytes, File(device, "book.cbz").readBytes())
        assertEquals(emptyList<String>(), names(shareDir))
    }

    @Test
    fun aMoveToAnotherDriveOfTheDeviceChecksEveryCopy() {
        val bytes = write(File(device, "A/book.cbz"), 50_000)
        File(device, "B").mkdirs()
        // Another drive: renaming fails, so the move copies; this one stores a byte wrong.
        val otherDrive = object : FileSpace by DeviceSpace {
            override fun rename(from: String, to: String) = false

            override fun create(path: String, input: InputStream, length: Long, modified: Long) =
                DeviceSpace.create(path, Flip(input), length, modified)
        }
        try {
            Transfer(spaceOf = { otherDrive }).run(p("A/book.cbz"), p("B"), "book.cbz", move = true, replace = false)
            fail("no failure")
        } catch (e: FileProblem) {
            assertEquals(FileProblem.Kind.DIFFERS, e.kind)
        }
        assertArrayEquals(bytes, File(device, "A/book.cbz").readBytes())
        assertEquals(emptyList<String>(), names(File(device, "B")))
    }

    @Test
    fun cancelingDeletesThePartCopy() {
        write(File(device, "big.cbz"), 4_000_000)
        File(device, "B").mkdirs()
        var reads = 0
        try {
            Transfer(alive = { reads++ < 2 }).run(p("big.cbz"), p("B"), "big.cbz", move = false, replace = false)
            fail("not canceled")
        } catch (e: CancellationException) {
            // Expected.
        }
        assertEquals(emptyList<String>(), names(File(device, "B")))
        assertTrue(File(device, "big.cbz").isFile)
    }

    @Test
    fun aCopyDroppedByTheNetworkIsDeletedOnceTheShareIsBack() {
        write(File(device, "book.cbz"), 300_000)
        fake.dropWritesAfter = 100_000
        val waits = ArrayList<Long>()
        try {
            // The network comes back after the first wait.
            Transfer(pause = { waits += it; fake.offline = false }).run(p("book.cbz"), "/net/s1", "book.cbz", move = true, replace = false)
            fail("no failure")
        } catch (e: RemoteException) {
            assertEquals("dropped", e.message)
        }
        // Deleted although the share counts as unreachable for a while after the failure.
        assertTrue(Remotes.unreachable("s1"))
        assertEquals(1, waits.size)
        assertEquals(emptyList<String>(), names(shareDir))
        assertTrue(File(device, "book.cbz").isFile)
    }

    @Test
    fun aFailedCopyLeavesWhatItWouldReplace() {
        write(File(device, "A/book.cbz"), 10)
        val old = write(File(device, "B/book.cbz"), 20)
        val broken = object : FileSpace by DeviceSpace {
            override fun sameAs(other: FileSpace) = false

            override fun open(path: String): InputStream = object : InputStream() {
                override fun read(): Int = throw IOException("unreadable")
            }
        }
        try {
            Transfer(spaceOf = { path -> if (path.startsWith(p("A"))) broken else DeviceSpace })
                .run(p("A/book.cbz"), p("B"), "book.cbz", move = true, replace = true)
            fail("no failure")
        } catch (e: IOException) {
            assertEquals("unreadable", e.message)
        }
        assertArrayEquals(old, File(device, "B/book.cbz").readBytes())
        assertEquals(listOf("book.cbz"), names(File(device, "B")))
        assertTrue(File(device, "A/book.cbz").isFile)
    }

    @Test
    fun replacesOnlyOnceTheNewCopyIsComplete() {
        val new = write(File(device, "book.cbz"), 30)
        write(File(shareDir, "book.cbz"), 99)
        Transfer().run(p("book.cbz"), "/net/s1", "book.cbz", move = false, replace = true)
        assertArrayEquals(new, File(shareDir, "book.cbz").readBytes())
        assertEquals(listOf("book.cbz"), names(shareDir))
        // A move within one space replaces too.
        write(File(device, "B/book.cbz"), 5)
        Transfer().run(p("book.cbz"), p("B"), "book.cbz", move = true, replace = true)
        assertArrayEquals(new, File(device, "B/book.cbz").readBytes())
        assertEquals(listOf("book.cbz"), names(File(device, "B")))
    }

    @Test
    fun refusesToOverwriteUnasked() {
        write(File(device, "book.cbz"), 30)
        val there = write(File(device, "B/book.cbz"), 5)
        try {
            Transfer().run(p("book.cbz"), p("B"), "book.cbz", move = false, replace = false)
            fail("no failure")
        } catch (e: IOException) {
            // Expected.
        }
        assertArrayEquals(there, File(device, "B/book.cbz").readBytes())
    }

    @Test
    fun neverReplacesWhatHoldsTheSource() {
        val inner = write(File(device, "Box/Box/inner.cbz"), 40)
        try {
            Transfer().run(p("Box/Box"), device.path, "Box", move = true, replace = true)
            fail("no failure")
        } catch (e: FileProblem) {
            assertEquals(FileProblem.Kind.HOLDS_SOURCE, e.kind)
        }
        assertArrayEquals(inner, File(device, "Box/Box/inner.cbz").readBytes())
    }

    @Test
    fun neverPutsAFolderInsideItself() {
        File(device, "Series/Sub").mkdirs()
        for (into in listOf(p("Series"), p("Series/Sub"))) {
            try {
                Transfer().run(p("Series"), into, "Series", move = true, replace = false)
                fail("no failure")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        }
        assertTrue(File(device, "Series/Sub").isDirectory)
    }

    @Test
    fun aMoveDeletesOnlyWhatItCopied() {
        write(File(device, "Series/001.cbz"), 1000)
        // A file comes into the folder while it is copied.
        val growing = object : FileSpace by DeviceSpace {
            override fun open(path: String): InputStream {
                File(device, "Series/002.cbz").writeBytes(ByteArray(5))
                return DeviceSpace.open(path)
            }
        }
        val result = Transfer(spaceOf = { if (Remotes.isRemote(it)) ShareSpace("s1") else growing })
            .run(p("Series"), "/net/s1", "Series", move = true, replace = false)
        assertTrue(result.sourceLeft)
        assertEquals(listOf("001.cbz"), names(File(shareDir, "Series")))
        assertEquals(listOf("002.cbz"), names(File(device, "Series")))
    }

    @Test
    fun linksAreLeftOutAndKeptWithWhatTheyPointTo() {
        write(File(device, "Series/001.cbz"), 100)
        val target = write(File(device, "Series/Elsewhere/other.cbz"), 200)
        val links = object : FileSpace by DeviceSpace {
            override fun list(path: String) = DeviceSpace.list(path).map { if (it.name == "Elsewhere") it.copy(isLink = true) else it }

            override fun stat(path: String) = DeviceSpace.stat(path)?.let { if (it.name == "Elsewhere") it.copy(isLink = true) else it }
        }
        val spaces = { path: String -> if (Remotes.isRemote(path)) ShareSpace("s1") else links }
        Transfer(spaceOf = spaces).run(p("Series"), "/net/s1", "Series", move = false, replace = false)
        assertEquals(listOf("001.cbz"), names(File(shareDir, "Series")))
        File(shareDir, "Series").deleteRecursively()

        val result = Transfer(spaceOf = spaces).run(p("Series"), "/net/s1", "Series", move = true, replace = false)
        assertTrue(result.sourceLeft)
        assertEquals(listOf("Elsewhere"), names(File(device, "Series")))
        assertArrayEquals(target, File(device, "Series/Elsewhere/other.cbz").readBytes())
        // A link itself is not copied.
        try {
            Transfer(spaceOf = spaces).run(p("Series/Elsewhere"), "/net/s1", "Elsewhere", move = false, replace = false)
            fail("no failure")
        } catch (e: IOException) {
            // Expected.
        }
    }

    @Test
    fun sweepingPutsRightWhatUnfinishedTransfersLeft() {
        val now = 10_000_000_000L
        val old = (now - Transfer.SWEEP_AGE_MS).toString(36)
        val young = (now - 1_000).toString(36)
        val dir = File(device, "Shelf").apply { mkdirs() }
        // A copy under way: deleted.
        write(File(dir, ".new.cbz.$old.emanata-new"), 10)
        File(dir, ".Series.$old.emanata-new/001.cbz").let { write(it, 10) }
        // Set aside, its name free: back. Two set aside: the newer comes back, the other goes.
        val series = write(File(dir, ".Kept.$old.emanata-old/001.cbz"), 20)
        write(File(dir, ".Kept.${(now - 2 * Transfer.SWEEP_AGE_MS).toString(36)}.emanata-old/001.cbz"), 30)
        // Set aside with its name taken again: the replacement finished, so it goes.
        write(File(dir, ".done.cbz.$old-1.emanata-old"), 5)
        val done = write(File(dir, "done.cbz"), 6)
        // Too young: another device may be copying it.
        write(File(dir, ".fresh.cbz.$young.emanata-new"), 7)
        write(File(dir, ".notes.txt"), 8)
        assertTrue(Transfer.sweep(DeviceSpace, dir.path, names(dir), now))
        assertEquals(listOf(".fresh.cbz.$young.emanata-new", ".notes.txt", "Kept", "done.cbz"), names(dir))
        assertArrayEquals(series, File(dir, "Kept/001.cbz").readBytes())
        assertArrayEquals(done, File(dir, "done.cbz").readBytes())
        assertFalse(Transfer.sweep(DeviceSpace, dir.path, names(dir), now))
    }

    @Test
    fun sweepingLeavesTheSideNamesOfTransfersUnderWay() {
        write(File(device, "big.cbz"), 3_000_000)
        val dir = File(device, "B").apply { mkdirs() }
        var swept = false
        // Made long ago, as far as its name tells.
        Transfer(stamp = { "1" }, alive = {
            if (!swept && dir.list()!!.isNotEmpty()) swept = !Transfer.sweep(DeviceSpace, dir.path, names(dir))
            true
        }).run(p("big.cbz"), dir.path, "big.cbz", move = false, replace = false)
        assertTrue(swept)
        assertEquals(listOf("big.cbz"), names(dir))
        // Once it ended, the same name is swept.
        write(File(dir, ".big.cbz.1.emanata-new"), 3)
        assertTrue(Transfer.sweep(DeviceSpace, dir.path, names(dir)))
    }

    /** Files in memory by `/` paths, where names differ in case only unless [ignoreCase]. */
    private class Memory(private val ignoreCase: Boolean) : FileSpace {
        val files = LinkedHashMap<String, String>()

        private fun key(path: String) = if (ignoreCase) path.lowercase() else path

        fun add(path: String) {
            files[key(path)] = path
        }

        fun paths() = files.values.sorted()

        override fun sameAs(other: FileSpace) = other === this

        override fun stat(path: String) = files[key(path)]?.let { Node(it.substringAfterLast('/'), false, 1) }

        override fun list(path: String) = files.values.filter { it.substringBeforeLast('/') == path }.map { stat(it)!! }

        override fun mkdir(path: String) = throw IOException("no folders")

        override fun open(path: String): InputStream = throw IOException("no reading")

        override fun create(path: String, input: InputStream, length: Long, modified: Long) = throw IOException("no writing")

        override fun rename(from: String, to: String): Boolean {
            val stored = files.remove(key(from)) ?: throw FileNotFoundException(from)
            if (key(to) in files) {
                files[key(from)] = stored
                throw IOException("Already there: $to")
            }
            files[key(to)] = to
            return true
        }

        override fun delete(path: String) {
            files.remove(key(path))
        }

        override fun remove(path: String) = delete(path)
    }

    @Test
    fun renamingChangesCaseOnlyWhereCaseIsIgnored() {
        val blind = Memory(ignoreCase = true).apply { add("/net/s1/Shelf/Sample.cbz") }
        assertEquals("/net/s1/Shelf/sample.cbz", Transfer(spaceOf = { blind }).rename("/net/s1/Shelf/Sample.cbz", "sample.cbz"))
        assertEquals(listOf("/net/s1/Shelf/sample.cbz"), blind.paths())
        // Another item by that name is a clash.
        blind.add("/net/s1/Shelf/Other.cbz")
        try {
            Transfer(spaceOf = { blind }).rename("/net/s1/Shelf/sample.cbz", "OTHER.cbz")
            fail("no failure")
        } catch (e: FileWork.NameTaken) {
            // Expected.
        }
        assertEquals(listOf("/net/s1/Shelf/Other.cbz", "/net/s1/Shelf/sample.cbz"), blind.paths())

        // Where case tells names apart, an item differing in case only is another one.
        val strict = Memory(ignoreCase = false).apply {
            add("/net/s1/Shelf/Sample.cbz")
            add("/net/s1/Shelf/sample.cbz")
        }
        try {
            Transfer(spaceOf = { strict }).rename("/net/s1/Shelf/Sample.cbz", "sample.cbz")
            fail("no failure")
        } catch (e: FileWork.NameTaken) {
            // Expected.
        }
        assertEquals(listOf("/net/s1/Shelf/Sample.cbz", "/net/s1/Shelf/sample.cbz"), strict.paths())
        strict.files.remove("/net/s1/Shelf/sample.cbz")
        assertEquals("/net/s1/Shelf/SAMPLE.cbz", Transfer(spaceOf = { strict }).rename("/net/s1/Shelf/Sample.cbz", "SAMPLE.cbz"))
        assertEquals(listOf("/net/s1/Shelf/SAMPLE.cbz"), strict.paths())
    }

    @Test
    fun messagesNameItemsWithoutTheirPaths() {
        assertEquals(
            "book.cbz: open failed: EACCES (Permission denied)",
            FileProblem.namesOnly("/storage/emulated/0/A b/book.cbz: open failed: EACCES (Permission denied)")
        )
        assertEquals("Could not rename Old name.cbz", FileProblem.namesOnly("Could not rename /net/s1/Shelf/Old name.cbz"))
        assertEquals("Not a folder: Shelf", FileProblem.namesOnly("Not a folder: /net/s1/Shelf/"))
        assertEquals("HTTP 500", FileProblem.namesOnly("HTTP 500"))
    }

    @Test
    fun keepBothNumbersNamesOn() {
        val taken = setOf("Book (2).cbz", "Book (3).cbz", "Series (2)")
        assertEquals("Book (4).cbz", Transfer.keepBothName("Book.cbz", false) { it in taken })
        assertEquals("Book (4).cbz", Transfer.keepBothName("Book (3).cbz", false) { it in taken })
        assertEquals("Series (3)", Transfer.keepBothName("Series", true) { it in taken })
        assertEquals("Vol. 1 (2)", Transfer.keepBothName("Vol. 1", true) { false })
        assertEquals(".hidden (2)", Transfer.keepBothName(".hidden", false) { false })
        assertEquals("Book.tar (2).cbt", Transfer.keepBothName("Book.tar.cbt", false) { false })
    }

    /** Changes the 1000th byte read. */
    private class Flip(input: InputStream) : FilterInputStream(input) {
        private var at = 0L

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0 && at <= 1000 && at + n > 1000) b[off + (1000 - at).toInt()] = (b[off + (1000 - at).toInt()] + 1).toByte()
            if (n > 0) at += n
            return n
        }
    }
}
