package com.infinity.comicreader.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Folder listings kept in memory and on disk. */
class ListingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun stat(name: String, size: Long = 1, dir: Boolean = false) = RemoteStat(name, dir, size, 1_000, 900)

    @Test
    fun aListingIsWrittenOnlyWhenItChanged() {
        val dir = tmp.newFolder("listings")
        val listings = Listings(dir)
        assertTrue(listings.put("/net/s1/Series", listOf(stat("a.cbz"), stat("b.cbz"))))
        val file = dir.walkTopDown().single { it.isFile }
        file.setLastModified(1_000)
        // The same entries in another order are no change.
        assertFalse(listings.put("/net/s1/Series", listOf(stat("b.cbz"), stat("a.cbz"))))
        assertEquals(1_000, file.lastModified())
        assertTrue(listings.put("/net/s1/Series", listOf(stat("a.cbz", size = 2), stat("b.cbz"))))
        assertEquals(2L, Listings(dir).get("/net/s1/Series")?.first { it.name == "a.cbz" }?.size)
    }

    @Test
    fun aListingReadsBackWithEveryField() {
        val dir = tmp.newFolder("listings")
        val entries = listOf(stat("Sub", 0, dir = true), RemoteStat("x.cbz", false, 123_456_789_012, 5, 4))
        Listings(dir).put("/net/s1", entries)
        assertEquals(entries, Listings(dir).get("/net/s1"))
        assertNull(Listings(dir).get("/net/s1/Other"))
    }

    @Test
    fun aDamagedFileReadsAsNoListing() {
        val dir = tmp.newFolder("listings")
        Listings(dir).put("/net/s1/Series", listOf(stat("a.cbz")))
        dir.walkTopDown().single { it.isFile }.writeText("damaged")
        assertNull(Listings(dir).get("/net/s1/Series"))
    }

    @Test
    fun forgettingAShareDropsOnlyItsListings() {
        val dir = tmp.newFolder("listings")
        val listings = Listings(dir)
        listings.put("/net/s1/Series", listOf(stat("a.cbz")))
        listings.put("/net/s2/Series", listOf(stat("b.cbz")))
        listings.forget("s1")
        assertNull(listings.get("/net/s1/Series"))
        assertNull(Listings(dir).get("/net/s1/Series"))
        assertEquals(listOf("b.cbz"), Listings(dir).get("/net/s2/Series")?.map { it.name })
    }

    @Test
    fun withoutAFolderListingsLiveInMemoryOnly() {
        val listings = Listings(null, maxFolders = 2)
        listings.put("/net/s1/a", listOf(stat("1")))
        listings.put("/net/s1/b", listOf(stat("2")))
        listings.put("/net/s1/c", listOf(stat("3")))
        assertNull(listings.get("/net/s1/a"))
        assertEquals(listOf("3"), listings.get("/net/s1/c")?.map { it.name })
    }

    @Test
    fun trimmingDropsTheLeastRecentlyWritten() {
        val dir = tmp.newFolder("listings")
        val listings = Listings(dir)
        listings.put("/net/s1/old", List(50) { stat("old $it.cbz") })
        listings.put("/net/s1/new", List(50) { stat("new $it.cbz") })
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        files.forEach { it.setLastModified(if (String(it.readBytes(), Charsets.ISO_8859_1).contains("old 0")) 1_000 else 2_000) }
        Listings.trim(dir, maxBytes = files.maxOf { it.length() })
        val fresh = Listings(dir)
        assertNull(fresh.get("/net/s1/old"))
        assertEquals(50, fresh.get("/net/s1/new")?.size)
        assertTrue(File(dir, "s1").isDirectory)
    }
}
