package minty.emanata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageRootTest {

    private val device = StorageRoot("/storage/emulated/0", "Device", StorageRoot.Kind.DEVICE)
    private val card = StorageRoot("/storage/1234-ABCD", "SD card", StorageRoot.Kind.REMOVABLE)
    private val roots = listOf(device, card)

    @Test
    fun aToldRemovalQuietsTheNextScreenOnce() {
        RemovalNotices.told(card, now = 1_000)
        assertFalse(RemovalNotices.alreadyTold(device, now = 2_000))
        assertTrue(RemovalNotices.alreadyTold(card, now = 2_000))
        assertFalse(RemovalNotices.alreadyTold(card, now = 2_000))
        // Long after, the drive's removal is news again.
        RemovalNotices.told(card, now = 1_000)
        assertFalse(RemovalNotices.alreadyTold(card, now = 1_000 + RemovalNotices.QUIET_MS))
    }

    @Test
    fun containsOnlyWholeFolders() {
        assertTrue(device.contains("/storage/emulated/0"))
        assertTrue(device.contains("/storage/emulated/0/Comics"))
        assertFalse(device.contains("/storage/emulated/01"))
        assertFalse(device.contains("/storage/emulated"))
    }

    @Test
    fun rootOfPicksTheVolume() {
        assertEquals(card, StorageRoot.of("/storage/1234-ABCD/a/b", roots))
        assertEquals(device, StorageRoot.of("/storage/emulated/0", roots))
        assertNull(StorageRoot.of("/data/local", roots))
    }

    @Test
    fun innermostRootWins() {
        val share = StorageRoot("/storage/emulated/0/mnt", "Share", StorageRoot.Kind.SHARE)
        assertEquals(share, StorageRoot.of("/storage/emulated/0/mnt/x", roots + share))
    }

    @Test
    fun segmentsBelowTheRoot() {
        assertEquals(listOf("attic", "Comics"), device.segments("/storage/emulated/0/attic/Comics"))
        assertEquals(emptyList<String>(), device.segments("/storage/emulated/0"))
        assertEquals(emptyList<String>(), device.segments("/elsewhere"))
    }

    @Test
    fun crumbsNameTheRoot() {
        val crumbs = Crumbs.of("/storage/emulated/0/attic/Comics", roots)
        assertEquals(listOf("Device", "attic", "Comics"), crumbs.map { it.label })
        assertEquals(
            listOf("/storage/emulated/0", "/storage/emulated/0/attic", "/storage/emulated/0/attic/Comics"),
            crumbs.map { it.path }
        )
        assertEquals(listOf(true, false, false), crumbs.map { it.isRoot })
    }

    @Test
    fun crumbsOfARootAlone() {
        assertEquals(listOf(Crumb("SD card", card.path, true)), Crumbs.of(card.path, roots))
    }

    @Test
    fun crumbsOutsideRootsStartBelowTheSystemFolders() {
        val crumbs = Crumbs.of("/mnt/media", roots)
        assertEquals(listOf("mnt", "media"), crumbs.map { it.label })
        assertEquals(listOf("/mnt", "/mnt/media"), crumbs.map { it.path })
        assertEquals(listOf("5678-EF01", "Comics"), Crumbs.of("/storage/5678-EF01/Comics", roots).map { it.label })
        assertEquals(listOf("10", "x"), Crumbs.of("/storage/emulated/10/x", roots).map { it.label })
    }

    @Test
    fun describeDropsTheDeviceRootAndNamesOthers() {
        assertEquals("attic/Comics", StorageRoot.describe("/storage/emulated/0/attic/Comics", roots))
        assertEquals("", StorageRoot.describe("/storage/emulated/0", roots))
        assertEquals("SD card/Comics/A", StorageRoot.describe("/storage/1234-ABCD/Comics/A", roots))
        assertEquals("SD card", StorageRoot.describe("/storage/1234-ABCD", roots))
        assertEquals("/mnt/media", StorageRoot.describe("/mnt/media", roots))
    }

    @Test
    fun aSystemFolderIsOneCrumb() {
        assertEquals(listOf(Crumb("/", "/", true)), Crumbs.of("/", roots))
        assertEquals(listOf(Crumb("storage", "/storage", true)), Crumbs.of("/storage", roots))
        assertEquals(listOf(Crumb("emulated", "/storage/emulated/", true)), Crumbs.of("/storage/emulated/", roots))
    }

    @Test
    fun boundIsTheRootElseTheFirstFolderBelowTheSystemOnes() {
        assertEquals(card, StorageRoot.bound("/storage/1234-ABCD/a", roots))
        assertEquals("/storage/5678-EF01", StorageRoot.bound("/storage/5678-EF01/a/b", roots)?.path)
        assertEquals("/storage/emulated/10", StorageRoot.bound("/storage/emulated/10", roots)?.path)
        assertNull(StorageRoot.bound("/storage/emulated", roots))
        assertNull(StorageRoot.bound("/", roots))
    }

    @Test
    fun removedDrives() {
        val usb = StorageRoot("/storage/1A2B-3C4D", "Drive A", StorageRoot.Kind.USB)
        val before = listOf(device, card, usb)
        assertEquals(usb, StorageRoot.removed("/storage/1A2B-3C4D/books/a.cbz", before, listOf(device, card)))
        assertEquals(card, StorageRoot.removed("/storage/1234-ABCD/a.cbz", before, listOf(device, usb)))
        // Still mounted, on the device itself, or on no root at all.
        assertEquals(null, StorageRoot.removed("/storage/1A2B-3C4D/a.cbz", before, before))
        assertEquals(null, StorageRoot.removed("/storage/emulated/0/a.cbz", before, emptyList()))
        assertEquals(null, StorageRoot.removed("/mnt/elsewhere/a.cbz", before, emptyList()))
        // A share removed from the app counts too.
        val share = StorageRoot("/net/s1", "Share A", StorageRoot.Kind.SHARE)
        assertEquals(share, StorageRoot.removed("/net/s1/a.cbz", before + share, before))
    }

    @Test
    fun addedDrives() {
        val usb = StorageRoot("/storage/1A2B-3C4D", "Drive A", StorageRoot.Kind.USB)
        val share = StorageRoot("/net/s1", "Share A", StorageRoot.Kind.SHARE)
        assertEquals(usb, StorageRoot.added(listOf(device, card), listOf(device, card, usb)))
        assertEquals(card, StorageRoot.added(listOf(device), listOf(device, card)))
        // Nothing new, a drive pulled out, or a new share is no plugged-in drive.
        assertEquals(null, StorageRoot.added(listOf(device, usb), listOf(device, usb)))
        assertEquals(null, StorageRoot.added(listOf(device, usb), listOf(device)))
        assertEquals(null, StorageRoot.added(listOf(device), listOf(device, share)))
    }
}
