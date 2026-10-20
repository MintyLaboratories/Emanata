package com.infinity.comicreader.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteLabelsTest {

    private val device = StorageRoot("/storage/emulated/0", "Device", StorageRoot.Kind.DEVICE)
    private val shareA = StorageRoot("/shares/a", "Share A", StorageRoot.Kind.SHARE)
    private val shareB = StorageRoot("/shares/b", "Share B", StorageRoot.Kind.SHARE)
    private val roots = listOf(device, shareA, shareB)

    /** Labels of [paths], each given relative to the device root unless it is absolute. */
    private fun labels(vararg paths: String): List<String> {
        val absolute = paths.map { if (it.startsWith("/")) it else "${device.path}/$it" }
        val map = FavoriteLabels.of(absolute, roots)
        return absolute.map { map.getValue(it).text }
    }

    @Test
    fun oneInstanceKeepsItsName() {
        assertEquals(listOf("Comics", "Manga"), labels("stacks/Comics", "stacks/Manga"))
    }

    @Test
    fun sameDepthClashShowsTheParent() {
        assertEquals(
            listOf("stacks/Comics", "books/Comics", "attic/Comics"),
            labels("stacks/Comics", "books/Comics", "attic/Comics")
        )
    }

    @Test
    fun deeperClashSkipsSharedFolders() {
        assertEquals(
            listOf("stacks…Comics", "books…Comics"),
            labels("stacks/stuff/Comics", "books/stuff/Comics")
        )
    }

    @Test
    fun middleIsSpelledOutWhenAnotherItemHasThatAncestor() {
        assertEquals(
            listOf(
                "stacks/Comics", "books/Comics", "stacks/stuff/Comics", "books/stuff/Comics", "different/Comics"
            ),
            labels(
                "stacks/Comics", "books/Comics", "stacks/stuff/Comics", "books/stuff/Comics",
                "something/different/Comics"
            )
        )
    }

    @Test
    fun differentRootsShowTheRoot() {
        assertEquals(
            listOf("Share A…Comics", "Share B…Comics", "Device…Comics"),
            labels("/shares/a/stacks/Comics", "/shares/b/Comics", "attic/Comics")
        )
    }

    @Test
    fun itemsSharingARootUseParentsAmongThemselves() {
        assertEquals(
            listOf("Share A…Comics", "stacks/Comics", "attic/Comics"),
            labels("/shares/a/Comics", "stacks/Comics", "attic/Comics")
        )
    }

    @Test
    fun prefixAndNameAreSeparate() {
        val path = "${device.path}/stacks/Comics"
        val label = FavoriteLabels.of(listOf(path, "${device.path}/attic/Comics"), roots).getValue(path)
        assertEquals(FavoriteLabel("stacks/", "Comics"), label)
    }

    @Test
    fun aRootIsNamedByItsRoot() {
        assertEquals(listOf("Device", "Share A"), labels(device.path, shareA.path))
        assertEquals("Device", FavoriteLabels.nameOf(device.path, roots))
    }

    @Test
    fun clashesIgnoreCase() {
        assertEquals(listOf("a/Comics", "b/comics"), labels("a/Comics", "b/comics"))
    }

    @Test
    fun indistinguishableItemsAreNumbered() {
        // Folders that differ only in case on a case-sensitive volume.
        assertEquals(listOf("Comics", "Comics (2)"), labels("x/Comics", "X/Comics"))
    }

    @Test
    fun anItemAboveTheOthersShowsItsParent() {
        assertEquals(listOf("a…Comics", "x/Comics"), labels("a/x/Comics", "x/Comics"))
    }

    @Test
    fun pathsOutsideRootsUseTheirFolders() {
        val map = FavoriteLabels.of(listOf("/elsewhere/x/Comics", "/other/x/Comics"), roots)
        assertEquals("elsewhere…Comics", map.getValue("/elsewhere/x/Comics").text)
        assertEquals("other…Comics", map.getValue("/other/x/Comics").text)
    }

    @Test
    fun filesKeepTheirExtension() {
        assertEquals(listOf("a/Book 1.cbz", "b/Book 1.cbz"), labels("a/Book 1.cbz", "b/Book 1.cbz"))
    }
}
