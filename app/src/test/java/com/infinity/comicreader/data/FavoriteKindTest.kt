package com.infinity.comicreader.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FavoriteKindTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A synthetic folder holding [files] and the folders in [sub]. */
    private class Dir(val files: List<String>, val sub: List<Dir> = emptyList()) : FolderView {
        override fun fileNames() = files
        override fun folders(): List<FolderView> = sub
    }

    private fun pages(n: Int, pattern: String = "p%03d.jpg") = (1..n).map { pattern.format(it) }
    private fun issues(n: Int) = (1..n).map { "Issue $it.cbz" }
    private fun comicFolder() = Dir(pages(8))
    private fun series(n: Int = 3) = Dir(issues(n) + "cover.jpg")

    private fun kind(dir: Dir, root: Boolean = false) = FavoriteClassifier.classifyFolder(dir, root)

    @Test
    fun theTopLevelAloneSettlesRootsAndPlainSets() {
        assertEquals(FavoriteKind.LIBRARY, FavoriteClassifier.quickKind(0, 0, 9, isRoot = true))
        assertEquals(FavoriteKind.SET, FavoriteClassifier.quickKind(3, 1, 3, isRoot = false))
        assertEquals(FavoriteKind.SET, kind(Dir(issues(3) + "cover.jpg", List(3) { Dir(issues(9)) })))
        // More subfolders than books, or enough images for loose pages: look inside.
        assertNull(FavoriteClassifier.quickKind(2, 0, 3, isRoot = false))
        assertNull(FavoriteClassifier.quickKind(3, 5, 0, isRoot = false))
    }

    @Test
    fun loosePagesAreAComic() {
        assertEquals(FavoriteKind.COMIC, kind(Dir(pages(5))))
        assertEquals(FavoriteKind.COMIC, kind(Dir(pages(12) + "ComicInfo.xml" + "cover.jpg")))
    }

    @Test
    fun tooFewOrUnpatternedImagesAreNotAComic() {
        assertFalse(FavoriteClassifier.isLooseComic(pages(4)))
        assertFalse(FavoriteClassifier.isLooseComic(listOf("cover.jpg", "promotional.jpg", "a.jpg", "b.jpg", "c.jpg")))
        assertFalse(FavoriteClassifier.isLooseComic(pages(5) + (1..6).map { "notes$it.txt" }))
    }

    @Test
    fun aFolderOfComicsIsASet() {
        assertEquals(FavoriteKind.SET, kind(series()))
        assertEquals(FavoriteKind.SET, kind(Dir(listOf("cover.jpg"), listOf(comicFolder(), comicFolder()))))
        assertEquals(FavoriteKind.SET, kind(Dir(listOf("Book.pdf"))))
    }

    @Test
    fun comicsTwoLevelsDownAreALibrary() {
        assertEquals(FavoriteKind.LIBRARY, kind(Dir(emptyList(), listOf(series(), series()))))
        // Deeper still, through a grouping folder.
        assertEquals(FavoriteKind.LIBRARY, kind(Dir(emptyList(), listOf(Dir(emptyList(), listOf(series()))))))
    }

    @Test
    fun aRootIsALibrary() {
        assertEquals(FavoriteKind.LIBRARY, kind(Dir(issues(3)), root = true))
    }

    @Test
    fun majorityWinsAndATieIsASet() {
        assertEquals(FavoriteKind.SET, kind(Dir(issues(2), listOf(series()))))
        assertEquals(FavoriteKind.LIBRARY, kind(Dir(issues(1), listOf(series(), series()))))
        assertEquals(FavoriteKind.SET, kind(Dir(issues(1), listOf(series()))))
    }

    @Test
    fun foldersWithoutComicsDoNotCount() {
        val extras = Dir(listOf("art.jpg", "notes.txt"))
        assertEquals(FavoriteKind.SET, kind(Dir(issues(1), listOf(extras, extras))))
        assertEquals(FavoriteKind.SET, kind(Dir(emptyList())))
    }

    @Test
    fun filesAreComics() {
        val book = tmp.newFile("Book.cbz")
        val image = tmp.newFile("cover.jpg")
        assertEquals(FavoriteKind.COMIC, FavoriteClassifier.classify(book, isRoot = false))
        assertEquals(FavoriteKind.COMIC, FavoriteClassifier.classify(image, isRoot = false))
    }

    @Test
    fun foldersOnDiskSkipHiddenEntries() {
        val dir = tmp.newFolder("series")
        repeat(3) { File(dir, "Issue $it.cbz").createNewFile() }
        File(dir, ".hidden").mkdir()
        File(dir, ".nomedia").createNewFile()
        val view = FileFolder(dir)
        assertEquals(3, view.fileNames().size)
        assertTrue(view.folders().isEmpty())
        assertEquals(FavoriteKind.SET, FavoriteClassifier.classify(dir, isRoot = false))
    }

    @Test
    fun cacheIsKeyedByModificationTime() {
        val cache = FavoriteKindCache(ApplicationProvider.getApplicationContext<Application>())
        cache.put("/a", 10L, FavoriteKind.LIBRARY)
        assertEquals(FavoriteKind.LIBRARY, cache.get("/a", 10L))
        assertNull(cache.get("/a", 11L))
        assertNull(cache.get("/b", 10L))
        cache.retain(listOf("/b"))
        assertNull(cache.get("/a", 10L))
    }
}
