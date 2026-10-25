package com.infinity.comicreader.files

import android.app.Application
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.net.FakeRemoteFs
import com.infinity.comicreader.net.Remotes
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var provider: BookProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val info = ProviderInfo().apply {
            authority = BookUris.authority(context.packageName)
            grantUriPermissions = true
        }
        provider = Robolectric.buildContentProvider(BookProvider::class.java).create(info).get()
    }

    @After
    fun tearDown() {
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    private fun uri(path: String) = BookUris.of(context.packageName, path)

    @Test
    fun urisCarryThePathAndEndInTheName() {
        for (path in listOf("/storage/books/Ünïcode set/Vol 1 #2 (draft).cbz", "/net/s1/a/b.cbr", "/x/a%20b?.pdf")) {
            val uri = uri(path)
            assertEquals("content", uri.scheme)
            assertEquals(BookUris.authority(context.packageName), uri.authority)
            assertEquals(path.substringAfterLast('/'), uri.lastPathSegment)
            assertEquals(path, BookUris.pathOf(Uri.parse(uri.toString())))
        }
        assertNull(BookUris.pathOf(Uri.parse("content://${BookUris.authority(context.packageName)}/not*base64/x.cbz")))
        assertNull(BookUris.pathOf(Uri.parse("content://${BookUris.authority(context.packageName)}")))
    }

    @Test
    fun typesFollowTheExtension() {
        assertEquals("application/vnd.comicbook+zip", BookUris.mimeOf("a.CBZ"))
        assertEquals("application/vnd.comicbook-rar", BookUris.mimeOf("a.cbr"))
        assertEquals("application/pdf", BookUris.mimeOf("a.pdf"))
        assertEquals("image/jpeg", BookUris.mimeOf("a.jpeg"))
        assertEquals("application/octet-stream", BookUris.mimeOf("a"))
        assertEquals("application/x-cb7", provider.getType(uri("/b/a.cb7")))
    }

    @Test
    fun aDeviceFileIsNamedSizedAndReadAsItIs() {
        val bytes = ByteArray(70_000) { (it * 13).toByte() }
        val book = File(tmp.root, "One shot.cbz").apply { writeBytes(bytes) }
        val cursor = provider.query(uri(book.absolutePath), null, null, null, null)!!
        cursor.use {
            it.moveToFirst()
            assertEquals("One shot.cbz", it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(70_000L, it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        val read = provider.openFile(uri(book.absolutePath), "r").use { FileInputStream(it.fileDescriptor).readBytes() }
        assertArrayEquals(bytes, read)
    }

    @Test
    fun onlyTheColumnsAskedFor() {
        val book = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(3)) }
        provider.query(uri(book.absolutePath), arrayOf(OpenableColumns.SIZE, "_data"), null, null, null)!!.use {
            assertEquals(listOf(OpenableColumns.SIZE), it.columnNames.toList())
        }
    }

    @Test(expected = SecurityException::class)
    fun neverWritten() {
        val book = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(3)) }
        provider.openFile(uri(book.absolutePath), "rw")
    }

    @Test(expected = java.io.FileNotFoundException::class)
    fun aMissingFileIsNotFound() {
        provider.openFile(uri(File(tmp.root, "gone.cbz").absolutePath), "r")
    }

    @Test
    fun aShareFileIsSizedAsItsListingFoundIt() {
        val dir = tmp.newFolder("share")
        File(dir, "far.cbz").writeBytes(ByteArray(1234))
        val fake = FakeRemoteFs(dir)
        Remotes.serve(listOf(FakeRemoteFs.share())) { fake }
        Remotes.list("/net/s1")
        val asked = fake.attempts
        provider.query(uri("/net/s1/far.cbz"), null, null, null, null)!!.use {
            it.moveToFirst()
            assertEquals("far.cbz", it.getString(0))
            assertEquals(1234L, it.getLong(1))
        }
        // Known from the listing: the share is not asked again.
        assertEquals(asked, fake.attempts)
    }

    @Test
    fun aForgottenStatIsAskedOfTheShareAgain() {
        val dir = tmp.newFolder("share")
        val file = File(dir, "far.cbz").apply { writeBytes(ByteArray(1234)) }
        val fake = FakeRemoteFs(dir)
        Remotes.serve(listOf(FakeRemoteFs.share())) { fake }
        Remotes.list("/net/s1")
        file.writeBytes(ByteArray(2000))
        Remotes.forgetStat("/net/s1/far.cbz")
        provider.query(uri("/net/s1/far.cbz"), arrayOf(OpenableColumns.SIZE), null, null, null)!!.use {
            it.moveToFirst()
            assertEquals(2000L, it.getLong(0))
        }
    }
}
