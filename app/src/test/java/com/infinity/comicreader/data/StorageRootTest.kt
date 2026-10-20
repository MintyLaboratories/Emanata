package com.infinity.comicreader.data

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
    fun crumbsOutsideRootsStartAtSlash() {
        val crumbs = Crumbs.of("/mnt/media", roots)
        assertEquals(listOf("/", "mnt", "media"), crumbs.map { it.label })
        assertEquals(listOf("/", "/mnt", "/mnt/media"), crumbs.map { it.path })
    }
}
