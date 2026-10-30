package com.infinity.comicreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Presence comes from the device at once, from what is kept for shares, then from asking them. */
class PresenceCheckTest {

    private class FakeWorld : PresenceCheck.World {
        val files = HashSet<String>()
        val mountedRoots = HashSet<String>()
        val shares = hashSetOf("s1")
        val unreachable = HashSet<String>()
        val statsKept = HashSet<String>()
        val listings = HashMap<String, Set<String>>()
        val listedAt = HashMap<String, Long>()
        val onShare = HashSet<String>()
        var failing = false
        var asked = 0
        var mountChecks = 0
        var changes = 0L
        var clock = 1_000L

        override fun shareOf(path: String): String? =
            if (path.startsWith("/net/")) path.removePrefix("/net/").substringBefore('/') else null

        override fun shareKnown(id: String) = id in shares
        override fun shareUnreachable(id: String) = id in unreachable
        override fun statKept(path: String) = path in statsKept
        override fun namesKept(path: String) = listings[path]
        override fun listedAt(path: String) = listedAt[path]
        override fun stat(path: String): Boolean {
            asked++
            if (failing) throw IOException("down")
            return path in onShare
        }
        override fun exists(path: String) = path in files
        override fun mounted(root: String): Boolean {
            mountChecks++
            return root in mountedRoots
        }
        override fun changes() = changes
        override fun now() = clock
    }

    private val world = FakeWorld()
    private val check = PresenceCheck(world, PresenceCheck.Memo())

    private fun found(p: Presence, settled: Boolean) = PresenceCheck.Found(p, settled)

    @Test
    fun deviceItemsAreSettledAtOnce() {
        world.files += "/storage/emulated/0/Comics/a.cbz"
        assertEquals(found(Presence.PRESENT, true), check.quick("/storage/emulated/0/Comics/a.cbz"))
        assertEquals(found(Presence.MISSING, true), check.quick("/storage/emulated/0/Comics/b.cbz"))
        assertTrue(check.quick("/storage/emulated/0/Comics/b.cbz").missing)
    }

    @Test
    fun aVolumeNotMountedIsUnreachableNotMissing() {
        val path = "/storage/1234-ABCD/Comics/a.cbz"
        val gone = check.quick(path)
        assertEquals(Presence.UNREACHABLE, gone.presence)
        assertFalse(gone.missing)
        world.mountedRoots += "/storage/1234-ABCD"
        assertEquals(Presence.MISSING, check.quick(path).presence)
        world.files += path
        assertEquals(Presence.PRESENT, check.quick(path).presence)
    }

    @Test
    fun oneListLooksAtEachVolumeAndFolderOnce() {
        world.mountedRoots += "/storage/1234-ABCD"
        world.listings["/net/s1/Comics"] = setOf("a.cbz")
        val seen = PresenceCheck.Seen()
        for (i in 1..5) {
            check.quick("/storage/1234-ABCD/Comics/$i.cbz", seen)
            check.quick("/net/s1/Comics/$i.cbz", seen)
        }
        assertEquals(1, world.mountChecks)
        assertEquals(setOf("/net/s1/Comics"), seen.names.keys)
    }

    @Test
    fun aShareRemovedFromTheAppMakesItsBooksMissingAtOnce() {
        val f = check.quick("/net/gone/Comics/a.cbz")
        assertTrue(f.missing)
        assertFalse(check.asks("/net/gone/Comics/a.cbz", f))
    }

    @Test
    fun anUnreachableShareIsNeverAsked() {
        world.unreachable += "s1"
        world.statsKept += "/net/s1/a.cbz"
        val f = check.quick("/net/s1/a.cbz")
        assertEquals(found(Presence.UNREACHABLE, true), f)
        assertFalse(check.asks("/net/s1/a.cbz", f))
    }

    @Test
    fun keptStatsAndListingsShowFirstThenTheShareSettles() {
        world.statsKept += "/net/s1/a.cbz"
        world.listings["/net/s1/Comics"] = setOf("b.cbz")
        assertEquals(found(Presence.PRESENT, false), check.quick("/net/s1/a.cbz"))
        assertEquals(found(Presence.PRESENT, false), check.quick("/net/s1/Comics/b.cbz"))
        // Left out of its folder's kept listing: missing, until the share says.
        val c = check.quick("/net/s1/Comics/c.cbz")
        assertEquals(found(Presence.MISSING, false), c)
        assertFalse(c.missing)
        assertTrue(check.asks("/net/s1/Comics/c.cbz", c))
        assertEquals(found(Presence.UNKNOWN, false), check.quick("/net/s1/Other/d.cbz"))

        world.onShare += "/net/s1/Comics/c.cbz"
        assertEquals(found(Presence.PRESENT, true), check.stat("/net/s1/Comics/c.cbz"))
        assertEquals(found(Presence.MISSING, true), check.stat("/net/s1/a.cbz"))
    }

    @Test
    fun answersStandUntilTheAppChangesASharesOrTimePasses() {
        assertEquals(Presence.MISSING, check.stat("/net/s1/a.cbz").presence)
        assertEquals(found(Presence.MISSING, true), check.quick("/net/s1/a.cbz"))
        world.changes++
        assertEquals(found(Presence.UNKNOWN, false), check.quick("/net/s1/a.cbz"))

        check.stat("/net/s1/a.cbz")
        world.clock += PresenceCheck.MEMO_MS
        assertEquals(found(Presence.UNKNOWN, false), check.quick("/net/s1/a.cbz"))
    }

    @Test
    fun aFolderListedSinceAnAnswerOutranksIt() {
        assertTrue(check.stat("/net/s1/Comics/a.cbz").missing)
        world.clock += 1
        // Put back from elsewhere, then seen as its folder is browsed.
        world.listings["/net/s1/Comics"] = setOf("a.cbz")
        world.listedAt["/net/s1/Comics"] = world.clock
        val f = check.quick("/net/s1/Comics/a.cbz")
        assertEquals(found(Presence.PRESENT, false), f)
        assertTrue(check.asks("/net/s1/Comics/a.cbz", f))
        // A folder listed before the answer leaves it standing.
        world.listedAt["/net/s1/Comics"] = world.clock - 2
        assertTrue(check.quick("/net/s1/Comics/a.cbz").missing)
    }

    @Test
    fun aFailingShareIsUnreachableAndNothingIsKept() {
        world.failing = true
        assertEquals(found(Presence.UNREACHABLE, true), check.stat("/net/s1/a.cbz"))
        assertEquals(found(Presence.UNKNOWN, false), check.quick("/net/s1/a.cbz"))
    }

    @Test
    fun volumeRootsAreTheirTopFolders() {
        assertEquals("/storage/1234-ABCD", BookLocation.volumeRoot("/storage/1234-ABCD/x/y.cbz"))
        assertEquals("/mnt/media_rw/1234-ABCD", BookLocation.volumeRoot("/mnt/media_rw/1234-ABCD/y.cbz"))
        assertNull(BookLocation.volumeRoot("/storage/emulated/0/y.cbz"))
        assertNull(BookLocation.volumeRoot("/net/s1/y.cbz"))
        assertEquals("volume:1234-ABCD", BookLocation.of("/storage/1234-ABCD/x/y.cbz"))
    }
}
