package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.testing.ref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** "Books read": a PDF or CB* archive each, a folder of loose images once, when all its images are read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BooksReadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var library: Library

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
    }

    private fun books() = library.stats().books

    /** A folder of [count] loose images, each its own content. */
    private fun folder(name: String, count: Int): Pair<File, List<File>> {
        val dir = File(tmp.root, name).apply { mkdirs() }
        return dir to (1..count).map { i -> File(dir, "$i.jpg").apply { writeBytes(ByteArray(50) { (it * i).toByte() }) } }
    }

    @Test
    fun archivesAndPdfsCountOneEachLooseImagesNone() {
        library.setReadMany(listOf(BookRef(BookId(1), "/x/One.cbz"), BookRef(BookId(2), "/x/Two.pdf"), BookRef(BookId(3), "/x/Three.cb7")), true)
        assertEquals(3, books())
        library.setRead(BookRef(BookId(4), "/x/loose/01.jpg"), true)
        assertEquals(3, books())
        library.setRead(BookRef(BookId(2), "/x/Two.pdf"), false)
        assertEquals(2, books())
    }

    @Test
    fun aFolderCountsOnceWhenAllItsImagesAreRead() {
        val (dir, images) = folder("Loose", 3)
        val refs = images.map { ref(it) }
        for (r in refs.take(2)) {
            library.setRead(r, true)
            FolderReads.updateFor(library, listOf(r))
        }
        assertEquals(0, books())
        library.setRead(refs[2], true)
        FolderReads.update(library, dir)
        assertEquals(1, books())
        assertTrue(library.isFolderRead(dir.absolutePath))
        // Reading an image again counts nothing more.
        FolderReads.updateFor(library, listOf(refs[0]))
        assertEquals(1, books())
        library.setRead(refs[1], false)
        FolderReads.updateFor(library, listOf(refs[1]))
        assertEquals(0, books())
        assertFalse(library.isFolderRead(dir.absolutePath))
    }

    @Test
    fun anImageKnownUnreadSettlesTheFolderWithoutReadingTheOthers() {
        val (dir, images) = folder("Partly", 3)
        val first = ref(images[0])
        library.setRead(first, true)
        // The last image known and not read; the second never looked at.
        ref(images[2])
        FolderReads.update(library, dir, images)
        assertFalse(library.isFolderRead(dir.absolutePath))
        assertNull(BookIds.last(images[1].absolutePath))
        // All read: each found and checked.
        library.setReadMany(images.map { ref(it) }, true)
        FolderReads.update(library, dir, images)
        assertTrue(library.isFolderRead(dir.absolutePath))
    }

    @Test
    fun hiddenImagesAndOtherFilesDoNotHoldAFolderBack() {
        val (dir, images) = folder("Mixed", 2)
        File(dir, ".hidden.jpg").writeBytes(byteArrayOf(9))
        File(dir, "Book.cbz").writeBytes(byteArrayOf(8))
        library.setReadMany(images.map { ref(it) }, true)
        FolderReads.update(library, dir)
        assertEquals(1, books())
    }

    @Test
    fun anUnlistableFolderChangesNothing() {
        library.setFolderRead("/nowhere/Gone", true)
        FolderReads.update(library, File(tmp.root, "gone"))
        FolderReads.updateFor(library, listOf(BookRef(BookId(7), File(tmp.root, "gone/1.jpg").absolutePath)))
        assertEquals(1, books())
    }

    @Test
    fun neverBelowZeroAndClearedOnlyWithStatistics() {
        library.setFolderRead("/x/A", true)
        library.clearStats()
        assertEquals(0, books())
        library.setFolderRead("/x/A", false)
        assertEquals(0, books())
        library.setFolderRead("/x/A", true)
        library.clearLibraryData()
        assertEquals(1, books())
        assertFalse(library.isFolderRead("/x/A"))
    }

    @Test
    fun aFolderCountedFollowsItsMoveAndIsForgottenDeleted() {
        library.setFolderRead("/x/A", true)
        library.replacePath("/x/A", "/y/A")
        assertTrue(library.isFolderRead("/y/A"))
        assertFalse(library.isFolderRead("/x/A"))
        library.removePathsUnder("/y/A")
        assertFalse(library.isFolderRead("/y/A"))
        assertEquals(1, books())
    }
}
