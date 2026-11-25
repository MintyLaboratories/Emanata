package minty.emanata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MissingFolderTest {

    private val device = StorageRoot("/storage/emulated/0", "Device", StorageRoot.Kind.DEVICE)
    private val card = StorageRoot("/storage/1234-ABCD", "SD card", StorageRoot.Kind.REMOVABLE)
    private val roots = listOf(device, card)

    private val books = "/storage/emulated/0/books"
    private val existing = setOf(device.path, books, "$books/Comics", card.path)

    private fun nearest(path: String) = MissingFolder.nearest(path, roots) { it in existing }

    @Test
    fun anExistingFolderStays() {
        assertEquals("$books/Comics", nearest("$books/Comics"))
        assertEquals("$books/Comics", nearest("$books/Comics/"))
    }

    @Test
    fun aMissingFolderGoesToItsNearestExistingParent() {
        assertEquals("$books/Comics", nearest("$books/Comics/Series A"))
        assertEquals("$books/Comics", nearest("$books/Comics/Series A/Volume 1"))
        assertEquals(device.path, nearest("${device.path}/gone/deeper"))
    }

    @Test
    fun neverClimbsAboveItsRoot() {
        assertEquals(card.path, nearest("${card.path}/gone"))
        assertNull(MissingFolder.nearest("${card.path}/gone", roots) { it == "/storage" })
    }

    @Test
    fun aMissingRootHasNowhereToGo() {
        assertNull(MissingFolder.nearest("/storage/9999-0000/Comics", roots) { false })
    }

    @Test
    fun aFolderUnderNoRootStaysOnlyWhenItExists() {
        assertEquals("/", MissingFolder.nearest("/", roots) { true })
        assertNull(MissingFolder.nearest("/", roots) { false })
    }

    @Test
    fun forwardCrumbsThroughTheMissingFolderAreDropped() {
        val missing = "$books/Comics/Series A"
        assertNull(MissingFolder.trailAfter("$missing/Volume 1", missing))
        assertNull(MissingFolder.trailAfter(missing, missing))
        assertEquals("$books/Comics/Series B", MissingFolder.trailAfter("$books/Comics/Series B", missing))
        assertNull(MissingFolder.trailAfter(null, missing))
    }
}
