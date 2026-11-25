package minty.emanata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardCrumbsTest {

    private val device = StorageRoot("/storage/emulated/0", "Device", StorageRoot.Kind.DEVICE)
    private val card = StorageRoot("/storage/1234-ABCD", "SD card", StorageRoot.Kind.REMOVABLE)
    private val roots = listOf(device, card)

    private val top = "/storage/emulated/0/books"
    private val deep = "$top/Comics/attic/Series A"

    @Test
    fun goingUpKeepsTheTrail() {
        assertEquals(deep, ForwardCrumbs.next(deep, top))
        assertEquals(deep, ForwardCrumbs.next(deep, "$top/Comics"))
        assertEquals(deep, ForwardCrumbs.next(deep, device.path))
    }

    @Test
    fun openingTheNextForwardFolderKeepsTheRest() {
        assertEquals(deep, ForwardCrumbs.next(deep, "$top/Comics/attic"))
        assertEquals(deep, ForwardCrumbs.next(deep, deep))
    }

    @Test
    fun openingAnotherFolderDropsTheTrail() {
        assertEquals("$top/Comics/shelf", ForwardCrumbs.next(deep, "$top/Comics/shelf"))
        assertEquals("/storage/1234-ABCD/Comics", ForwardCrumbs.next(deep, "/storage/1234-ABCD/Comics"))
        assertEquals(top, ForwardCrumbs.next(null, top))
    }

    @Test
    fun goingDeeperThanTheTrailExtendsIt() {
        assertEquals("$deep/Extras", ForwardCrumbs.next(deep, "$deep/Extras"))
    }

    @Test
    fun withinMatchesWholeFolderNames() {
        assertTrue(ForwardCrumbs.isWithin("$top/Comics", top))
        assertTrue(ForwardCrumbs.isWithin(top, "$top/"))
        assertFalse(ForwardCrumbs.isWithin("${top}2/Comics", top))
        assertFalse(ForwardCrumbs.isWithin(top, "$top/Comics"))
    }

    @Test
    fun crumbsShowTheTrailPastTheCurrentFolder() {
        val (crumbs, current) = ForwardCrumbs.crumbs(top, deep, roots)
        assertEquals(listOf("Device", "books", "Comics", "attic", "Series A"), crumbs.map { it.label })
        assertEquals(1, current)
        assertEquals("$top/Comics", crumbs[2].path)
    }

    @Test
    fun crumbsAtTheRootWithATrail() {
        val (crumbs, current) = ForwardCrumbs.crumbs(device.path, deep, roots)
        assertEquals(5, crumbs.size)
        assertEquals(0, current)
    }

    @Test
    fun crumbsWithoutATrailEndAtTheCurrentFolder() {
        val (crumbs, current) = ForwardCrumbs.crumbs(deep, deep, roots)
        assertEquals(crumbs.lastIndex, current)
        assertEquals(listOf("Device", "books"), ForwardCrumbs.crumbs(top, null, roots).first.map { it.label })
        // A trail elsewhere is not shown.
        val (other, at) = ForwardCrumbs.crumbs(top, "/storage/1234-ABCD/Comics", roots)
        assertEquals(listOf("Device", "books"), other.map { it.label })
        assertEquals(1, at)
    }
}
