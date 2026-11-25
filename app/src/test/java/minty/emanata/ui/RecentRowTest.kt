package minty.emanata.ui

import minty.emanata.data.BookId
import minty.emanata.data.HistoryItem
import minty.emanata.data.StorageRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RecentRowTest {
    private val device = StorageRoot("/storage/emulated/0", "Device", StorageRoot.Kind.DEVICE)
    private val share = StorageRoot("/net/s1", "Share A", StorageRoot.Kind.SHARE)

    private fun item(path: String) = HistoryItem(path, path.substringAfterLast('/'), 0, 10, 1L, BookId(path.hashCode().toLong()))

    @Test
    fun pointsAtTheFolderOfTheNewestBook() {
        val history = listOf(item("/storage/emulated/0/Comics/Series/02.cbz"), item("/storage/emulated/0/Other/01.cbz"))
        assertEquals("/storage/emulated/0/Comics/Series", RecentRow.folderOf(history) { false })
    }

    @Test
    fun skipsBooksOnRemovedShares() {
        val history = listOf(item("/net/gone/Series/01.cbz"), item("/storage/emulated/0/Comics/01.cbz"))
        assertEquals("/storage/emulated/0/Comics", RecentRow.folderOf(history) { it.startsWith("/net/gone") })
    }

    @Test
    fun noRowWithoutHistory() {
        assertNull(RecentRow.folderOf(emptyList()) { false })
    }

    @Test
    fun namesDeviceFoldersBelowTheDeviceAndTheDeviceItself() {
        val roots = listOf(device, share)
        assertEquals("Comics/Series", RecentRow.subtitle("/storage/emulated/0/Comics/Series", roots) { null })
        assertEquals("Device", RecentRow.subtitle("/storage/emulated/0", roots) { null })
    }

    @Test
    fun namesShareFoldersAfterTheShare() {
        val roots = listOf(device, share)
        assertEquals("Share A/Series", RecentRow.subtitle("/net/s1/Series", roots) { null })
        assertEquals("Share A", RecentRow.subtitle("/net/s1", roots) { null })
    }

    @Test
    fun aShareTheRootsDoNotListYetIsStillNamed() {
        assertEquals("Share B/Series/Arc", RecentRow.subtitle("/net/s2/Series/Arc", listOf(device)) { if (it == "s2") "Share B" else null })
        assertEquals("Share B", RecentRow.subtitle("/net/s2", listOf(device)) { "Share B" })
    }

    @Test
    fun neverShowsARawSharePath() {
        val shown = RecentRow.subtitle("/net/s9/Series", listOf(device)) { null }
        assertFalse(shown, shown.contains("/net"))
        assertEquals("Series", shown)
    }
}
