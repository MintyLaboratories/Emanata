package com.infinity.comicreader.files

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FileClipboardTest {

    /** [rel] on the device, with the host's separators. */
    private fun local(rel: String) = File(File("/s").absoluteFile, rel).path

    private fun dir(path: String) = FileClipboard.Item(path, true)

    private fun file(path: String) = FileClipboard.Item(path, false)

    @Test
    fun aFolderTakesAlongWhatIsSelectedInIt() {
        val items = listOf(
            file(local("Shelf/a.cbz")), dir(local("Shelf")), file(local("Other/b.cbz")), dir(local("Shelf/Sub")), file(local("Shelf2/c.cbz"))
        )
        assertEquals(listOf(dir(local("Shelf")), file(local("Other/b.cbz")), file(local("Shelf2/c.cbz"))), FileClipboard.outermost(items))
    }

    @Test
    fun anItemListedTwiceIsKeptOnce() {
        assertEquals(listOf(dir(local("Shelf"))), FileClipboard.outermost(listOf(dir(local("Shelf")), dir(local("Shelf")))))
    }

    @Test
    fun itemsOnSharesToo() {
        val items = listOf(file("/net/a/Shelf/a.cbz"), dir("/net/a/Shelf"), file(local("Shelf/a.cbz")))
        assertEquals(listOf(dir("/net/a/Shelf"), file(local("Shelf/a.cbz"))), FileClipboard.outermost(items))
    }
}
