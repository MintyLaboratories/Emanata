package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.testing.bookId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONArray
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Books kept for that no reachable location holds are found; the rest note where they are. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MissingBooksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var library: Library
    private lateinit var shelf: File
    private lateinit var device: MissingBooks.Location

    /** A card that is not mounted: its listing fails. */
    private val card = MissingBooks.Location("volume:AAAA-1111", "Card", "/storage/AAAA-1111")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
        shelf = tmp.newFolder("Shelf")
        device = MissingBooks.Location(BookLocation.DEVICE, "Device", shelf.path)
    }

    /** A book of [size] bytes of [seed] at [file], read once so the index knows it, and marked read. */
    private fun keep(file: File, size: Int, seed: Int): BookRef {
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(size) { (it * seed).toByte() })
        return BookRef(bookId(file), file.path).also { library.setRead(it, true) }
    }

    @Test
    fun booksFoundAnywhereStayAndMovedOnesNoteWhereTheyAreNow() {
        val there = keep(File(shelf, "A/there.cbz"), 300, 3)
        val moved = keep(File(shelf, "A/moved.cbz"), 400, 5)
        val gone = keep(File(shelf, "A/gone.cbz"), 500, 7)
        val to = File(shelf, "B/renamed.cbz").apply { parentFile!!.mkdirs() }
        assertTrue(File(moved.path).renameTo(to))
        File(gone.path).delete()
        // Unseen, and of no missing book's size: never read.
        val other = File(shelf, "B/other.cbz").apply { writeBytes(ByteArray(700) { 1 }) }

        val result = MissingBooks(library, listOf(device)).run()!!

        assertTrue(result.complete)
        assertEquals(listOf(gone.id), result.missing.map { it.id })
        assertEquals(Library.Kept(read = true), result.missing.single().kept)
        assertEquals(to.path, library.pathOf(moved.id))
        assertEquals(there.path, library.pathOf(there.id))
        assertNull(BookIds.known(other.path, other.length(), other.lastModified()))
    }

    @Test
    fun booksLastSeenWhereNoScanReachedAreKeptAndTheRestLeftUnticked() {
        val gone = keep(File(shelf, "gone.cbz"), 500, 7)
        File(gone.path).delete()
        val onCard = BookRef(BookId(0xcafe), "/storage/AAAA-1111/Comics/card.cbz")
        library.setRead(onCard, true)

        val result = MissingBooks(library, listOf(device, card)).run()!!

        assertFalse(result.complete)
        assertEquals(listOf("Card"), result.skipped)
        assertEquals(listOf(gone.id), result.missing.map { it.id })
    }

    @Test
    fun aCardNotMountedAtAllIsNamedByItsVolume() {
        val onCard = BookRef(BookId(0xcafe), "/storage/AAAA-1111/Comics/card.cbz")
        library.setRead(onCard, true)
        val result = MissingBooks(library, listOf(device)).run()!!
        assertEquals(listOf("AAAA-1111"), result.skipped)
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun booksOutsideEveryLocationAreLookedForWhereTheyWere() {
        val elsewhere = tmp.newFolder("Elsewhere")
        val kept = keep(File(elsewhere, "kept.cbz"), 300, 3)
        val gone = keep(File(elsewhere, "gone.cbz"), 400, 5)
        File(gone.path).delete()
        // Hidden folders are not walked, but a book seen there is found where it is.
        val hidden = keep(File(shelf, ".hidden/in.cbz"), 600, 11)

        val result = MissingBooks(library, listOf(device)).run()!!

        assertEquals(listOf(gone.id), result.missing.map { it.id })
        assertEquals(kept.path, library.pathOf(kept.id))
        assertEquals(hidden.path, library.pathOf(hidden.id))
    }

    @Test
    fun aCanceledScanFindsNothing() {
        keep(File(shelf, "a.cbz"), 300, 3)
        assertNull(MissingBooks(library, listOf(device), alive = { false }).run())
    }

    @Test
    fun progressCountsFoldersAndBooksButNotLooseImages() {
        keep(File(shelf, "A/a.cbz"), 300, 3)
        keep(File(shelf, "B/b.cbz"), 400, 5)
        File(shelf, "B/photo.jpg").writeBytes(ByteArray(100) { 2 })
        val seen = ArrayList<MissingBooks.Progress>()
        MissingBooks(library, listOf(device), progress = { synchronized(seen) { seen += it } }).run()
        assertEquals(MissingBooks.Progress("Device", 3, 2), seen.last())
    }

    @Test
    fun checkingUnknownFilesShowsHowFarItGot() {
        val moved = keep(File(shelf, "a.cbz"), 300, 3)
        val gone = keep(File(shelf, "b.cbz"), 400, 5)
        File(gone.path).delete()
        // Rewritten under another name: the index does not know it there.
        val bytes = File(moved.path).readBytes()
        File(moved.path).delete()
        File(shelf, "c.cbz").writeBytes(bytes)
        val seen = ArrayList<MissingBooks.Progress>()

        val result = MissingBooks(library, listOf(device), progress = { synchronized(seen) { seen += it } }, lanes = 2).run()!!

        assertEquals(listOf(gone.id), result.missing.map { it.id })
        assertEquals(File(shelf, "c.cbz").path, library.pathOf(moved.id))
        // Two last-seen paths, then the one file of a missing book's size.
        val checks = seen.filter { it.checking > 0 }
        assertEquals(MissingBooks.Progress("", 1, 1, 0, 3), checks.first())
        assertEquals(MissingBooks.Progress("", 1, 1, 3, 3), checks.last())
    }

    @Test
    fun aBookOfUnknownSizeIsLookedForWhereItWasOnlyAndLooseImagesAreNeverRead() {
        // Kept without a size: no file was ever read for it.
        val gone = BookRef(BookId(0xbeef), File(shelf, "gone.cbz").path)
        library.setRead(gone, true)
        val photo = File(shelf, "photo.jpg").apply { writeBytes(ByteArray(100) { 2 }) }
        val other = File(shelf, "other.cbz").apply { writeBytes(ByteArray(700) { 1 }) }
        val image = keep(File(shelf, "Pics/page.png"), 200, 13)

        val result = MissingBooks(library, listOf(device)).run()!!

        assertEquals(listOf(gone.id), result.missing.map { it.id })
        assertNull(BookIds.known(photo.path, photo.length(), photo.lastModified()))
        assertNull(BookIds.known(other.path, other.length(), other.lastModified()))
        // An image book is found where it was seen, not by the walk.
        assertEquals(image.path, library.pathOf(image.id))
    }

    @Test
    fun aBookWhosePathHoldsAnotherFileNowIsListedUnticked() {
        val edited = keep(File(shelf, "edited.cbz"), 300, 3)
        File(edited.path).writeBytes(ByteArray(320) { 9 })

        val result = MissingBooks(library, listOf(device)).run()!!

        assertTrue(result.complete)
        val book = result.missing.single()
        assertEquals(MissingBooks.Why.CHANGED, book.why)
        assertFalse(result.ticked(book))
    }

    @Test
    fun aBookKeptWithNoPlaceKnownIsListedUnticked() {
        val id = BookId(0xf00d)
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().putString("read", JSONArray(listOf(id.hex)).toString()).commit()

        val result = MissingBooks(library, listOf(device)).run()!!

        val book = result.missing.single()
        assertEquals(id, book.id)
        assertNull(book.seen)
        assertEquals(MissingBooks.Why.UNKNOWN, book.why)
        assertFalse(result.ticked(book))
    }

    @Test
    fun aDeviceThatCannotBeListedIsSkippedLikeAnyLocation() {
        val gone = keep(File(shelf, "gone.cbz"), 500, 7)
        File(gone.path).delete()
        val unlisted = MissingBooks.Location(BookLocation.DEVICE, "Device", File(tmp.root, "absent").path)

        val result = MissingBooks(library, listOf(unlisted)).run()!!

        assertEquals(listOf("Device"), result.skipped)
        assertTrue(result.missing.isEmpty())
        assertTrue(library.hasData(gone.id))
    }
}
